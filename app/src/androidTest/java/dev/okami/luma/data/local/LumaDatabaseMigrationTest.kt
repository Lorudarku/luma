package dev.okami.luma.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LumaDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LumaDatabase::class.java,
    )

    @Test
    /**
     * Verifica que la migración 1→2 conserva movimientos y crea los datos auxiliares.
     *
     * Pasos: crea un esquema v1, inserta un gasto ficticio, migra validando el esquema exportado
     * y comprueba tanto las columnas nulas como las nueve categorías predeterminadas.
     */
    fun migrationFromOneToTwoPreservesTransactionsAndSeedsCategories() {
        // 1. Preparar una base v1 real a partir del esquema histórico exportado.
        helper.createDatabase(TEST_DATABASE, 1).apply {
            insertLegacyTransaction()
            close()
        }

        // 2. Ejecutar la migración productiva y pedir a Room que valide todo el esquema resultante.
        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            LumaDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query(
                "SELECT id, categoryId, recurringExpenseId, recurrenceDateEpochDay " +
                    "FROM transactions WHERE id = 'legacy-transaction'"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("legacy-transaction", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertNull(cursor.getString(2))
                assertTrueSqlNull(cursor, 3)
            }
            database.query("SELECT COUNT(*) FROM categories").use { cursor ->
                cursor.moveToFirst()
                assertEquals(9, cursor.getInt(0))
            }
        }
    }

    /**
     * Inserta una fila compatible con el esquema original para probar conservación de datos.
     *
     * Receptor: conexión SQLite en versión 1.
     * Efectos: añade exclusivamente una transacción ficticia estable.
     */
    private fun SupportSQLiteDatabase.insertLegacyTransaction() {
        execSQL(
            "INSERT INTO transactions " +
                "(id, type, amountMinor, currencyCode, occurredAtEpochMillis, description) " +
                "VALUES ('legacy-transaction', 'EXPENSE', 1250, 'EUR', 1788084000000, " +
                "'Compra ficticia')"
        )
    }

    /**
     * Comprueba un valor SQL nulo sin forzar una conversión de tipo del cursor.
     *
     * Entrada: cursor posicionado e índice de columna esperado como `NULL`.
     */
    private fun assertTrueSqlNull(cursor: android.database.Cursor, columnIndex: Int) {
        assertEquals(android.database.Cursor.FIELD_TYPE_NULL, cursor.getType(columnIndex))
    }

    private companion object {
        const val TEST_DATABASE = "luma-migration-test"
    }

    /** Verifica la cadena v1→v3 completa: conserva importes y añade índices/preferencias sin destruir datos. */
    @Test fun migrationFromOneToThreePreservesMoney() {
        helper.createDatabase("luma-migration-v3-test", 1).apply { insertLegacyTransaction(); close() }
        helper.runMigrationsAndValidate("luma-migration-v3-test", 3, true,
            LumaDatabase.MIGRATION_1_2, LumaDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT amountMinor FROM transactions WHERE id = 'legacy-transaction'").use {
                it.moveToFirst(); assertEquals(1250L, it.getLong(0))
            }
            db.execSQL("INSERT INTO saved_filters(name, specification) VALUES ('Ficticio', '{}')")
        }
    }
    /** v3→v4 registra meses antes de desvincular un ingreso antiguo; conserva cantidades y categorías. */
    @Test fun migrationToFourPreservesConvertedOccurrences() {
        val name = "luma-migration-v4-test"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO recurring_expenses VALUES ('rule',1250,'EUR',31,20484,NULL,NULL,1)")
            execSQL("INSERT INTO transactions (id,type,amountMinor,currencyCode,occurredAtEpochMillis,recurringExpenseId,recurrenceDateEpochDay) VALUES ('converted','INCOME',1250,'EUR',1788084000000,'rule',20484)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, LumaDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT type, amountMinor, recurringExpenseId, recurrenceDateEpochDay FROM transactions").use {
                it.moveToFirst(); assertEquals("INCOME", it.getString(0)); assertEquals(1250L, it.getLong(1))
                assertNull(it.getString(2)); assertTrueSqlNull(it, 3)
            }
            db.query("SELECT ruleId, monthEpoch FROM recurring_occurrences").use {
                it.moveToFirst(); assertEquals("rule", it.getString(0))
                assertEquals(recurrenceMonth(java.time.LocalDate.ofEpochDay(20484)), it.getLong(1))
            }
        }
    }

    /** v1→v4 completa preserva el importe exacto y valida todos los índices/esquema Room. */
    @Test fun migrationFromOneToFourPreservesMoney() {
        val name = "luma-migration-v1-v4-test"
        helper.createDatabase(name, 1).apply { insertLegacyTransaction(); close() }
        helper.runMigrationsAndValidate(name, 4, true, LumaDatabase.MIGRATION_1_2,
            LumaDatabase.MIGRATION_2_3, LumaDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT amountMinor FROM transactions WHERE id = 'legacy-transaction'").use {
                it.moveToFirst(); assertEquals(1250L, it.getLong(0))
            }
        }
    }

}
