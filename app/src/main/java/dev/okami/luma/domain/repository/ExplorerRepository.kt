package dev.okami.luma.domain.repository

import androidx.paging.PagingSource
import dev.okami.luma.domain.model.AggregateRow
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionFilter
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

data class MovementWithBalance(val transaction: Transaction, val runningBalance: Long)
data class SavedFilter(val name: String, val filter: TransactionFilter)

/** Consultas del explorador sin entidades Room ni SQL/JSON en los consumidores. */
interface ExplorerRepository {
    /** Crea una fuente paginada validada; entradas criterio/zona; los errores de datos pasan a LoadState.Error. */
    fun pages(filter: TransactionFilter, zone: ZoneId): PagingSource<Int, MovementWithBalance>
    /** Observa agregados exactos; entradas criterio/zona; salida resumida sin cargar todo el histórico. */
    fun aggregates(filter: TransactionFilter, zone: ZoneId): Flow<List<AggregateRow>>
    /** Observa códigos monetarios locales; salida catálogo ordenado. */
    fun currencies(): Flow<List<String>>
    /** Observa filtros decodificados y validados; salida criterios con nombre. */
    fun saved(): Flow<List<SavedFilter>>
    /** Guarda un criterio validado; entrada nombre/criterio; efecto escritura local cifrada. */
    suspend fun save(filter: SavedFilter)
    /** Elimina un filtro por nombre; efecto no modifica movimientos. */
    suspend fun delete(name: String)
}
