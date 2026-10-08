package dev.okami.luma.data

import dev.okami.luma.data.local.TransactionDao
import dev.okami.luma.data.mapper.toDomain
import dev.okami.luma.data.mapper.toEntity
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransactionRepositoryImpl(
    private val transactionDao: TransactionDao,
) : TransactionRepository {
    /**
     * Expone entidades observadas como modelos de dominio sin filtrar tipos de Room hacia UI.
     *
     * Salida: [Flow] de transacciones ordenadas por el DAO.
     * Efectos: mantiene una observación activa mientras el consumidor esté suscrito.
     */
    override fun observeTransactions(): Flow<List<Transaction>> =
        transactionDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /**
     * Convierte y persiste una transacción nueva.
     *
     * Entrada: transacción de dominio validada.
     * Efectos: inserta exactamente una fila; propaga errores de integridad de Room.
     */
    override suspend fun add(transaction: Transaction) {
        // Convertir antes de escribir mantiene aislado el modelo de persistencia.
        transactionDao.insert(transaction.toEntity())
    }

    /**
     * Actualiza una transacción existente y verifica que la fila siga presente.
     *
     * Entrada: transacción con identificador existente.
     * Efectos: actualiza una fila o lanza si el usuario intentó editar un registro ausente.
     */
    override suspend fun update(transaction: Transaction) {
        // El recuento de Room evita informar éxito cuando no se modificó ninguna fila.
        check(transactionDao.updatePreservingOccurrence(transaction.toEntity()) == 1) {
            "Transaction '${transaction.id}' does not exist."
        }
    }

    /**
     * Elimina una transacción y verifica que la operación haya afectado a una fila.
     *
     * Entrada: identificador de dominio.
     * Efectos: elimina localmente una fila o lanza si ya no existe.
     */
    override suspend fun delete(id: String) {
        // La comprobación protege la confirmación de UI frente a estados desactualizados.
        check(transactionDao.deletePreservingOccurrence(id) == 1) {
            "Transaction '$id' does not exist."
        }
    }
}
