package dev.okami.luma.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringExpenseDao {
    /**
     * Observa reglas recurrentes, mostrando primero las activas.
     *
     * Salida: flujo estable ordenado por actividad, día e identificador.
     */
    @Query("SELECT * FROM recurring_expenses ORDER BY active DESC, dayOfMonth ASC, id ASC")
    fun observeAll(): Flow<List<RecurringExpenseEntity>>

    /**
     * Recupera reglas activas para generar ocurrencias vencidas.
     *
     * Salida: instantánea de reglas activas.
     */
    @Query("SELECT * FROM recurring_expenses WHERE active = 1 ORDER BY id ASC")
    suspend fun getActive(): List<RecurringExpenseEntity>

    /**
     * Inserta una regla recurrente nueva.
     *
     * Entrada: entidad validada.
     * Efectos: persiste una fila y aborta ante ID duplicado.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: RecurringExpenseEntity)

    /**
     * Reemplaza los datos editables de una regla existente.
     *
     * Entrada: entidad actualizada.
     * Salida: número de filas modificadas.
     */
    @Update
    suspend fun update(rule: RecurringExpenseEntity): Int

    /**
     * Desactiva una regla sin eliminar sus transacciones históricas.
     *
     * Entrada: identificador de la regla.
     * Salida: número de filas modificadas.
     */
    @Query("UPDATE recurring_expenses SET active = 0 WHERE id = :id")
    suspend fun deactivate(id: String): Int

    /**
     * Limpia una categoría eliminada en todas las reglas.
     *
     * Entrada: identificador de categoría.
     * Salida: número de reglas afectadas.
     */
    @Query("UPDATE recurring_expenses SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: String): Int

    /**
     * Busca una regla por ID para distinguir creación, reactivación y desactivación.
     *
     * Entrada: identificador estable de regla.
     * Salida: entidad encontrada o `null`.
     */
    @Query("SELECT * FROM recurring_expenses WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): RecurringExpenseEntity?
}
