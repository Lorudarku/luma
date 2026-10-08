package dev.okami.luma.domain.repository

import dev.okami.luma.domain.model.RecurringExpense
import dev.okami.luma.domain.model.Transaction
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

interface RecurringExpenseRepository {
    /**
     * Guarda una regla con identidad estable y sus vencimientos en una única transacción.
     * Entradas: regla, límite y zona. Efectos: todo se confirma o todo se revierte; admite reintentos.
     */
    suspend fun saveAndGenerate(rule: RecurringExpense, throughDate: LocalDate, zoneId: ZoneId)
    /**
     * Observa todas las reglas mensuales locales.
     *
     * Salida: flujo con reglas activas e inactivas.
     */
    fun observeRules(): Flow<List<RecurringExpense>>

    /**
     * Añade una regla mensual nueva.
     *
     * Entrada: regla validada.
     * Efectos: persiste la configuración, pero no crea fechas futuras.
     */
    suspend fun add(rule: RecurringExpense)

    /**
     * Actualiza una regla sin reescribir ocurrencias históricas.
     *
     * Entrada: regla persistida con sus nuevos campos.
     */
    suspend fun update(rule: RecurringExpense)

    /**
     * Desactiva futuras generaciones manteniendo el histórico.
     *
     * Entrada: identificador de regla.
     */
    suspend fun deactivate(id: String)

    /**
     * Materializa de forma idempotente todas las ocurrencias vencidas.
     *
     * Entradas: fecha máxima inclusiva y zona usada para el instante persistido.
     * Efectos: inserta gastos ausentes dentro de una transacción Room.
     */
    suspend fun generateDue(throughDate: LocalDate, zoneId: ZoneId)

    /**
     * Activa o desactiva la regla mensual asociada a una transacción concreta.
     *
     * Entradas: movimiento de gasto, estado deseado, fecha límite y zona local.
     * Efectos: asocia la fila original, crea/reactiva o desactiva la regla y genera vencimientos
     * dentro de una única transacción local.
     */
    suspend fun setMonthlyRecurrence(
        transaction: Transaction,
        enabled: Boolean,
        throughDate: LocalDate,
        zoneId: ZoneId,
    )
}
