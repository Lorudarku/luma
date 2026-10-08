package dev.okami.luma.security

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.okami.luma.data.local.LumaDatabase
import dev.okami.luma.data.local.recurrenceMonth
import dev.okami.luma.domain.model.*
import dev.okami.luma.domain.validation.TransactionDateCodec
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*

data class BackupSummary(val transactions: Long, val categories: Long, val rules: Long)

/** Contrato de copias elegido por el usuario; permite probar su coordinación sin Room ni Keystore. */
interface BackupRepository {
    /** Exporta a la URI indicada; entrada contraseña mutable; efecto: copia cifrada y limpieza del secreto. */
    suspend fun export(uri: Uri, password: CharArray)
    /** Copia una URI al staging privado acotado; salida: archivo cifrado cuya limpieza corresponde al consumidor. */
    suspend fun stage(uri: Uri): File
    /** Valida sin modificar el archivo; salida: alcance de la sustitución para la confirmación. */
    suspend fun inspect(file: File, password: CharArray): BackupSummary
    /** Sustituye datos atómicamente tras confirmar; entradas archivo y contraseña; limpia ambos al terminar. */
    suspend fun restore(file: File, password: CharArray)
}

/** Copias portables SQLCipher: ningún temporal ni proveedor recibe una base descifrada. */
class EncryptedBackupRepository(private val context: Context, private val database: LumaDatabase,
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone()) : BackupRepository {
    /**
     * Exporta una instantánea consistente y restaurable; entradas URI y contraseña de al menos 20 caracteres.
     * Valida esquema, modelos y límites antes de enviar bytes cifrados; limpia claves/temporal ante cualquier fallo.
     */
    override suspend fun export(uri: Uri, password: CharArray) {
        var bytes: ByteArray? = null
        var target: LumaDatabase? = null
        val name = "luma-export-${java.util.UUID.randomUUID()}.db"
        try {
            require(password.size >= 20)
            cancellableQueries { signal ->
                bytes = encodePassword(password)
                val room = EncryptedDatabase.build(context, name, requireNotNull(bytes))
                target = room
                val source = database.openHelper.writableDatabase
                val destination = room.openHelper.writableDatabase
                // 1. Copiar una instantánea; el consumidor no puede recibir datos parciales sin verificar.
                source.beginTransaction()
                try {
                    destination.beginTransaction()
                    try {
                        EncryptedDatabase.TABLES.forEach {
                            signal.throwIfCanceled()
                            destination.execSQL("DELETE FROM $it")
                            EncryptedDatabase.copyTable(source, destination, it, signal)
                        }
                        destination.setTransactionSuccessful()
                    } finally { destination.endTransaction() }
                    source.setTransactionSuccessful()
                } finally { source.endTransaction() }
                validate(destination, signal)
                room.close()
                val file = context.getDatabasePath(name)
                require(file.length() in 1..MAX_BACKUP_BYTES)
                // 2. Aplicar también el límite de importación a los bytes finales de exportación.
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            signal.throwIfCanceled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { target?.close() } finally {
                    bytes?.fill(0)
                    password.fill('\u0000')
                    context.deleteDatabase(name)
                }
            }
        }
    }

    /** Copia a staging privado hasta 256 MiB; entrada URI; comprueba cancelación y elimina parciales ante fallo. */
    override suspend fun stage(uri: Uri): File {
        var staged: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val file = File.createTempFile("restore-", ".lumabackup", context.noBackupFilesDir)
                staged = file
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_BACKUP_BYTES)
                            out.write(buffer, 0, read)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                file
            }
        } catch (error: Exception) {
            // También cubre la cancelación durante el retorno de withContext, cuando no entrega el archivo.
            withContext(NonCancellable + Dispatchers.IO) { staged?.delete() }
            throw error
        }
    }

    /** Verifica una copia sin modificarla; entradas archivo/contraseña; salida recuentos acotados para confirmar. */
    override suspend fun inspect(file: File, password: CharArray): BackupSummary = cancellableQueries { signal ->
        withBackup(file, password) { source -> validate(source, signal) }
    }

    /**
     * Revalida y sustituye tablas en una transacción; entradas archivo/contraseña confirmados.
     * Adapta v3 preservando meses y desvinculando ingresos antiguos; una cancelación antes del commit revierte todo.
     */
    override suspend fun restore(file: File, password: CharArray) {
        try {
            cancellableQueries { signal ->
                withBackup(file, password) { source ->
                    // 1. No confiar únicamente en la previsualización ni ejecutar DDL del origen.
                    validate(source, signal)
                    val target = database.openHelper.writableDatabase
                    target.beginTransaction()
                    try {
                        EncryptedDatabase.TABLES.reversed().forEach { target.execSQL("DELETE FROM $it") }
                        sourceTables(source.version).forEach { EncryptedDatabase.copyTable(source, target, it, signal) }
                        // 2. Sembrar el registro y reparar metadatos de las copias v3, sin alterar valores financieros.
                        LumaDatabase.MIGRATION_3_4.migrate(target)
                        signal.throwIfCanceled()
                        target.setTransactionSuccessful()
                    } finally { target.endTransaction() }
                }
            }
        } finally {
            password.fill('\u0000')
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    /** Abre solo lectura y desactiva confianza del esquema; entradas acotadas; limpia siempre la clave codificada. */
    private fun <T> withBackup(file: File, password: CharArray, block: (SupportSQLiteDatabase) -> T): T {
        require(file.length() in 1..MAX_BACKUP_BYTES)
        require(password.size in 1..MAX_PASSWORD_CHARACTERS)
        val bytes = encodePassword(password)
        try {
            SQLiteDatabase.openDatabase(file.absolutePath, bytes, null, SQLiteDatabase.OPEN_READONLY,
                { _, _ -> throw IllegalStateException("Invalid backup") }, null).use { db ->
                db.execSQL("PRAGMA trusted_schema=OFF")
                return block(db)
            }
        } finally { bytes.fill(0) }
    }

    /**
     * Valida esquema antes de consultar contenido; entrada SQLite externo y señal; salida resumen confirmado.
     * Rechaza SQL activo, tipos coercionables, límites y referencias inválidas; v3 se adapta al restaurar.
     */
    private fun validate(db: SupportSQLiteDatabase, signal: CancellationSignal): BackupSummary {
        require(db.version in 3..4)
        val tables = sourceTables(db.version)
        val actualTables = mutableSetOf<String>()
        // 1. Nunca contar una vista ni evaluar columnas generadas provenientes del archivo.
        query(db, "SELECT type, name, sql FROM sqlite_master LIMIT 101", signal).use { c ->
            var entries = 0
            while (c.moveToNext()) {
                signal.throwIfCanceled()
                require(++entries <= 100)
                val type = c.getString(0)
                val sql = c.getString(2).orEmpty()
                require(type == "table" || type == "index")
                require(!sql.contains("VIRTUAL", true) && !sql.contains("GENERATED", true))
                if (type == "table") {
                    require(c.getString(1) in tables + listOf("room_master_table", "android_metadata"))
                    actualTables.add(c.getString(1))
                }
            }
        }
        require(actualTables.containsAll(tables))
        val pageSize = query(db, "PRAGMA page_size", signal).use { it.moveToFirst(); it.getLong(0) }
        val pageCount = query(db, "PRAGMA page_count", signal).use { it.moveToFirst(); it.getLong(0) }
        require(pageSize in 512L..65_536L && pageCount in 1L..(MAX_BACKUP_BYTES / pageSize))
        val counts = tables.associateWith { count(db, it, signal) }
        EncryptedDatabase.verify(db, signal)
        validateStorageTypes(db, tables, signal)
        requireUnique(db, "transactions", "id", signal)
        requireUnique(db, "categories", "normalizedName", signal)
        requireUnique(db, "saved_filters", "name", signal)
        if (db.version == 4) requireUnique(db, "recurring_occurrences", "ruleId, monthEpoch", signal)
        val today = LocalDate.now(clock)
        val categories = mutableSetOf<String>()
        query(db, "SELECT id, name, emoji, isDefault, normalizedName FROM categories", signal).use { c ->
            while (c.moveToNext()) {
                signal.throwIfCanceled()
                val flag = c.getLong(3)
                require(flag in 0L..1L)
                val category = Category(c.getString(0), c.getString(1), c.getString(2), flag == 1L)
                require(c.getString(4) == normalizeCategoryName(category.name) && categories.add(category.id))
            }
        }
        val rules = mutableSetOf<String>()
        query(db, "SELECT id, amountMinor, currencyCode, dayOfMonth, startDateEpochDay, description, categoryId, active FROM recurring_expenses", signal).use { c ->
            while (c.moveToNext()) {
                signal.throwIfCanceled()
                val day = c.getLong(3)
                val active = c.getLong(7)
                require(day in 1L..31L && active in 0L..1L)
                val rule = RecurringExpense(c.getString(0), Money(c.getLong(1), c.getString(2)), day.toInt(), LocalDate.ofEpochDay(c.getLong(4)), c.getString(5), c.getString(6), active == 1L)
                require(rule.startDate in TransactionDateCodec.MIN_DATE..today && (rule.categoryId == null || rule.categoryId in categories))
                require(rules.add(rule.id))
            }
        }
        query(db, "SELECT id, type, amountMinor, currencyCode, occurredAtEpochMillis, description, categoryId, recurringExpenseId, recurrenceDateEpochDay FROM transactions", signal).use { c ->
            while (c.moveToNext()) {
                signal.throwIfCanceled()
                val type = TransactionType.valueOf(c.getString(1))
                val ruleId = c.getString(7)
                val recurrenceDate = if (c.isNull(8)) null else LocalDate.ofEpochDay(c.getLong(8))
                require((ruleId == null) == (recurrenceDate == null))
                require(ruleId == null || ruleId in rules)
                require(recurrenceDate == null || recurrenceDate in TransactionDateCodec.MIN_DATE..today)
                require(db.version == 3 || ruleId == null || type == TransactionType.EXPENSE)
                // 2. Las copias v3 pueden contener el antiguo ingreso vinculado; el destino lo desvincula.
                val transaction = Transaction(c.getString(0), type, Money(c.getLong(2), c.getString(3)), Instant.ofEpochMilli(c.getLong(4)), c.getString(5), c.getString(6), ruleId.takeIf { type == TransactionType.EXPENSE }, recurrenceDate.takeIf { type == TransactionType.EXPENSE })
                require(transaction.occurredAt.atZone(clock.zone).toLocalDate() in TransactionDateCodec.MIN_DATE..today)
                require(transaction.categoryId == null || transaction.categoryId in categories)
            }
        }
        if (db.version == 4) query(db, "SELECT ruleId, monthEpoch FROM recurring_occurrences", signal).use { c ->
            while (c.moveToNext()) {
                signal.throwIfCanceled()
                require(c.getString(0) in rules)
                require(c.getLong(1) in recurrenceMonth(TransactionDateCodec.MIN_DATE)..recurrenceMonth(today))
            }
        }
        // 3. Las preferencias también son entrada externa, incluidos sus enteros JSON originales.
        query(db, "SELECT name, specification FROM saved_filters", signal).use { c ->
            while (c.moveToNext()) {
                signal.throwIfCanceled()
                require(c.getString(0).trim() == c.getString(0) && c.getString(0).length in 1..40)
                dev.okami.luma.data.FilterCodec.decode(c.getString(1))
            }
        }
        return BackupSummary(counts.getValue("transactions"), counts.getValue("categories"), counts.getValue("recurring_expenses"))
    }

    /** Rechaza blobs/reales/columnas extra; entradas tablas conocidas y señal; comprueba tipos antes de modelos. */
    private fun validateStorageTypes(db: SupportSQLiteDatabase, tables: List<String>, signal: CancellationSignal) {
        val integerColumns = mapOf(
            "transactions" to setOf("amountMinor", "occurredAtEpochMillis", "recurrenceDateEpochDay"),
            "categories" to setOf("isDefault"),
            "recurring_expenses" to setOf("amountMinor", "dayOfMonth", "startDateEpochDay", "active"),
            "recurring_occurrences" to setOf("monthEpoch"),
            "saved_filters" to emptySet(),
        )
        val nullable = setOf("description", "categoryId", "recurringExpenseId", "recurrenceDateEpochDay")
        tables.forEach { table ->
            val expected = database.openHelper.writableDatabase.query("SELECT * FROM $table LIMIT 0").use { it.columnNames.toSet() }
            // table_info excluye columnas generadas ocultas; SELECT * debe coincidir exactamente también.
            query(db, "PRAGMA table_xinfo($table)", signal).use { c ->
                while (c.moveToNext()) require(c.getInt(c.getColumnIndexOrThrow("hidden")) == 0)
            }
            query(db, "SELECT * FROM $table", signal).use { c ->
                require(c.columnNames.toSet() == expected)
                while (c.moveToNext()) {
                    signal.throwIfCanceled()
                    c.columnNames.forEachIndexed { index, name ->
                        val type = c.getType(index)
                        if (type == android.database.Cursor.FIELD_TYPE_NULL) require(name in nullable)
                        else if (name in integerColumns.getValue(table)) require(type == android.database.Cursor.FIELD_TYPE_INTEGER)
                        else {
                            require(type == android.database.Cursor.FIELD_TYPE_STRING)
                            require(c.getString(index).length <= if (name == "specification") 16_384 else 200)
                        }
                    }
                }
            }
        }
    }

    /** Cuenta hasta límite+1 filas ordinarias; entrada tabla validada; rechaza exceso sin recorrer todo el origen. */
    private fun count(db: SupportSQLiteDatabase, table: String, signal: CancellationSignal): Long {
        val limit = when (table) {
            "transactions", "recurring_occurrences" -> 1_000_000L
            "categories", "recurring_expenses" -> 10_000L
            "saved_filters" -> 1_000L
            else -> error("Unknown table")
        }
        return query(db, "SELECT COUNT(*) FROM (SELECT 1 FROM $table LIMIT ${limit + 1})", signal).use {
            check(it.moveToFirst())
            it.getLong(0).also { count -> require(count <= limit) }
        }
    }

    /** Rechaza claves repetidas incluso si la copia omitió índices; entradas tabla/columnas constantes y señal. */
    private fun requireUnique(db: SupportSQLiteDatabase, table: String, columns: String, signal: CancellationSignal) {
        query(db, "SELECT 1 FROM $table GROUP BY $columns HAVING COUNT(*) > 1 LIMIT 1", signal).use {
            require(!it.moveToFirst())
        }
    }

    /** Obtiene la lista constante por versión; entrada validada; v3 no tiene registro de meses omitidos. */
    private fun sourceTables(version: Int): List<String> = if (version == 3) EncryptedDatabase.TABLES - "recurring_occurrences" else EncryptedDatabase.TABLES

    /** Ejecuta SQL constante con cancelación nativa; entradas conexión/SQL/señal; salida cursor que debe cerrarse. */
    private fun query(db: SupportSQLiteDatabase, sql: String, signal: CancellationSignal) = db.query(SimpleSQLiteQuery(sql), signal)

    /**
     * Enlaza cancelación estructurada con interrupción de SQLite en otro dispatcher.
     * Entrada: bloque IO y señal; salida: resultado del bloque; siempre retira el observador al terminar.
     */
    private suspend fun <T> cancellableQueries(block: (CancellationSignal) -> T): T = coroutineScope {
        val signal = CancellationSignal()
        val observer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try { withContext(Dispatchers.IO) { block(signal) } }
        finally { observer.cancelAndJoin() }
    }

    /** Codifica caracteres sin String adicional; entrada secreto mutable; salida bytes con buffer intermedio limpiado. */
    private fun encodePassword(password: CharArray): ByteArray {
        require(password.size <= MAX_PASSWORD_CHARACTERS)
        val encoded = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        return ByteArray(encoded.remaining()).also { encoded.get(it); if (encoded.hasArray()) encoded.array().fill(0) }
    }

    companion object {
        const val MAX_BACKUP_BYTES = 256L * 1024 * 1024
        const val MAX_PASSWORD_CHARACTERS = 1024
    }
}
