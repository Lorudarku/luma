package dev.okami.luma.data.local

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        RecurringExpenseEntity::class,
        SavedFilterEntity::class,
        RecurringOccurrenceEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class LumaDatabase : RoomDatabase() {
    /** Acceso a consultas paginadas y preferencias cifradas de la sesión abierta. */
    abstract fun explorerDao(): ExplorerDao
    /**
     * Expone el DAO generado por Room para leer y mutar transacciones.
     *
     * Salida: implementación de [TransactionDao] gestionada por la instancia de la base de datos.
     * Efectos: ninguno hasta que el DAO invoque una operación de lectura o escritura.
     */
    abstract fun transactionDao(): TransactionDao

    /**
     * Expone el DAO de categorías gestionado por Room.
     *
     * Salida: implementación local reactiva para categorías.
     */
    abstract fun categoryDao(): CategoryDao

    /**
     * Expone el DAO de reglas de gasto mensual.
     *
     * Salida: implementación local para observar y generar recurrencias.
     */
    abstract fun recurringExpenseDao(): RecurringExpenseDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            /**
             * Conserva meses ya contabilizados antes de reparar vínculos antiguos incompatibles.
             * Entrada: esquema v3. Efectos: añade el registro mensual y desvincula ingresos sin alterar importes.
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Sembrar el histórico también para cuotas que después se desvincularán.
                db.execSQL("CREATE TABLE IF NOT EXISTS recurring_occurrences (ruleId TEXT NOT NULL, monthEpoch INTEGER NOT NULL, PRIMARY KEY(ruleId, monthEpoch))")
                db.execSQL("""
                    INSERT OR IGNORE INTO recurring_occurrences(ruleId, monthEpoch)
                    SELECT recurringExpenseId,
                        CAST(strftime('%Y', recurrenceDateEpochDay * 86400, 'unixepoch') AS INTEGER) * 12
                        + CAST(strftime('%m', recurrenceDateEpochDay * 86400, 'unixepoch') AS INTEGER) - 1
                    FROM transactions WHERE recurringExpenseId IS NOT NULL AND recurrenceDateEpochDay IS NOT NULL
                """.trimIndent())
                // 2. Un ingreso conserva su valor y deja de representar una cuota de gasto.
                db.execSQL("UPDATE transactions SET recurringExpenseId = NULL, recurrenceDateEpochDay = NULL WHERE type != 'EXPENSE'")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            /** Añade índices de consultas y filtros privados sin modificar movimientos existentes. */
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS saved_filters (name TEXT NOT NULL PRIMARY KEY, specification TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_currencyCode_occurredAtEpochMillis ON transactions(currencyCode, occurredAtEpochMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_categoryId_occurredAtEpochMillis ON transactions(categoryId, occurredAtEpochMillis)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            /**
             * Amplía el esquema sin perder las transacciones instaladas con la versión 1.
             *
             * Entrada: conexión SQLite abierta por Room.
             * Efectos: añade columnas, tablas, índices y categorías iniciales.
             */
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Las columnas opcionales conservan todas las filas existentes sin conversión.
                db.execSQL("ALTER TABLE transactions ADD COLUMN categoryId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurringExpenseId TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recurrenceDateEpochDay INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_categoryId " +
                        "ON transactions(categoryId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_transactions_recurringExpenseId_recurrenceDateEpochDay " +
                        "ON transactions(recurringExpenseId, recurrenceDateEpochDay)"
                )

                // 2. Crear los catálogos nuevos antes de sembrar datos predeterminados.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS categories (" +
                        "id TEXT NOT NULL, name TEXT NOT NULL, normalizedName TEXT NOT NULL, " +
                        "emoji TEXT NOT NULL, isDefault INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_categories_normalizedName " +
                        "ON categories(normalizedName)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recurring_expenses (" +
                        "id TEXT NOT NULL, amountMinor INTEGER NOT NULL, currencyCode TEXT NOT NULL, " +
                        "dayOfMonth INTEGER NOT NULL, startDateEpochDay INTEGER NOT NULL, " +
                        "description TEXT, categoryId TEXT, active INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recurring_expenses_categoryId " +
                        "ON recurring_expenses(categoryId)"
                )
                insertDefaultCategories(db)
            }
        }

        val SEED_CALLBACK = object : Callback() {
            /**
             * Inserta categorías base al crear una base de datos nueva.
             *
             * Entrada: conexión recién creada.
             * Efectos: añade presets con IDs estables mediante `INSERT OR IGNORE`.
             */
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                insertDefaultCategories(db)
            }
        }

        /**
         * Inserta el catálogo inicial usando SQL constante y datos ficticios de producto.
         *
         * Entrada: conexión SQLite en creación o migración.
         * Efectos: crea solo presets ausentes y nunca sobrescribe categorías del usuario.
         */
        private fun insertDefaultCategories(database: SupportSQLiteDatabase) {
            DEFAULT_CATEGORIES.forEach { category ->
                database.execSQL(
                    "INSERT OR IGNORE INTO categories " +
                        "(id, name, normalizedName, emoji, isDefault) VALUES (?, ?, ?, ?, 1)",
                    arrayOf(category.id, category.name, category.normalizedName, category.emoji),
                )
            }
        }
    }
}

private data class DefaultCategorySeed(
    val id: String,
    val name: String,
    val normalizedName: String,
    val emoji: String,
)

private val DEFAULT_CATEGORIES = listOf(
    DefaultCategorySeed("default-food", "Alimentación", "alimentación", "🍎"),
    DefaultCategorySeed("default-tech", "Tecnología", "tecnología", "💻"),
    DefaultCategorySeed("default-eating-out", "Comer fuera", "comer fuera", "🍽️"),
    DefaultCategorySeed("default-fuel", "Combustible", "combustible", "⛽"),
    DefaultCategorySeed("default-home", "Vivienda", "vivienda", "🏠"),
    DefaultCategorySeed("default-transport", "Transporte", "transporte", "🚗"),
    DefaultCategorySeed("default-health", "Salud", "salud", "💊"),
    DefaultCategorySeed("default-leisure", "Ocio", "ocio", "🎮"),
    DefaultCategorySeed("default-other", "Otros", "otros", "📦"),
)
