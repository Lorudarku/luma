package dev.okami.luma.data.mapper

import dev.okami.luma.data.local.RecurringExpenseEntity
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.RecurringExpense
import java.time.LocalDate

/**
 * Serializa una regla recurrente manteniendo dinero y fecha exactos.
 *
 * Entrada: regla de dominio validada.
 * Salida: entidad Room basada en unidades menores y epoch day.
 */
fun RecurringExpense.toEntity(): RecurringExpenseEntity = RecurringExpenseEntity(
    id = id,
    amountMinor = money.amountMinor,
    currencyCode = money.currencyCode,
    dayOfMonth = dayOfMonth,
    startDateEpochDay = startDate.toEpochDay(),
    description = description,
    categoryId = categoryId,
    active = active,
)

/**
 * Reconstruye una regla recurrente desde su fila local.
 *
 * Entrada: entidad leída de SQLite.
 * Salida: regla de dominio con tipos exactos.
 */
fun RecurringExpenseEntity.toDomain(): RecurringExpense = RecurringExpense(
    id = id,
    money = Money(amountMinor, currencyCode),
    dayOfMonth = dayOfMonth,
    startDate = LocalDate.ofEpochDay(startDateEpochDay),
    description = description,
    categoryId = categoryId,
    active = active,
)
