package dev.okami.luma.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransactionTotalsTest {
    @Test
    /**
     * Comprueba que los totales se agrupan por moneda y se ordenan de forma determinista.
     *
     * Preparación: combina ingresos y gastos ficticios en EUR y USD.
     * Verificación: contrasta ingresos, gastos y saldo calculados para cada moneda.
     */
    fun `calculates deterministic totals grouped by currency`() {
        val transactions = listOf(
            transaction("1", TransactionType.INCOME, 20_000, "EUR"),
            transaction("2", TransactionType.EXPENSE, 5_050, "EUR"),
            transaction("3", TransactionType.INCOME, 1_000, "USD"),
        )

        assertEquals(
            listOf(
                CurrencyTotals("EUR", incomeMinor = 20_000, expenseMinor = 5_050, balanceMinor = 14_950),
                CurrencyTotals("USD", incomeMinor = 1_000, expenseMinor = 0, balanceMinor = 1_000),
            ),
            calculateTransactionTotals(transactions),
        )
    }

    @Test
    /**
     * Comprueba que un desbordamiento monetario no se oculta durante la suma.
     *
     * Preparación: usa el valor máximo de Long y añade una unidad en la misma moneda.
     * Verificación: espera ArithmeticException en vez de aceptar un saldo corrupto.
     */
    fun `fails explicitly when totals overflow`() {
        val transactions = listOf(
            transaction("1", TransactionType.INCOME, Long.MAX_VALUE, "EUR"),
            transaction("2", TransactionType.INCOME, 1, "EUR"),
        )

        assertThrows(ArithmeticException::class.java) {
            calculateTransactionTotals(transactions)
        }
    }

    /**
     * Crea una transacción mínima para reutilizarla en los escenarios de totales.
     *
     * Entrada: identidad, tipo, importe menor y código de moneda ficticios.
     * Salida: instancia de dominio con una fecha común para hacer el test determinista.
     */
    private fun transaction(
        id: String,
        type: TransactionType,
        amountMinor: Long,
        currencyCode: String,
    ) = Transaction(
        id = id,
        type = type,
        money = Money(amountMinor, currencyCode),
        occurredAt = Instant.EPOCH,
        description = null,
    )
}
