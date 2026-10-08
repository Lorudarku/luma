package dev.okami.luma.data

import dev.okami.luma.data.local.TransactionDao
import dev.okami.luma.data.local.TransactionEntity
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionRepositoryImplTest {
    @Test
    /**
     * Comprueba el mapeo bidireccional y la conservación de cantidades grandes en el repositorio.
     *
     * Pasos: crea DAO falso, añade una transacción, lee el Flow y compara dominio y entidad.
     */
    fun `observes mapped domain transactions and persists exact values`() = runTest {
        val dao = FakeTransactionDao()
        val repository = TransactionRepositoryImpl(dao)
        val transaction = transaction(amountMinor = Long.MAX_VALUE)

        repository.add(transaction)

        assertEquals(transaction, repository.observeTransactions().first().single())
        assertEquals(Long.MAX_VALUE, dao.entities.value.single().amountMinor)
    }

    @Test
    /**
     * Comprueba que actualizar o eliminar una fila inexistente no se reporta como éxito.
     *
     * Pasos: usa un DAO vacío, invoca ambas operaciones suspendidas y verifica sus excepciones.
     */
    fun `update and delete require an existing transaction`() = runTest {
        val repository = TransactionRepositoryImpl(FakeTransactionDao())
        val transaction = transaction(amountMinor = 100)

        assertTrue(runCatching { repository.update(transaction) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { repository.delete(transaction.id) }.exceptionOrNull() is IllegalStateException)
    }

    /**
     * Construye una transacción ficticia para las pruebas del contrato de repositorio.
     *
     * Entrada: cantidad exacta en unidades mínimas.
     * Salida: ingreso EUR con fecha y descripción estables.
     */
    private fun transaction(amountMinor: Long) = Transaction(
        id = "transaction-1",
        type = TransactionType.INCOME,
        money = Money(amountMinor, "EUR"),
        occurredAt = Instant.parse("2026-08-30T10:00:00Z"),
        description = "Ingreso ficticio",
    )

    private class FakeTransactionDao : TransactionDao {
        val entities = MutableStateFlow<List<TransactionEntity>>(emptyList())
        val occurrences = mutableSetOf<dev.okami.luma.data.local.RecurringOccurrenceEntity>()

        /** Registra un mes simulado; devuelve -1 ante repetición y 1 ante inserción. */
        override suspend fun insertOccurrence(occurrence: dev.okami.luma.data.local.RecurringOccurrenceEntity): Long =
            if (occurrences.add(occurrence)) 1L else -1L

        /**
         * Devuelve el Flow mutable que representa la tabla simulada.
         *
         * Salida: emisiones actuales de entidades Room.
         */
        override fun observeAll(): Flow<List<TransactionEntity>> = entities

        /**
         * Simula una inserción y rechaza IDs duplicados.
         *
         * Entrada: entidad a añadir.
         * Efectos: publica una lista nueva en el Flow.
         */
        override suspend fun insert(transaction: TransactionEntity) {
            check(entities.value.none { it.id == transaction.id })
            entities.value += transaction
        }

        /**
         * Simula la inserción idempotente de una ocurrencia recurrente.
         *
         * Entrada: entidad candidata.
         * Salida: `-1` si ya existe la misma regla/fecha o un rowId ficticio.
         */
        override suspend fun insertIgnoringConflict(transaction: TransactionEntity): Long {
            val duplicate = entities.value.any {
                it.recurringExpenseId == transaction.recurringExpenseId &&
                    it.recurrenceDateEpochDay == transaction.recurrenceDateEpochDay &&
                    transaction.recurringExpenseId != null
            }
            if (duplicate) return -1
            entities.value = entities.value + transaction
            return 1
        }

        /**
         * Simula una actualización por identificador.
         *
         * Entrada: entidad reemplazante.
         * Salida: 1 si existía la fila o 0 si no existía.
         */
        override suspend fun update(transaction: TransactionEntity): Int {
            if (entities.value.none { it.id == transaction.id }) return 0
            entities.value = entities.value.map {
                if (it.id == transaction.id) transaction else it
            }
            return 1
        }

        /**
         * Simula una eliminación por identificador.
         *
         * Entrada: ID a filtrar.
         * Salida: 1 si se eliminó una fila o 0 si no se encontró.
         */
        override suspend fun deleteById(id: String): Int {
            if (entities.value.none { it.id == id }) return 0
            entities.value = entities.value.filterNot { it.id == id }
            return 1
        }

        /**
         * Simula la desvinculación de una categoría eliminada.
         *
         * Entrada: ID de categoría.
         * Salida: cantidad de entidades afectadas.
         */
        override suspend fun clearCategory(categoryId: String): Int {
            val affected = entities.value.count { it.categoryId == categoryId }
            entities.value = entities.value.map {
                if (it.categoryId == categoryId) it.copy(categoryId = null) else it
            }
            return affected
        }

        /** Busca una entidad simulada por identificador. */
        override suspend fun findById(id: String): TransactionEntity? =
            entities.value.firstOrNull { it.id == id }
    }
}
