package dev.okami.luma.domain.repository

import dev.okami.luma.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {
    /**
     * Expone el flujo local de transacciones para que la UI reaccione a cambios.
     *
     * Entrada: ninguna.
     * Salida: flujo que emite la lista ordenada por fecha.
     */
    fun observeTransactions(): Flow<List<Transaction>>

    /**
     * Persiste una transacción previamente validada.
     *
     * Entrada: transacción de dominio completa.
     * Efectos: inserta el registro en el almacenamiento local.
     */
    suspend fun add(transaction: Transaction)

    /**
     * Reemplaza una transacción existente conservando su identidad.
     *
     * Entrada: transacción de dominio con un identificador existente.
     * Efectos: actualiza el registro local o informa si no existe.
     */
    suspend fun update(transaction: Transaction)

    /**
     * Elimina una transacción concreta del almacenamiento local.
     *
     * Entrada: identificador estable de la transacción.
     * Efectos: borra el registro o informa si no existe.
     */
    suspend fun delete(id: String)
}
