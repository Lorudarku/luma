package dev.okami.luma.security

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.okami.luma.data.FilterCodec
import dev.okami.luma.data.FilterQuery
import dev.okami.luma.data.local.LumaDatabase
import dev.okami.luma.domain.model.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pruebas sobre SQLCipher real; claves ficticias de test, nunca Keystore o datos del usuario. */
@RunWith(AndroidJUnit4::class)
class EncryptedStorageTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)

    /**
     * Regresión: abrir SQLCipher ejecuta cipher_log_level mediante cursor, no execSQL.
     * Entrada: base nueva y clave ficticia. Salida: conexión válida que conserva los logs desactivados.
     * Efectos: crea y elimina únicamente un archivo de prueba con nombre único.
     */
    @Test fun newVaultOpensWithLoggingDisabled() {
        val name = "test-pragma-${java.util.UUID.randomUUID()}.db"
        try {
            EncryptedDatabase.open(base, key.copyOf(), name).use { room ->
                room.openHelper.writableDatabase.query("PRAGMA cipher_log_level").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("NONE", it.getString(0))
                }
                room.openHelper.writableDatabase.query("PRAGMA cipher_provider").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("libtomcrypt", it.getString(0).lowercase(java.util.Locale.ROOT))
                }
            }
        } finally { base.deleteDatabase(name) }
    }

    /** Entrada: SQLite heredado aislado. Salida: filas intactas en SQLCipher sin cabecera o descripción legible. */
    @Test fun migratesLegacyWithoutLosingRowsAndRejectsWrongKey() {
        val root = File(base.cacheDir, "vault-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            /** Redirige solo los nombres de prueba a un directorio exclusivo, sin tocar luma.db real. */
            override fun getDatabasePath(name: String): File = File(root, name)
            /** Mantiene también SQLiteOpenHelper dentro del directorio de fixture en su apertura de tres argumentos. */
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory)
            /** Redirige la apertura con manejador de errores; evita delegar al contexto original. */
            override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase =
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
            /** Elimina exclusivamente archivos del directorio aislado de esta prueba. */
            override fun deleteDatabase(name: String): Boolean = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        try {
            // 1. Crear un legado con el esquema actual; la cadena v1→v3 se verifica en MigrationTestHelper.
            Room.databaseBuilder(context, LumaDatabase::class.java, "luma.db").build().use { room ->
                insertFixture(room.openHelper.writableDatabase)
            }
            EncryptedDatabase.open(context, key.copyOf()).use { room ->
                room.openHelper.writableDatabase.query("SELECT amountMinor, description FROM transactions").use {
                    assertTrue(it.moveToFirst()); assertEquals(1250L, it.getLong(0)); assertEquals("FICTIONAL-PRIVATE-MARKER", it.getString(1))
                }
            }
            // 2. Una clave errónea no puede borrar el destino ni volver a inicializarlo vacío.
            val file = context.getDatabasePath(EncryptedDatabase.FILE_NAME)
            val bytes = file.readBytes()
            assertFalse(bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("FICTIONAL-PRIVATE-MARKER"))
            assertFalse(context.getDatabasePath("luma.db").exists())
            assertThrows(Exception::class.java) { EncryptedDatabase.open(context, ByteArray(32) { 9 }).close() }
            assertArrayEquals(bytes, file.readBytes())
            EncryptedDatabase.open(context, key.copyOf()).close()
        } finally { root.deleteRecursively() }
    }

    /** Entrada: copia válida, contraseña errónea y página alterada. Salida: rechazo sin modificar el destino. */
    @Test fun backupValidationAndRestoreAreAtomic() = runBlocking {
        System.loadLibrary("sqlcipher")
        val sourceName = "test-backup-${java.util.UUID.randomUUID()}.db"
        val targetName = "test-restore-${java.util.UUID.randomUUID()}.db"
        val password = "fictional backup secret 12345"
        val target = EncryptedDatabase.build(base, targetName, key.copyOf())
        try {
            EncryptedDatabase.build(base, sourceName, password.toByteArray()).use { insertFixture(it.openHelper.writableDatabase) }
            val repository = EncryptedBackupRepository(base, target, clock)
            val source = base.getDatabasePath(sourceName)
            assertEquals(1L, repository.inspect(source, password.toCharArray()).transactions)
            var rejected = false
            try { repository.inspect(source, "incorrect secret".toCharArray()) } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
            val damaged = File.createTempFile("damaged-test-", ".db", base.cacheDir)
            try {
                source.copyTo(damaged, overwrite = true)
                java.io.RandomAccessFile(damaged, "rw").use { it.seek(100); val original = it.read(); it.seek(100); it.write(original xor 1) }
                rejected = false
                try { repository.inspect(damaged, password.toCharArray()) } catch (_: Exception) { rejected = true }
                assertTrue(rejected)
            } finally { damaged.delete() }
            repository.restore(source, password.toCharArray())
            target.openHelper.writableDatabase.query("SELECT COUNT(*) FROM transactions").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        } finally { target.close(); base.deleteDatabase(sourceName); base.deleteDatabase(targetName) }
    }

    /** Entrada: filtro con metacaracteres SQL. Salida: coincidencia literal y saldo que incluye filas ocultas. */
    @Test fun parameterizedFiltersKeepHistoricalBalanceAndExactCodec() {
        Room.inMemoryDatabaseBuilder(base, LumaDatabase::class.java).build().use { room ->
            val db = room.openHelper.writableDatabase
            insertFixture(db)
            db.execSQL("INSERT INTO transactions (id,type,amountMinor,currencyCode,occurredAtEpochMillis,description) VALUES ('earlier','INCOME',2000,'EUR',0,NULL)")
            val filter = TransactionFilter(currencies = setOf("EUR"), text = "FICTIONAL", minimum = 1000)
            assertEquals(filter, FilterCodec.decode(FilterCodec.encode(filter)))
            db.query(FilterQuery.build(filter, ZoneOffset.UTC, false)).use {
                assertEquals(1, it.count); it.moveToFirst(); assertEquals(750L, it.getLong(it.getColumnIndexOrThrow("runningBalance")))
            }
            db.query(FilterQuery.build(filter.copy(text = "%' OR 1=1 --"), ZoneOffset.UTC, false)).use { assertEquals(0, it.count) }
        }
    }

    /** Una vista recursiva se rechaza por esquema, antes de ejecutar COUNT; el límite temporal detecta regresiones. */
    @Test fun recursiveViewIsRejectedBeforeReadingItsRows() = runBlocking {
        malformedCopy { db ->
            db.execSQL("DROP TABLE transactions")
            db.execSQL("CREATE VIEW transactions AS WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n) SELECT x FROM n")
        }
    }

    /** Booleanos/días de 64 bits y pseudo monedas no pueden superar la validación por truncamiento. */
    @Test fun oversizedIntegersAndUnsupportedCurrencyAreRejected() = runBlocking {
        malformedCopy { it.execSQL("UPDATE categories SET isDefault = 4294967297") }
        malformedCopy { db ->
            db.execSQL("INSERT INTO recurring_expenses VALUES ('rule',1000,'EUR',4294967297,20701,NULL,NULL,1)")
        }
        malformedCopy { db ->
            db.execSQL("INSERT INTO recurring_expenses VALUES ('rule',1000,'EUR',5,20701,NULL,NULL,4294967297)")
        }
        malformedCopy { it.execSQL("UPDATE transactions SET currencyCode = 'XXX'") }
    }

    /** Los límites JSON deben ser enteros originales, incluidos Long.MAX_VALUE; strings/reales/versiones se rechazan. */
    @Test fun jsonNumbersAreValidatedWithoutCoercion() {
        val valid = FilterCodec.encode(TransactionFilter(currencies = setOf("EUR"), minimum = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, FilterCodec.decode(valid).minimum)
        for (value in listOf("1.5", "1e20", "\"42\"", "9223372036854775808")) {
            assertThrows(IllegalArgumentException::class.java) { FilterCodec.decode(valid.replace(Long.MAX_VALUE.toString(), value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { FilterCodec.decode(valid.replace("\"v\":1", "\"v\":4294967297")) }
    }

    /** Una copia v3 con el antiguo ingreso vinculado se adapta sin cambiar su importe ni regenerar el gasto. */
    @Test fun legacyBackupPreservesConvertedIncomeAndHandledMonth() = runBlocking {
        System.loadLibrary("sqlcipher")
        val sourceName = "test-v3-${java.util.UUID.randomUUID()}.db"
        val targetName = "test-v4-${java.util.UUID.randomUUID()}.db"
        val password = "fictional backup secret 12345"
        val date = java.time.LocalDate.of(2026, 9, 5)
        val target = EncryptedDatabase.build(base, targetName, key.copyOf())
        try {
            EncryptedDatabase.build(base, sourceName, password.toByteArray()).use { room ->
                val db = room.openHelper.writableDatabase
                db.execSQL("INSERT INTO recurring_expenses VALUES ('rule',1000,'EUR',5,?,NULL,NULL,1)", arrayOf(date.toEpochDay()))
                db.execSQL("INSERT INTO transactions (id,type,amountMinor,currencyCode,occurredAtEpochMillis,recurringExpenseId,recurrenceDateEpochDay) VALUES ('converted','INCOME',1000,'EUR',?,'rule',?)",
                    arrayOf(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), date.toEpochDay()))
                db.execSQL("DROP TABLE recurring_occurrences")
                db.version = 3
            }
            val repository = EncryptedBackupRepository(base, target, clock)
            assertEquals(1L, repository.inspect(base.getDatabasePath(sourceName), password.toCharArray()).transactions)
            repository.restore(base.getDatabasePath(sourceName), password.toCharArray())
            dev.okami.luma.data.RecurringExpenseRepositoryImpl(target, target.recurringExpenseDao(), target.transactionDao())
                .generateDue(date, ZoneOffset.UTC)
            target.openHelper.writableDatabase.query("SELECT type,amountMinor,recurringExpenseId FROM transactions").use {
                assertEquals(1, it.count); it.moveToFirst(); assertEquals("INCOME", it.getString(0))
                assertEquals(1000L, it.getLong(1)); assertNull(it.getString(2))
            }
        } finally { target.close(); base.deleteDatabase(sourceName); base.deleteDatabase(targetName) }
    }

    /** La copia v4 preserva una cuota omitida; exportar/verificar/restaurar no cambia su semántica. */
    @Test fun encryptedBackupRoundTripKeepsDeletedOccurrences() = runBlocking {
        System.loadLibrary("sqlcipher")
        val name = "test-roundtrip-${java.util.UUID.randomUUID()}.db"
        val file = File.createTempFile("roundtrip-test-", ".lumabackup", base.cacheDir)
        val password = "fictional backup secret 12345"
        val room = EncryptedDatabase.build(base, name, key.copyOf())
        try {
            val date = java.time.LocalDate.of(2026, 9, 5)
            val rules = dev.okami.luma.data.RecurringExpenseRepositoryImpl(room, room.recurringExpenseDao(), room.transactionDao())
            rules.saveAndGenerate(RecurringExpense("rule", Money(1234, "BHD"), 5, date, null, null, true), date, ZoneOffset.UTC)
            val id = room.transactionDao().observeAll().first().single().id
            dev.okami.luma.data.TransactionRepositoryImpl(room.transactionDao()).delete(id)
            val repository = EncryptedBackupRepository(base, room, clock)
            repository.export(android.net.Uri.fromFile(file), password.toCharArray())
            assertEquals(0L, repository.inspect(file, password.toCharArray()).transactions)
            repository.restore(file, password.toCharArray())
            rules.generateDue(date, ZoneOffset.UTC)
            assertTrue(room.transactionDao().observeAll().first().isEmpty())
        } finally { room.close(); file.delete(); base.deleteDatabase(name) }
    }

    /** La consulta real procesa 100.000 filas sin subconsulta correlacionada y conserva saldos con filas ocultas. */
    @Test fun largeHistoryUsesSingleWindowPassAndRejectsOverflow() {
        System.loadLibrary("sqlcipher")
        val name = "test-query-${java.util.UUID.randomUUID()}.db"
        try {
            EncryptedDatabase.build(base, name, key.copyOf()).use { room ->
                val db = room.openHelper.writableDatabase
                db.beginTransaction()
                try {
                    val statement = db.compileStatement("INSERT INTO transactions(id,type,amountMinor,currencyCode,occurredAtEpochMillis) VALUES (?, ?, 1, 'EUR', ?)")
                    statement.use {
                        repeat(100_000) { index ->
                            it.bindString(1, "row-${index.toString().padStart(6, '0')}")
                            it.bindString(2, if (index % 2 == 0) "INCOME" else "EXPENSE")
                            it.bindLong(3, 1577836800000L + index)
                            it.executeInsert()
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                val filter = TransactionFilter(types = setOf(TransactionType.EXPENSE))
                val query = FilterQuery.build(filter, ZoneOffset.UTC, false)
                db.query(androidx.sqlite.db.SimpleSQLiteQuery("EXPLAIN QUERY PLAN " + query.sql, arrayOf("EXPENSE"))).use { cursor ->
                    while (cursor.moveToNext()) assertFalse(cursor.getString(3).contains("CORRELATED"))
                }
                val started = android.os.SystemClock.elapsedRealtime()
                db.query(androidx.sqlite.db.SimpleSQLiteQuery(query.sql + " LIMIT 40", arrayOf("EXPENSE"))).use {
                    assertEquals(40, it.count)
                    while (it.moveToNext()) assertEquals(0L, it.getLong(it.getColumnIndexOrThrow("runningBalance")))
                }
                println("SQL ficticio 100000 filas, página 40: ${android.os.SystemClock.elapsedRealtime() - started} ms")
                db.execSQL("DELETE FROM transactions")
                db.execSQL("INSERT INTO transactions(id,type,amountMinor,currencyCode,occurredAtEpochMillis) VALUES ('a','INCOME',9223372036854775807,'EUR',1), ('b','INCOME',1,'EUR',2)")
                assertThrows(Exception::class.java) {
                    db.query(FilterQuery.build(TransactionFilter(), ZoneOffset.UTC, false)).use { it.moveToFirst() }
                }
            }
        } finally { base.deleteDatabase(name) }
    }

    /** Crea una copia cifrada malformada y exige rechazo rápido sin modificar el destino; limpia todos los fixtures. */
    private suspend fun malformedCopy(change: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit) {
        System.loadLibrary("sqlcipher")
        val sourceName = "test-malformed-${java.util.UUID.randomUUID()}.db"
        val targetName = "test-untouched-${java.util.UUID.randomUUID()}.db"
        val password = "fictional backup secret 12345"
        val target = EncryptedDatabase.build(base, targetName, key.copyOf())
        try {
            insertFixture(target.openHelper.writableDatabase)
            EncryptedDatabase.build(base, sourceName, password.toByteArray()).use { room ->
                insertFixture(room.openHelper.writableDatabase)
                change(room.openHelper.writableDatabase)
            }
            var rejected = false
            try {
                kotlinx.coroutines.withTimeout(5000) {
                    EncryptedBackupRepository(base, target, clock).inspect(base.getDatabasePath(sourceName), password.toCharArray())
                }
            } catch (error: Exception) {
                assertFalse("Schema validation must reject before a timeout", error is kotlinx.coroutines.TimeoutCancellationException)
                rejected = true
            }
            assertTrue(rejected)
            target.openHelper.writableDatabase.query("SELECT amountMinor FROM transactions").use {
                it.moveToFirst(); assertEquals(1250L, it.getLong(0))
            }
        } finally { target.close(); base.deleteDatabase(sourceName); base.deleteDatabase(targetName) }
    }

    /** Inserta una transacción ficticia sin categorías; sirve para migración, backup y filtros. */
    private fun insertFixture(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO transactions (id,type,amountMinor,currencyCode,occurredAtEpochMillis,description) VALUES ('fixture','EXPENSE',1250,'EUR',1788307200000,'FICTIONAL-PRIVATE-MARKER')")
    }

    /** Cierra Room al terminar cada fixture, incluso si falla una aserción o la apertura. */
    private inline fun <T> LumaDatabase.use(block: (LumaDatabase) -> T): T = try { block(this) } finally { close() }
}
