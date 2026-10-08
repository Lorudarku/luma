package dev.okami.luma.domain.model

data class CurrencyTotals(
    val currencyCode: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val balanceMinor: Long,
)

/**
 * Calcula ingresos, gastos y balance por código de moneda sin usar coma flotante.
 *
 * Entrada: una lista de transacciones con cantidades positivas en unidades mínimas.
 * Salida: totales ordenados de forma determinista por código de moneda.
 * Errores: propaga [ArithmeticException] si una suma o resta desborda [Long].
 * Pasos: agrupa, acumula por tipo con aritmética comprobada, calcula el balance y ordena.
 */
fun calculateTransactionTotals(transactions: List<Transaction>): List<CurrencyTotals> =
    // 1. Separar monedas evita sumar cantidades incompatibles.
    transactions
        .groupBy { it.money.currencyCode }
        .map { (currencyCode, currencyTransactions) ->
            // 2. Acumular cada tipo por separado conserva la semántica del dominio.
            var income = 0L
            var expense = 0L
            currencyTransactions.forEach { transaction ->
                when (transaction.type) {
                    TransactionType.INCOME -> {
                        income = Math.addExact(income, transaction.money.amountMinor)
                    }

                    TransactionType.EXPENSE -> {
                        expense = Math.addExact(expense, transaction.money.amountMinor)
                    }
                }
            }
            // 3. Math.subtractExact hace visible cualquier overflow del balance.
            CurrencyTotals(
                currencyCode = currencyCode,
                incomeMinor = income,
                expenseMinor = expense,
                balanceMinor = Math.subtractExact(income, expense),
            )
        }
        // 4. Un orden estable facilita UI, tests y exportaciones futuras.
        .sortedBy(CurrencyTotals::currencyCode)
