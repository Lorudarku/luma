package dev.okami.luma.security

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.okami.luma.AppContainer
import dev.okami.luma.LumaApplication
import dev.okami.luma.data.local.LumaDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class VaultStatus { LOCKED, OPENING, OPEN, ERROR, KEY_ERROR, DATABASE_ERROR }

/** Recursos efímeros: destruir el store cancela todos los ViewModels financieros de la sesión. */
class VaultSession(val database: LumaDatabase, val container: AppContainer) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * Autoridad de sesión; una apertura tardía se cierra sin publicar datos tras bloquear.
 * Entradas: Application y operaciones de clave/apertura sustituibles para pruebas sin autenticación real.
 * La sobrecarga Application conserva la construcción estándar de AndroidViewModel; producción usa Keystore/SQLCipher.
 */
class VaultSessionManager @JvmOverloads constructor(
    application: Application,
    private val unlockKey: (DatabaseKeyManager) -> ByteArray = { it.unlock() },
    private val openDatabase: (Application, ByteArray, String) -> LumaDatabase = { context, key, name ->
        EncryptedDatabase.open(context, key, name)
    },
) : AndroidViewModel(application) {
    var keyManager = DatabaseKeyManager(application)
        private set
    var recovering by androidx.compose.runtime.mutableStateOf(false)
        private set
    private val mutableStatus = MutableStateFlow(VaultStatus.LOCKED)
    val status = mutableStatus.asStateFlow()
    var session: VaultSession? = null
        private set
    private var generation = 0L
    private val mutex = Mutex()
    // Recursos de disco: el propietario espera su cierre antes de cancelar este scope acotado.
    private val resourceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var maintenance = false
    var document by androidx.compose.runtime.mutableStateOf<Pair<android.net.Uri, Boolean>?>(null)

    /**
     * Abre tras autenticación del sistema y publica solo una sesión vigente.
     * Efectos: accede a clave/base en IO; distingue la etapa del fallo sin exponer excepciones ni secretos.
     */
    fun authenticated() {
        if (maintenance || mutableStatus.value == VaultStatus.OPENING || session != null) return
        val ticket = ++generation
        val openingKeyManager = keyManager
        mutableStatus.value = VaultStatus.OPENING
        // Completar la publicación o el cierre incluso si se destruye el propietario durante la apertura.
        resourceScope.launch {
            var failureStage = VaultStatus.KEY_ERROR
            try {
                // 1. Serializar apertura y cierre evita conexiones con ciclos de vida distintos.
                val opened = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val key = unlockKey(openingKeyManager)
                        failureStage = VaultStatus.DATABASE_ERROR
                        try { openDatabase(getApplication(), key, openingKeyManager.databaseName) }
                        finally { key.fill(0) }
                    }
                }
                // 2. onStop invalida el ticket incluso si la migración no pudo interrumpirse.
                if (ticket != generation) {
                    withContext(Dispatchers.IO) { opened.close() }
                } else {
                    session = VaultSession(opened, getApplication<LumaApplication>().createContainer(opened))
                    mutableStatus.value = VaultStatus.OPEN
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (ticket == generation) mutableStatus.value = failureStage
            }
        }
    }

    /** Bloquea sin esperar disco: limpia UI/store y programa cierre de Room protegido de cancelación. */
    fun lock() {
        generation++
        val previous = session
        session = null
        mutableStatus.value = VaultStatus.LOCKED
        previous?.viewModelStore?.clear()
        if (previous != null) resourceScope.launch(Dispatchers.IO) {
            mutex.withLock { previous.database.close() }
        }
    }

    /** Expone un error genérico sin incluir datos o mensajes del proveedor. */
    fun fail() { mutableStatus.value = VaultStatus.ERROR }

    /** Inicia recuperación en un slot aislado con una clave nueva; conserva intacta la base activa. */
    fun beginRecovery() {
        if (maintenance || recovering) return
        lock()
        keyManager = DatabaseKeyManager(getApplication(), java.util.UUID.randomUUID().toString())
        recovering = true
    }

    /** Abandona el staging por ID; comprueba el puntero bajo mutex y conserva todo ante incertidumbre. */
    fun cancelRecovery() {
        if (maintenance || !recovering) return
        val staging = keyManager
        val opened = session?.database
        lock()
        keyManager = DatabaseKeyManager(getApplication())
        recovering = false
        document = null
        resourceScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    opened?.close()
                    val context = getApplication<Application>()
                    if (canDiscardRecovery(staging.slot, DatabaseKeyManager.activeSlot(context))) {
                        staging.destroy()
                        check(context.deleteDatabase(staging.databaseName) || !context.getDatabasePath(staging.databaseName).exists())
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableStatus.value = VaultStatus.ERROR }
        }
    }

    /** Activa una restauración validada y durable; destruye la copia antigua solo después del commit. */
    fun completeRecovery() {
        if (maintenance || !recovering || session == null) return
        val context = getApplication<Application>()
        val replacement = keyManager
        val previous = DatabaseKeyManager(context)
        val restored = session?.database
        maintenance = true
        lock()
        mutableStatus.value = VaultStatus.OPENING
        resourceScope.launch {
            try {
                val promotion = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        restored?.close()
                        val file = context.getDatabasePath(replacement.databaseName)
                        java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
                        val directory = android.system.Os.open(requireNotNull(file.parent), android.system.OsConstants.O_RDONLY, 0)
                        try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
                        promoteRecovery(replacement.slot, replacement::activate,
                            { DatabaseKeyManager.activeSlot(context) }) {
                            check(previous.slot != replacement.slot && previous.slot != "unavailable")
                            previous.destroy()
                            check(context.deleteDatabase(previous.databaseName) || !context.getDatabasePath(previous.databaseName).exists())
                            check(context.deleteDatabase("luma.db") || !context.getDatabasePath("luma.db").exists())
                        }
                    }
                }
                // Una activación publicada deja de ser staging incluso si su limpieza falló.
                if (promotion != RecoveryPromotion.NOT_ACTIVATED) {
                    recovering = false
                    document = null
                    keyManager = DatabaseKeyManager(context)
                }
                mutableStatus.value = if (promotion == RecoveryPromotion.COMPLETE) VaultStatus.LOCKED else VaultStatus.ERROR
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableStatus.value = VaultStatus.ERROR }
            finally { maintenance = false }
        }
    }

    /** Destruye claves y archivos locales tras confirmación; serializa con aperturas y cierres. */
    fun erase() {
        if (maintenance) return
        val opened = session?.database
        maintenance = true
        lock()
        mutableStatus.value = VaultStatus.OPENING
        resourceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    mutex.withLock {
                        val context = getApplication<Application>()
                        opened?.close()
                        DatabaseKeyManager.eraseLocalVaults(context)
                    }
                }
                keyManager = DatabaseKeyManager(getApplication())
                recovering = false
                document = null
                mutableStatus.value = VaultStatus.LOCKED
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableStatus.value = VaultStatus.ERROR }
            finally { maintenance = false }
        }
    }

    /** Destruye estados inmediatamente y cancela el scope de recursos tras terminar aperturas/cierres pendientes. */
    override fun onCleared() {
        // 1. Invalidar tickets obliga a cualquier apertura pendiente a cerrar su resultado.
        lock()
        val pending = requireNotNull(resourceScope.coroutineContext[Job]).children.toList()
        // 2. Esperar solo hijos ya existentes evita autoespera y pérdida de conexiones tardías.
        resourceScope.launch { pending.joinAll(); resourceScope.cancel() }
    }
}
