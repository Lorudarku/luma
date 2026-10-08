package dev.okami.luma.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    /**
     * Observa las categorías ordenadas por nombre normalizado.
     *
     * Salida: flujo reactivo de todas las categorías disponibles.
     */
    @Query("SELECT * FROM categories ORDER BY normalizedName ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    /**
     * Inserta una categoría nueva y rechaza nombres normalizados duplicados.
     *
     * Entrada: entidad validada.
     * Efectos: añade una fila o propaga el conflicto de unicidad.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity)

    /**
     * Actualiza una categoría existente.
     *
     * Entrada: entidad con identificador persistido.
     * Salida: número de filas modificadas.
     */
    @Update
    suspend fun update(category: CategoryEntity): Int

    /**
     * Elimina una categoría después de que sus referencias hayan sido limpiadas.
     *
     * Entrada: entidad seleccionada.
     * Salida: número de filas eliminadas.
     */
    @Delete
    suspend fun delete(category: CategoryEntity): Int

    /**
     * Busca una categoría concreta para coordinar edición o eliminación.
     *
     * Entrada: identificador estable.
     * Salida: entidad encontrada o `null`.
     */
    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CategoryEntity?
}
