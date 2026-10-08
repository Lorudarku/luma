package dev.okami.luma.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    /** Inserta un mes tratado; devuelve -1 si ya estaba contabilizado u omitido, sin sobrescribirlo. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrence(occurrence: RecurringOccurrenceEntity): Long

    /**
     * Actualiza una fila preservando la mensualidad anterior aunque cambie tipo, fecha o vínculo.
     * Entrada: entidad editada. Salida: filas actualizadas; ambas escrituras son atómicas.
     */
    @Transaction
    suspend fun updatePreservingOccurrence(transaction: TransactionEntity): Int {
        findById(transaction.id)?.let { rememberOccurrence(it) }
        return update(transaction)
    }

    /** Elimina por ID y registra primero su mensualidad; salida: filas borradas dentro de una transacción. */
    @Transaction
    suspend fun deletePreservingOccurrence(id: String): Int {
        findById(id)?.let { rememberOccurrence(it) }
        return deleteById(id)
    }

    /** Registra la cuota asociada a una entidad, si existe; efecto: impide su regeneración futura. */
    suspend fun rememberOccurrence(transaction: TransactionEntity) {
        val ruleId = transaction.recurringExpenseId ?: return
        val date = transaction.recurrenceDateEpochDay ?: return
        insertOccurrence(RecurringOccurrenceEntity(ruleId, recurrenceMonth(LocalDate.ofEpochDay(date))))
    }
    /**
     * Observa todas las entidades ordenadas de la más reciente a la más antigua.
     *
     * Salida: un [Flow] que emite cada cambio de la tabla y usa el identificador como desempate.
     * Efectos: Room mantiene la observación; la consulta no debe ejecutarse en el hilo principal.
     */
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /**
     * Inserta una entidad nueva y aborta ante un identificador duplicado.
     *
     * Entrada: entidad ya validada por el dominio y convertida por el repositorio.
     * Efectos: escribe una fila mediante Room y puede lanzar una excepción de integridad.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: TransactionEntity)

    /**
     * Inserta una ocurrencia recurrente sin duplicarla si ya existe.
     *
     * Entrada: entidad con regla y fecha de ocurrencia.
     * Salida: rowId o `-1` cuando el índice único detecta una repetición.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringConflict(transaction: TransactionEntity): Long

    /**
     * Actualiza una entidad existente.
     *
     * Entrada: entidad con identificador persistido.
     * Salida: número de filas afectadas, usado para detectar registros inexistentes.
     */
    @Update
    suspend fun update(transaction: TransactionEntity): Int

    /**
     * Elimina una entidad por su identificador.
     *
     * Entrada: identificador de la transacción que se quiere eliminar.
     * Salida: número de filas eliminadas para distinguir éxito de ausencia.
     */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /**
     * Desvincula una categoría antes de eliminarla.
     *
     * Entrada: identificador de categoría.
     * Salida: número de movimientos que pasan a quedar sin categoría.
     */
    @Query("UPDATE transactions SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: String): Int

    /**
     * Busca la fila actual antes de asociarla a una regla mensual.
     *
     * Entrada: identificador estable del movimiento.
     * Salida: entidad persistida o `null` cuando todavía es un alta nueva.
     */
    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TransactionEntity?
}
