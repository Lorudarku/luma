package dev.okami.luma.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import dev.okami.luma.domain.model.AggregateRow

data class ExplorerRow(@Embedded val transaction: TransactionEntity, val runningBalance: Long)

@Dao
interface ExplorerDao {
    /** Página de movimientos con saldo histórico; la consulta solo contiene parámetros validados. */
    @RawQuery(observedEntities = [TransactionEntity::class, RecurringExpenseEntity::class])
    fun pages(query: SupportSQLiteQuery): PagingSource<Int, ExplorerRow>

    /** Agrega el conjunto completo independientemente de las páginas visibles. */
    @RawQuery(observedEntities = [TransactionEntity::class, RecurringExpenseEntity::class])
    fun aggregates(query: SupportSQLiteQuery): Flow<List<AggregateRow>>

    /** Obtiene el catálogo monetario sin cargar descripciones ni operaciones. */
    @Query("SELECT DISTINCT currencyCode FROM transactions ORDER BY currencyCode")
    fun currencies(): Flow<List<String>>

    /** Observa preferencias cifradas con orden estable. */
    @Query("SELECT * FROM saved_filters ORDER BY name")
    fun saved(): Flow<List<SavedFilterEntity>>

    /** Guarda un filtro validado; un nombre existente se reemplaza por acción explícita del usuario. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(filter: SavedFilterEntity)

    /** Elimina únicamente el filtro identificado por su nombre. */
    @Query("DELETE FROM saved_filters WHERE name = :name")
    suspend fun delete(name: String)
}
