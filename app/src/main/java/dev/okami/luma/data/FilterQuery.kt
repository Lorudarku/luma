package dev.okami.luma.data

import androidx.sqlite.db.SimpleSQLiteQuery
import dev.okami.luma.domain.model.*
import java.time.ZoneId

/** Traduce criterios a SQL parametrizado y orden permitido; nunca interpola texto del usuario. */
object FilterQuery {
    /** Construye páginas o agregados usando idéntico predicado. Fechas locales se convierten a instantes. */
    fun build(filter: TransactionFilter, zone: ZoneId, aggregate: Boolean): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val clauses = mutableListOf<String>()
        // 1. Fechas con extremo superior exclusivo respetan días de 23 y 25 horas.
        filter.start?.let { clauses += "t.occurredAtEpochMillis >= ?"; args += it.atStartOfDay(zone).toInstant().toEpochMilli() }
        filter.end?.let { clauses += "t.occurredAtEpochMillis < ?"; args += it.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
        if (filter.types.isNotEmpty()) { clauses += "t.type IN (${filter.types.joinToString { "?" }})"; args.addAll(filter.types.map { it.name }) }
        if (filter.currencies.isNotEmpty()) { clauses += "t.currencyCode IN (${filter.currencies.joinToString { "?" }})"; args.addAll(filter.currencies) }
        if (filter.categoryIds.isNotEmpty() || filter.uncategorized) {
            val choices = mutableListOf<String>()
            if (filter.categoryIds.isNotEmpty()) { choices += "t.categoryId IN (${filter.categoryIds.joinToString { "?" }})"; args.addAll(filter.categoryIds) }
            if (filter.uncategorized) choices += "t.categoryId IS NULL"
            clauses += "(${choices.joinToString(" OR ")})"
        }
        filter.minimum?.let { clauses += "t.amountMinor >= ?"; args += it }
        filter.maximum?.let { clauses += "t.amountMinor <= ?"; args += it }
        if (filter.text.isNotEmpty()) {
            clauses += "COALESCE(t.description, '') LIKE ? ESCAPE '\\'"
            args += "%" + filter.text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        when (filter.recurrence) {
            RecurrenceFilter.ALL -> Unit
            RecurrenceFilter.NONE -> clauses += "t.recurringExpenseId IS NULL"
            RecurrenceFilter.LINKED -> clauses += "t.recurringExpenseId IS NOT NULL"
            RecurrenceFilter.ACTIVE -> clauses += "EXISTS (SELECT 1 FROM recurring_expenses r WHERE r.id = t.recurringExpenseId AND r.active = 1)"
            RecurrenceFilter.INACTIVE -> clauses += "EXISTS (SELECT 1 FROM recurring_expenses r WHERE r.id = t.recurringExpenseId AND r.active = 0)"
        }
        when (filter.description) {
            DescriptionFilter.ALL -> Unit
            DescriptionFilter.PRESENT -> clauses += "COALESCE(t.description, '') != ''"
            DescriptionFilter.EMPTY -> clauses += "COALESCE(t.description, '') = ''"
        }
        // 2. Columnas y direcciones proceden exclusivamente de enums internos.
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val order = when (filter.order) {
            TransactionOrder.NEWEST -> "t.occurredAtEpochMillis DESC, t.id DESC"
            TransactionOrder.OLDEST -> "t.occurredAtEpochMillis ASC, t.id ASC"
            TransactionOrder.LARGEST -> "t.amountMinor DESC, t.id DESC"
            TransactionOrder.SMALLEST -> "t.amountMinor ASC, t.id ASC"
        }
        val sql = if (aggregate) {
            "SELECT strftime('%Y-%m-%d', t.occurredAtEpochMillis / 1000, 'unixepoch', 'localtime') AS day, t.currencyCode, t.type, t.categoryId, SUM(t.amountMinor) AS amountMinor, COUNT(*) AS count FROM transactions t" + where + " GROUP BY day, t.currencyCode, t.type, t.categoryId ORDER BY day"
        } else {
            // 3. SQLCipher incluye SQLite moderno en todas las API soportadas. El saldo se calcula
            // una vez por histórico y antes de filtrar, con SUM entero que falla ante overflow.
            "WITH balances AS (SELECT transactions.*, SUM(CASE WHEN type = 'INCOME' THEN amountMinor ELSE -amountMinor END) OVER (PARTITION BY currencyCode ORDER BY occurredAtEpochMillis, id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS runningBalance FROM transactions) SELECT t.* FROM balances t" + where + " ORDER BY " + order
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
