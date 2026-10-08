package dev.okami.luma.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protege la clave SQLCipher con Android Keystore autenticado; prefiere hardware sin exigirlo. */
class DatabaseKeyManager(private val context: Context, val slot: String = activeSlot(context)) {
    init { require(slot == "default" || slot == "unavailable" || slot.matches(Regex("[a-f0-9-]{36}"))) }
    private val alias = if (slot == "default") "luma-vault-wrap-v1" else "luma-vault-wrap-v1-$slot"
    val databaseName = if (slot == "default") EncryptedDatabase.FILE_NAME else "luma-vault-$slot.db"
    private val envelope = AtomicFile(File(context.noBackupFilesDir, if (slot == "default") "vault-key-v1" else "vault-key-v1-$slot"))
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * Prepara Keystore antes de autenticar, conservando claves existentes cualquiera que sea su respaldo.
     * Entrada: credencial y almacenamiento del dispositivo. Efecto: crea una KEK solo si no protege datos.
     * No rebaja el cifrado ni elimina archivos cuando falta una clave o falla el proveedor.
     */
    fun prepare() {
        check(slot != "unavailable")
        check(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        // 1. Una clave perdida no autoriza reemplazar la protección de datos existentes.
        if (needsNewVaultKey(store.containsAlias(alias), atomicArtifactExists(envelope) || context.getDatabasePath(databaseName).exists())) {
            if (Build.VERSION.SDK_INT >= 28) generateWithHardwareFallback() else generate(false)
        }
        // 2. TEE/StrongBox son mejoras opcionales; no se rechaza el Keystore por software.
    }

    /** Devuelve 32 bytes tras autenticación reciente; crea un sobre atómico solo en altas nuevas. */
    fun unlock(): ByteArray {
        prepare()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // 1. Autenticar también versión/contexto del sobre para impedir usos cruzados.
        if (atomicArtifactExists(envelope)) {
            require(envelope.baseFile.length() <= 60 && File(envelope.baseFile.path + ".bak").length() <= 60)
            val bytes = envelope.readFully()
            require(bytes.size == 60)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes, 12, bytes.size - 12)
        }
        check(!context.getDatabasePath(databaseName).exists())
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            // 2. El proveedor genera un nonce nuevo; el archivo contiene únicamente el sobre.
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            val stream = envelope.startWrite()
            try {
                stream.write(cipher.iv + cipher.doFinal(secret))
                envelope.finishWrite(stream)
            } catch (error: Exception) {
                envelope.failWrite(stream)
                throw error
            }
            return secret
        } catch (error: Exception) {
            secret.fill(0)
            throw error
        }
    }

    /** Elimina el sobre y su clave tras confirmación de borrado total; no toca copias externas. */
    fun destroy() {
        envelope.delete()
        store.deleteEntry(alias)
    }

    /** Recupera la KEK no exportable. Salida: referencia a clave, nunca sus bytes. */
    private fun key(): SecretKey = store.getKey(alias, null) as SecretKey

    /** En API 28+, intenta StrongBox y usa el Keystore predeterminado (TEE/software) si no está disponible. */
    @androidx.annotation.RequiresApi(28)
    private fun generateWithHardwareFallback() {
        try { generate(context.packageManager.hasSystemFeature("android.hardware.strongbox_keystore")) }
        catch (_: StrongBoxUnavailableException) { generate(false) }
    }

    /** Genera AES-256 autenticado con ventana breve para compatibilidad con credenciales del sistema. */
    private fun generate(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        // 1. API 30 admite seleccionar explícitamente biometría fuerte y credencial.
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(15, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(15)
        }
        // 2. Sin StrongBox, Android elige el respaldo disponible; no se exige TEE.
        if (Build.VERSION.SDK_INT >= 28) builder.setIsStrongBoxBacked(strongBox)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(builder.build())
        }.generateKey()
    }

    /** Publica el slot por escritura atómica y fsync; entrada: slot preparado; una excepción puede ser posterior al commit. */
    fun activate() {
        val pointer = AtomicFile(File(context.noBackupFilesDir, "vault-active-v1"))
        val stream = pointer.startWrite()
        try {
            stream.write(slot.toByteArray(Charsets.US_ASCII))
            pointer.finishWrite(stream)
        } catch (error: Exception) { pointer.failWrite(stream); throw error }
        // No ejecutar failWrite tras finishWrite: el puntero publicado debe reconciliarse por lectura.
        val directory = android.system.Os.open(context.noBackupFilesDir.absolutePath,
            android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
    }

    companion object {
        /** Lee un identificador público acotado; un puntero dañado falla cerrado sin elegir otra base. */
        fun activeSlot(context: Context): String {
            val file = AtomicFile(File(context.noBackupFilesDir, "vault-active-v1"))
            if (!atomicArtifactExists(file)) return "default"
            return try {
                require(file.baseFile.length() <= 36 && File(file.baseFile.path + ".bak").length() <= 36)
                file.readFully().toString(Charsets.US_ASCII).also {
                    require(it == "default" || it.matches(Regex("[a-f0-9-]{36}")))
                }
            } catch (_: Exception) { "unavailable" }
        }

        /** Borra solo artefactos privados propios tras confirmación; incluye restos de exportación/recuperación. */
        fun eraseLocalVaults(context: Context) {
            val slots = "[a-f0-9-]{36}"
            // 1. Destruir todas las KEK propias vuelve irrecuperables incluso restos físicos del almacenamiento.
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliasPattern = Regex("luma-vault-wrap-v1(?:-$slots)?")
            store.aliases().toList().filter { aliasPattern.matches(it) }.forEach(store::deleteEntry)
            // 2. Los patrones anclados excluyen ficheros de otras funciones y copias elegidas fuera de la app.
            val databasePattern = Regex("luma(?:-vault(?:-$slots)?|-encrypting|-export-$slots)?\\.db")
            context.databaseList().filter { databasePattern.matches(it) }.forEach { name ->
                check(context.deleteDatabase(name) || !context.getDatabasePath(name).exists())
            }
            val privatePattern = Regex("(?:vault-key-v1(?:-$slots)?(?:\\.bak|\\.new)?|restore-.*\\.lumabackup)")
            context.noBackupFilesDir.listFiles().orEmpty().filter { privatePattern.matches(it.name) }.forEach { check(it.delete() || !it.exists()) }
            AtomicFile(File(context.noBackupFilesDir, "vault-active-v1")).delete()
        }
    }
}

/** Detecta el archivo principal o respaldo AtomicFile; entrada: sobre/puntero; salida: existencia sin perder un backup. */
private fun atomicArtifactExists(file: AtomicFile): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
