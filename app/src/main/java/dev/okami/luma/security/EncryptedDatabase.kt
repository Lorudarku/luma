package dev.okami.luma.security

import android.content.ContentValues
import android.content.Context
import android.os.CancellationSignal
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.okami.luma.data.local.LumaDatabase
import java.security.MessageDigest

/** Apertura y conversión recuperable: el original solo se elimina tras verificar el destino. */
object EncryptedDatabase {
    const val FILE_NAME = "luma-vault.db"
    val TABLES = listOf("categories", "recurring_expenses", "recurring_occurrences", "transactions", "saved_filters")

    /** Abre Room cifrado en IO; convierte instalaciones previas y comprueba integridad antes de usarlas. */
    fun open(context: Context, key: ByteArray, name: String = FILE_NAME): LumaDatabase {
        System.loadLibrary("sqlcipher")
        net.zetetic.database.Logger.setTarget(net.zetetic.database.NoopTarget())
        val destination = context.getDatabasePath(name)
        // 1. Una promoción completada tiene prioridad sobre residuos del original.
        if (name == FILE_NAME && !destination.exists() && context.getDatabasePath("luma.db").exists()) {
            migrate(context, key)
        }
        val room = build(context, name, key)
        try {
            verify(room.openHelper.writableDatabase)
            // 2. Recuperar limpieza interrumpida únicamente tras abrir el destino válido.
            if (name == FILE_NAME && context.getDatabasePath("luma.db").exists()) check(context.deleteDatabase("luma.db"))
            return room
        } catch (error: Exception) {
            room.close()
            throw error
        }
    }

    /** Construye una instancia con esquema v4; entradas contexto/nombre/clave; migra sin borrado destructivo. */
    fun build(context: Context, name: String, key: ByteArray): LumaDatabase =
        Room.databaseBuilder(context, LumaDatabase::class.java, name)
            .openHelperFactory(PreservingCipherFactory(key))
            .addMigrations(LumaDatabase.MIGRATION_1_2, LumaDatabase.MIGRATION_2_3, LumaDatabase.MIGRATION_3_4)
            .addCallback(LumaDatabase.SEED_CALLBACK)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

    /** Copia una instantánea a un temporal cifrado y compara cada tabla antes de promocionarlo. */
    private fun migrate(context: Context, key: ByteArray) {
        val temporary = "luma-encrypting.db"
        context.deleteDatabase(temporary)
        val source = Room.databaseBuilder(context, LumaDatabase::class.java, "luma.db")
            .openHelperFactory(PreservingCipherFactory(null))
            .addMigrations(LumaDatabase.MIGRATION_1_2, LumaDatabase.MIGRATION_2_3, LumaDatabase.MIGRATION_3_4).build()
        val target = build(context, temporary, key)
        try {
            // 1. El único consumidor de la base antigua es esta migración, antes de crear repositorios.
            val old = source.openHelper.writableDatabase
            old.query("PRAGMA wal_checkpoint(FULL)").use { while (it.moveToNext()) { /* Consume checkpoint. */ } }
            val encrypted = target.openHelper.writableDatabase
            old.beginTransaction()
            try {
                encrypted.beginTransaction()
                try {
                    TABLES.forEach { table ->
                        encrypted.execSQL("DELETE FROM $table")
                        copyTable(old, encrypted, table)
                        check(digest(old, table).contentEquals(digest(encrypted, table)))
                    }
                    encrypted.setTransactionSuccessful()
                } finally { encrypted.endTransaction() }
                old.setTransactionSuccessful()
            } finally { old.endTransaction() }
            verify(encrypted)
        } finally {
            target.close()
            source.close()
        }
        // 2. Rename dentro del mismo directorio: un cierre no puede publicar una copia parcial.
        val file = context.getDatabasePath(temporary)
        java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
        check(file.renameTo(context.getDatabasePath(FILE_NAME)))
        val directory = android.system.Os.open(requireNotNull(file.parent), android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
    }

    /** Copia valores tipados; entradas conexiones/tabla constante/señal opcional; aborta al cancelar sin ejecutar DDL externo. */
    fun copyTable(source: SupportSQLiteDatabase, target: SupportSQLiteDatabase, table: String, signal: CancellationSignal? = null) {
        require(table in TABLES)
        // 1. SQLite de plataforma exige una señal no nula en su sobrecarga cancelable.
        val query = SimpleSQLiteQuery("SELECT * FROM $table")
        (if (signal == null) source.query(query) else source.query(query, signal)).use { cursor ->
            while (cursor.moveToNext()) {
                signal?.throwIfCanceled()
                val values = ContentValues()
                cursor.columnNames.forEachIndexed { index, column ->
                    when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> values.putNull(column)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(index))
                        android.database.Cursor.FIELD_TYPE_STRING -> values.put(column, cursor.getString(index))
                        else -> error("Unsupported field")
                    }
                }
                target.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
            }
        }
    }

    /** Verifica estructura/HMAC; entradas conexión y señal opcional; falla cerrado ante corrupción o cancelación. */
    fun verify(db: SupportSQLiteDatabase, signal: CancellationSignal? = null) {
        val integrity = SimpleSQLiteQuery("PRAGMA integrity_check")
        val cipher = SimpleSQLiteQuery("PRAGMA cipher_integrity_check")
        (if (signal == null) db.query(integrity) else db.query(integrity, signal)).use {
            check(it.moveToFirst() && it.getString(0) == "ok")
        }
        (if (signal == null) db.query(cipher) else db.query(cipher, signal)).use { check(!it.moveToFirst()) }
    }

    /** Calcula una huella en memoria por tabla con límites de campo y orden determinista. */
    private fun digest(db: SupportSQLiteDatabase, table: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        db.query("SELECT * FROM $table ORDER BY ${when (table) { "saved_filters" -> "name"; "recurring_occurrences" -> "ruleId, monthEpoch"; else -> "id" }}").use { c ->
            while (c.moveToNext()) for (i in 0 until c.columnCount) {
                val bytes = if (c.isNull(i)) byteArrayOf() else c.getString(i).toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(8).putInt(c.getType(i)).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest()
    }
}
