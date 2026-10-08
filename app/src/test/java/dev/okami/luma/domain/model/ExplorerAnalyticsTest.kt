package dev.okami.luma.domain.model

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Regresiones de filtros compartidos, comparación temporal y dinero exacto con fixtures ficticios. */
class ExplorerAnalyticsTest {
    private val today = LocalDate.of(2026, 9, 5)
    private val filter = TransactionFilter(start = today.withDayOfMonth(1), end = today, period = StatisticsPeriod.MONTH)

    /** Entrada: monedas mezcladas. Salida: ni límites ni orden monetario ambiguos se aceptan. */
    @Test fun amountCriteriaRequireOneCurrency() {
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(minimum = 1) }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(currencies = setOf("EUR", "USD"), order = TransactionOrder.LARGEST) }
        assertThrows(IllegalArgumentException::class.java) { TransactionFilter(currencies = setOf("EUR"), minimum = 2, maximum = 1) }
        assertEquals(0L, TransactionFilter(currencies = setOf("EUR"), minimum = 0).minimum)
    }

    /** Entrada: cinco días de septiembre. Salida: comparación con cinco días de agosto, no el mes entero. */
    @Test fun comparisonUsesElapsedNaturalPeriod() {
        val previous = previousFilter(filter, today)
        assertEquals(LocalDate.of(2026, 8, 1), previous.start)
        assertEquals(LocalDate.of(2026, 8, 5), previous.end)
        assertEquals(LocalDate.of(2026, 10, 1), shiftedRange(filter, 1, today).first)
        val february = previousFilter(filter.copy(start = LocalDate.of(2024, 3, 1), end = LocalDate.of(2024, 3, 31)), today)
        assertEquals(LocalDate.of(2024, 2, 29), february.end)
    }

    /** Entrada: saldo previo y selección parcial. Salida: saldo real conserva el histórico; selección parte de cero. */
    @Test fun historyAndSelectionStaySeparateByCurrency() {
        val selected = listOf(row("2026-09-02", "EXPENSE", 250, "EUR", "food"), row("2026-09-02", "EXPENSE", 900, "USD"))
        val history = listOf(row("2026-08-01", "INCOME", 1000)) + selected
        val data = calculateExplorerDashboard(selected, history, emptyList(), filter, "EUR", today)
        assertEquals(250L, data.expense)
        assertEquals(-250L, data.points.last().balanceMinor)
        assertEquals(0L, data.points.first().balanceMinor)
        assertEquals(1000L, data.realPoints.first().balanceMinor)
        assertEquals(750L, data.realPoints.last().balanceMinor)
        assertEquals(mapOf("food" to 250L), data.categoryExpenses)
        assertNull(data.percentage)
        assertEquals(1L, data.count)
    }

    /** Entrada: ingreso categorizado. Salida: no se presenta como gasto y el porcentaje negativo es exacto. */
    @Test fun categoriesExcludeIncomeAndNegativeComparisonIsExact() {
        val rows = listOf(row("2026-09-01", "INCOME", 100, category = "food"), row("2026-09-02", "EXPENSE", 50, category = "food"))
        val prior = listOf(row("2026-08-02", "EXPENSE", 100))
        val data = calculateExplorerDashboard(rows, rows, prior, filter, "EUR", today)
        assertEquals(50L, data.categoryExpenses["food"])
        assertEquals(150L, data.difference)
        assertEquals("150.00", data.percentage.toString())
    }

    /** Entrada: sumas fuera de Long. Efecto: error explícito, nunca un saldo envuelto. */
    @Test fun overflowingTotalsAreRejected() {
        val rows = listOf(row("2026-09-01", "INCOME", Long.MAX_VALUE), row("2026-09-02", "INCOME", 1))
        assertThrows(ArithmeticException::class.java) { calculateExplorerDashboard(rows, rows, emptyList(), filter, "EUR", today) }
    }

    /** Entrada: cien mil agregados. Salida: totales completos y número acotado de puntos, sin depender del paginado. */
    @Test fun hundredThousandRowsProduceCompleteBoundedSeries() {
        val rows = List(100_000) { row("2026-09-02", "EXPENSE", 1) }
        val data = calculateExplorerDashboard(rows, rows, emptyList(), filter.copy(start = LocalDate.of(1900, 1, 1)), "EUR", today)
        assertEquals(100_000L, data.expense)
        assertEquals(100_000L, data.count)
        assertEquals(-100_000L, data.realPoints.last().balanceMinor)
        assertTrue(data.points.size <= 122)
        assertEquals(100_000L, data.flow.sumOf { it.expense })
    }

    /** Entrada: ningún movimiento. Salida: una curva plana sin divisiones por cero ni porcentajes inventados. */
    @Test fun emptySelectionProducesFlatSeries() {
        val data = calculateExplorerDashboard(emptyList(), emptyList(), emptyList(), filter, "EUR", today)
        assertTrue(data.points.all { it.balanceMinor == 0L })
        assertTrue(data.points.none { it.date > today })
        assertNull(data.percentage)
    }

    /** Construye únicamente agregados ficticios; entradas explícitas y ninguna dependencia Android. */
    private fun row(day: String, type: String, amount: Long, currency: String = "EUR", category: String? = null) =
        AggregateRow(day, currency, type, category, amount, 1)
}
