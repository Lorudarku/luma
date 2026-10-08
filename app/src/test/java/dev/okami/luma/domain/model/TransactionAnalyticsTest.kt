package dev.okami.luma.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransactionAnalyticsTest {
    /**
     * Comprueba saldos por movimiento con orden determinista y monedas separadas.
     *
     * Verificación: cada ID recibe el saldo inmediatamente posterior dentro de su moneda.
     */
    @Test
    fun `calculates chronological running balances per currency`() {
        val transactions = listOf(
            transaction("b", TransactionType.EXPENSE, 300, "EUR", "2026-01-02T00:00:00Z"),
            transaction("usd", TransactionType.INCOME, 700, "USD", "2026-01-01T00:00:00Z"),
            transaction("a", TransactionType.INCOME, 1_000, "EUR", "2026-01-01T00:00:00Z"),
        )

        assertEquals(
            mapOf("a" to 1_000L, "usd" to 700L, "b" to 700L),
            calculateRunningBalances(transactions),
        )
    }

    /**
     * Comprueba que el saldo acumulado informa overflow en lugar de envolverse.
     *
     * Verificación: dos ingresos incompatibles con Long lanzan ArithmeticException.
     */
    @Test
    fun `running balances fail explicitly on overflow`() {
        val transactions = listOf(
            transaction("a", TransactionType.INCOME, Long.MAX_VALUE, "EUR", "2026-01-01T00:00:00Z"),
            transaction("b", TransactionType.INCOME, 1, "EUR", "2026-01-02T00:00:00Z"),
        )

        assertThrows(ArithmeticException::class.java) {
            calculateRunningBalances(transactions)
        }
    }

    /**
     * Comprueba que una serie mensual parte del saldo histórico anterior al mes.
     *
     * Verificación: el primer punto incluye enero y aplica después el gasto de febrero.
     */
    @Test
    fun `monthly series carries historical balance and filters currency`() {
        val transactions = listOf(
            transaction("income", TransactionType.INCOME, 1_000, "EUR", "2026-01-15T00:00:00Z"),
            transaction("expense", TransactionType.EXPENSE, 250, "EUR", "2026-02-02T00:00:00Z"),
            transaction("usd", TransactionType.EXPENSE, 900, "USD", "2026-02-02T00:00:00Z"),
        )

        val series = calculateBalanceSeries(
            transactions,
            "EUR",
            StatisticsPeriod.MONTH,
            LocalDate.of(2026, 2, 10),
            ZoneOffset.UTC,
        )

        assertEquals(LocalDate.of(2026, 2, 1), series.rangeStart)
        assertEquals(1_000L, series.points.first().balanceMinor)
        assertEquals(750L, series.points[1].balanceMinor)
        assertEquals(750L, series.points.last().balanceMinor)
    }

    /**
     * Crea una transacción ficticia para escenarios de analítica.
     *
     * Entradas: ID, tipo, importe, moneda e instante ISO.
     * Salida: modelo válido sin descripción.
     */
    private fun transaction(
        id: String,
        type: TransactionType,
        amount: Long,
        currency: String,
        instant: String,
    ) = Transaction(id, type, Money(amount, currency), Instant.parse(instant), null)
}
