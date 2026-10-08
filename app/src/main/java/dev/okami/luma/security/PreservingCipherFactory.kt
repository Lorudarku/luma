package dev.okami.luma.security

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Impide que el callback SQLite predeterminado elimine archivos ante corrupción o una clave inválida. */
class PreservingCipherFactory(key: ByteArray?) : SupportSQLiteOpenHelper.Factory {
    private val encrypted = key != null
    private val delegate: SupportSQLiteOpenHelper.Factory = key?.let { SupportOpenHelperFactory(it) }
        ?: androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()

    /** Conserva callbacks Room y sustituye exclusivamente el manejo destructivo de corrupción. */
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val original = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
            /** Delega creación del esquema conocido por Room. */
            override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
            /** Delega únicamente migraciones registradas, nunca SQL de una copia externa. */
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(db, oldVersion, newVersion)
            /** Mantiene la política Room de rechazo de versiones futuras. */
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onDowngrade(db, oldVersion, newVersion)
            /**
             * Configura la conexión recibida sin persistir temporales en claro.
             * Efectos: activa protección de memoria y desactiva logs SQLCipher.
             * Consume el resultado de cipher_log_level mediante query: execSQL rechaza instrucciones con filas.
             */
            override fun onConfigure(db: SupportSQLiteDatabase) {
                // 1. Mantener la configuración del propietario y los temporales en memoria.
                original.onConfigure(db)
                db.execSQL("PRAGMA temp_store=MEMORY")
                if (encrypted) {
                    db.execSQL("PRAGMA cipher_memory_security=ON")
                    // 2. Este PRAGMA devuelve su nivel incluso al asignarlo; avanzar el cursor ejecuta la orden.
                    db.query("PRAGMA cipher_log_level=NONE").use { cursor ->
                        check(cursor.moveToFirst()) { "Cipher logging configuration failed" }
                    }
                }
            }
            /** Conserva validación de identidad y seguimiento de cambios de Room. */
            override fun onOpen(db: SupportSQLiteDatabase) = original.onOpen(db)
            /** Falla cerrado sin borrar la única copia del usuario. */
            override fun onCorruption(db: SupportSQLiteDatabase) { throw IllegalStateException("Vault integrity failure") }
        }
        return delegate.create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name).callback(callback).build())
    }
}
