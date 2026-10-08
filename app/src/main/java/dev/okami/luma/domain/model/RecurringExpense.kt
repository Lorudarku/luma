package dev.okami.luma.domain.model

import java.time.LocalDate
import java.time.YearMonth

data class RecurringExpense(
    val id: String,
    val money: Money,
    val dayOfMonth: Int,
    val startDate: LocalDate,
    val description: String?,
    val categoryId: String?,
    val active: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Recurring expense id must not be blank." }
        require(dayOfMonth in 1..31) { "Recurring day must be between 1 and 31." }
        require(
            description == null ||
                (description.isNotBlank() &&
                    description == description.trim() &&
                    description.length <= MAX_TRANSACTION_DESCRIPTION_LENGTH)
        ) {
            "Description must be trimmed, non-blank, and within the length limit."
        }
        require(categoryId == null || categoryId.isNotBlank()) {
            "Category id must be null or non-blank."
        }
    }
}

/**
 * Calcula todas las fechas mensuales vencidas de una regla sin producir fechas futuras.
 *
 * Entradas: regla recurrente y última fecha permitida.
 * Salida: fechas desde el inicio hasta el límite, ordenadas y ajustadas al último día del mes.
 * Pasos: recorre meses, limita días 29–31 y descarta fechas anteriores al comienzo de la regla.
 */
fun calculateDueOccurrenceDates(
    rule: RecurringExpense,
    throughDate: LocalDate,
): List<LocalDate> {
    if (!rule.active || rule.startDate > throughDate) return emptyList()

    // 1. Recorrer por YearMonth evita sumar días con duraciones variables.
    val dates = mutableListOf<LocalDate>()
    var month = YearMonth.from(rule.startDate)
    val lastMonth = YearMonth.from(throughDate)
    while (month <= lastMonth) {
        // 2. Un día inexistente se desplaza al último día real del mes.
        val occurrence = month.atDay(rule.dayOfMonth.coerceAtMost(month.lengthOfMonth()))
        if (occurrence >= rule.startDate && occurrence <= throughDate) {
            dates += occurrence
        }
        month = month.plusMonths(1)
    }
    return dates
}
