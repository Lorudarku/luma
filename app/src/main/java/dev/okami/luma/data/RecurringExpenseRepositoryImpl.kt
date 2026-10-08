package dev.okami.luma.data

import androidx.room.withTransaction
import dev.okami.luma.data.local.LumaDatabase
import dev.okami.luma.data.local.RecurringExpenseDao
import dev.okami.luma.data.local.TransactionDao
import dev.okami.luma.data.local.TransactionEntity
import dev.okami.luma.data.local.RecurringOccurrenceEntity
import dev.okami.luma.data.local.recurrenceMonth
import dev.okami.luma.data.mapper.toDomain
import dev.okami.luma.data.mapper.toEntity
import dev.okami.luma.domain.model.RecurringExpense
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import dev.okami.luma.domain.model.calculateDueOccurrenceDates
import dev.okami.luma.domain.repository.RecurringExpenseRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecurringExpenseRepositoryImpl(
    private val database: LumaDatabase,
    private val recurringExpenseDao: RecurringExpenseDao,
    private val transactionDao: TransactionDao,
) : RecurringExpenseRepository {
    /** Guarda configuración y cuotas atómicamente; entradas: regla, fecha límite y zona; no reescribe histórico. */
    override suspend fun saveAndGenerate(rule: RecurringExpense, throughDate: LocalDate, zoneId: ZoneId) {
        database.withTransaction {
            // 1. El ID del borrador se conserva también en los reintentos.
            if (recurringExpenseDao.findById(rule.id) == null) recurringExpenseDao.insert(rule.toEntity())
            else check(recurringExpenseDao.update(rule.toEntity()) == 1)
            // 2. Un fallo en cualquier cuota revierte también el guardado de la regla.
            generateDueInsideTransaction(throughDate, zoneId)
        }
    }
    /**
     * Convierte las reglas observadas a modelos de dominio.
     *
     * Salida: flujo local ordenado de reglas recurrentes.
     */
    override fun observeRules(): Flow<List<RecurringExpense>> =
        recurringExpenseDao.observeAll().map { rules -> rules.map { it.toDomain() } }

    /**
     * Persiste una regla recurrente validada.
     *
     * Entrada: regla nueva.
     * Efectos: inserta su configuración sin crear todavía ocurrencias.
     */
    override suspend fun add(rule: RecurringExpense) {
        recurringExpenseDao.insert(rule.toEntity())
    }

    /**
     * Actualiza una regla y detecta reglas ya eliminadas.
     *
     * Entrada: regla editada.
     * Efectos: modifica exactamente una fila.
     */
    override suspend fun update(rule: RecurringExpense) {
        check(recurringExpenseDao.update(rule.toEntity()) == 1) {
            "Recurring expense '${rule.id}' does not exist."
        }
    }

    /**
     * Desactiva una regla conservando sus movimientos generados.
     *
     * Entrada: identificador de regla.
     * Efectos: impide generaciones posteriores.
     */
    override suspend fun deactivate(id: String) {
        check(recurringExpenseDao.deactivate(id) == 1) {
            "Recurring expense '$id' does not exist."
        }
    }

    /**
     * Inserta todas las ocurrencias vencidas de forma atómica e idempotente.
     *
     * Entradas: fecha límite y zona horaria de persistencia.
     * Efectos: añade únicamente gastos ausentes; nunca modifica el histórico existente.
     */
    override suspend fun generateDue(throughDate: LocalDate, zoneId: ZoneId) {
        database.withTransaction {
            // 1. El helper comparte la misma transacción con altas y reactivaciones.
            generateDueInsideTransaction(throughDate, zoneId)
        }
    }

    /**
     * Cambia la periodicidad de un gasto preservando su fila original y su histórico.
     *
     * Entradas: transacción seleccionada, estado deseado, hoy y zona local.
     * Efectos: crea/asocia/reactiva o desactiva la regla y materializa vencimientos atómicamente.
     */
    override suspend fun setMonthlyRecurrence(
        transaction: Transaction,
        enabled: Boolean,
        throughDate: LocalDate,
        zoneId: ZoneId,
    ) {
        require(transaction.type == TransactionType.EXPENSE) {
            "Only expense transactions can be monthly."
        }
        database.withTransaction {
            // 1. El ID ya asociado prevalece; para una fila manual se reutiliza su ID estable.
            val occurrenceDate = transaction.recurrenceDate
                ?: transaction.occurredAt.atZone(zoneId).toLocalDate()
            val ruleId = transaction.recurringExpenseId ?: transaction.id
            val storedRule = recurringExpenseDao.findById(ruleId)

            if (!enabled) {
                // 2. Desactivar nunca elimina reglas ni ocurrencias históricas.
                check(storedRule != null && recurringExpenseDao.deactivate(ruleId) == 1) {
                    "Recurring expense '$ruleId' does not exist."
                }
                return@withTransaction
            }

            // 3. Una regla ausente adopta los datos de la fila; una inactiva conserva su edición.
            if (storedRule == null) {
                recurringExpenseDao.insert(
                    RecurringExpense(
                        id = ruleId,
                        money = transaction.money,
                        dayOfMonth = occurrenceDate.dayOfMonth,
                        startDate = occurrenceDate,
                        description = transaction.description,
                        categoryId = transaction.categoryId,
                        active = true,
                    ).toEntity()
                )
            } else if (!storedRule.active) {
                check(recurringExpenseDao.update(storedRule.copy(active = true)) == 1)
            }

            // 4. La fila original ocupa la clave regla-fecha e impide duplicar la primera cuota.
            val linkedEntity = transaction.toEntity().copy(
                recurringExpenseId = ruleId,
                recurrenceDateEpochDay = occurrenceDate.toEpochDay(),
            )
            val existing = transactionDao.findById(transaction.id)
            if (existing == null) {
                transactionDao.insert(linkedEntity)
            } else {
                check(transactionDao.updatePreservingOccurrence(linkedEntity) == 1)
            }
            transactionDao.rememberOccurrence(linkedEntity)

            // 5. Reactivar rellena también los meses vencidos durante la pausa, nunca el futuro.
            generateDueInsideTransaction(throughDate, zoneId)
        }
    }

    /**
     * Materializa las fechas vencidas suponiendo que el llamador ya abrió una transacción Room.
     *
     * Entradas: fecha máxima inclusiva y zona usada para construir instantes.
     * Efectos: registra cada mes como máximo una vez; una omisión y el histórico sobreviven a ediciones.
     */
    private suspend fun generateDueInsideTransaction(throughDate: LocalDate, zoneId: ZoneId) {
        recurringExpenseDao.getActive().map { it.toDomain() }.forEach { rule ->
            calculateDueOccurrenceDates(rule, throughDate).forEach occurrence@ { occurrenceDate ->
                val claimed = transactionDao.insertOccurrence(
                    RecurringOccurrenceEntity(rule.id, recurrenceMonth(occurrenceDate))
                )
                if (claimed == -1L) return@occurrence
                // La inserción aborta ante conflictos inesperados: no perder una cuota silenciosamente.
                transactionDao.insert(
                    TransactionEntity(
                        id = recurringTransactionId(rule.id, occurrenceDate),
                        type = TransactionType.EXPENSE.name,
                        amountMinor = rule.money.amountMinor,
                        currencyCode = rule.money.currencyCode,
                        occurredAtEpochMillis = occurrenceDate.atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                        description = rule.description,
                        categoryId = rule.categoryId,
                        recurringExpenseId = rule.id,
                        recurrenceDateEpochDay = occurrenceDate.toEpochDay(),
                    )
                )
            }
        }
    }
}

/**
 * Construye el ID estable de una ocurrencia mensual.
 *
 * Entradas: ID de regla y fecha efectiva.
 * Salida: identificador determinista seguro para reintentos.
 */
private fun recurringTransactionId(ruleId: String, date: LocalDate): String =
    "recurring:$ruleId:${date.toEpochDay()}"
