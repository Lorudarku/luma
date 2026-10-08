package dev.okami.luma.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CategorySpendingTest {
    @Test
    /**
     * Verifica totales naturales, exclusión de ingresos, separación de monedas y orden del detalle.
     */
    fun `calculates category month and year spending by currency`() {
        val transactions = listOf(
            transaction("old", TransactionType.EXPENSE, 500, "EUR", "2025-12-31T10:00:00Z"),
            transaction("year", TransactionType.EXPENSE, 700, "EUR", "2026-01-15T10:00:00Z"),
            transaction("month", TransactionType.EXPENSE, 300, "EUR", "2026-08-20T10:00:00Z"),
            transaction("usd", TransactionType.EXPENSE, 400, "USD", "2026-08-21T10:00:00Z"),
            transaction("income", TransactionType.INCOME, 9_000, "EUR", "2026-08-22T10:00:00Z"),
            transaction("other", TransactionType.EXPENSE, 8_000, "EUR", "2026-08-23T10:00:00Z", "other"),
        )

        val summary = calculateCategorySpending(
            transactions,
            "food",
            LocalDate.of(2026, 8, 31),
            ZoneOffset.UTC,
        )

        assertEquals(
            listOf(
                CategoryCurrencySpending("EUR", 300, 1_000),
                CategoryCurrencySpending("USD", 400, 400),
            ),
            summary.totals,
        )
        assertEquals(listOf("usd", "month", "year", "old"), summary.expenses.map { it.id })
    }

    @Test
    /**
     * Verifica que un acumulado imposible falle explícitamente en lugar de mostrar un total falso.
     */
    fun `fails on category total overflow`() {
        val transactions = listOf(
            transaction("maximum", TransactionType.EXPENSE, Long.MAX_VALUE, "EUR", "2026-08-20T10:00:00Z"),
            transaction("extra", TransactionType.EXPENSE, 1, "EUR", "2026-08-21T10:00:00Z"),
        )

        assertThrows(ArithmeticException::class.java) {
            calculateCategorySpending(
                transactions,
                "food",
                LocalDate.of(2026, 8, 31),
                ZoneOffset.UTC,
            )
        }
    }

    /**
     * Crea un movimiento ficticio estable para escenarios de agregación.
     */
    private fun transaction(
        id: String,
        type: TransactionType,
        amountMinor: Long,
        currencyCode: String,
        instant: String,
        categoryId: String = "food",
    ) = Transaction(
        id = id,
        type = type,
        money = Money(amountMinor, currencyCode),
        occurredAt = Instant.parse(instant),
        description = null,
        categoryId = categoryId,
    )
}
