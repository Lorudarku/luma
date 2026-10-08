package dev.okami.luma.domain.model

import java.time.LocalDate
import java.time.ZoneId

data class CategoryCurrencySpending(
    val currencyCode: String,
    val monthMinor: Long,
    val yearMinor: Long,
)

data class CategorySpendingSummary(
    val categoryId: String,
    val totals: List<CategoryCurrencySpending>,
    val expenses: List<Transaction>,
)

/**
 * Calcula el gasto mensual, anual e histórico de una categoría sin mezclar monedas.
 *
 * Entradas: movimientos observados, categoría, fecha de referencia y zona local.
 * Salida: totales exactos por divisa y gastos ordenados del más reciente al más antiguo.
 * Errores: propaga [ArithmeticException] si un acumulado excede el rango de [Long].
 */
fun calculateCategorySpending(
    transactions: List<Transaction>,
    categoryId: String,
    referenceDate: LocalDate,
    zoneId: ZoneId,
): CategorySpendingSummary {
    // 1. Solo los gastos asignados forman parte del consumo de la categoría.
    val expenses = transactions
        .filter { it.type == TransactionType.EXPENSE && it.categoryId == categoryId }
        .sortedWith(compareByDescending<Transaction> { it.occurredAt }.thenByDescending { it.id })

    // 2. Cada moneda mantiene acumuladores independientes para mes y año naturales.
    val currencies = expenses.map { it.money.currencyCode }.distinct().sorted()
    val totals = currencies.map { currencyCode ->
        var monthMinor = 0L
        var yearMinor = 0L
        expenses.asSequence()
            .filter { it.money.currencyCode == currencyCode }
            .forEach { transaction ->
                val date = transaction.occurredAt.atZone(zoneId).toLocalDate()
                if (date.year == referenceDate.year) {
                    yearMinor = Math.addExact(yearMinor, transaction.money.amountMinor)
                    if (date.month == referenceDate.month) {
                        monthMinor = Math.addExact(monthMinor, transaction.money.amountMinor)
                    }
                }
            }
        CategoryCurrencySpending(currencyCode, monthMinor, yearMinor)
    }
    return CategorySpendingSummary(categoryId, totals, expenses)
}
