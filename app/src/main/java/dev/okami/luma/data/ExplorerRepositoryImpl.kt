package dev.okami.luma.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.okami.luma.data.local.ExplorerDao
import dev.okami.luma.data.local.ExplorerRow
import dev.okami.luma.data.local.SavedFilterEntity
import dev.okami.luma.data.mapper.toDomain
import dev.okami.luma.data.mapper.toEntity
import dev.okami.luma.domain.model.TransactionFilter
import dev.okami.luma.domain.model.currencyScale
import dev.okami.luma.domain.repository.*
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map

class ExplorerRepositoryImpl(private val dao: ExplorerDao) : ExplorerRepository {
    /** Crea una página y valida entidades en la capa de datos; entradas criterio/zona; conserva invalidación Room. */
    override fun pages(filter: TransactionFilter, zone: ZoneId): PagingSource<Int, MovementWithBalance> =
        ValidatedMovementSource(dao.pages(FilterQuery.build(filter, zone, false)))

    /** Consulta agregados con predicado compartido; entradas criterio/zona; salida flujo exacto. */
    override fun aggregates(filter: TransactionFilter, zone: ZoneId) = dao.aggregates(FilterQuery.build(filter, zone, true))

    /** Valida el catálogo monetario antes de exponerlo; salida códigos soportados o error de lectura. */
    override fun currencies() = dao.currencies().map { codes -> codes.onEach { currencyScale(it) } }

    /** Decodifica filtros cifrados en datos; salida criterios validados sin filtrar JSON hacia la UI. */
    override fun saved() = dao.saved().map { entries -> entries.map { SavedFilter(it.name, FilterCodec.decode(it.specification)) } }

    /** Persiste nombre/criterio validado; entradas modelo de filtro; efecto reemplaza una preferencia local. */
    override suspend fun save(filter: SavedFilter) {
        require(filter.name == filter.name.trim() && filter.name.length in 1..40)
        dao.save(SavedFilterEntity(filter.name, FilterCodec.encode(filter.filter)))
    }

    /** Elimina solo la preferencia nombrada; entrada nombre; efecto escritura local. */
    override suspend fun delete(name: String) { dao.delete(name) }
}

private class ValidatedMovementSource(private val source: PagingSource<Int, ExplorerRow>) : PagingSource<Int, MovementWithBalance>() {
    init {
        source.registerInvalidatedCallback { invalidate() }
        registerInvalidatedCallback { source.invalidate() }
    }

    /** Carga y valida la página; entrada posición/tamaño; salida error recuperable ante entidades inválidas. */
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MovementWithBalance> = try {
        when (val result = source.load(params)) {
            is LoadResult.Page -> LoadResult.Page(result.data.map { MovementWithBalance(it.transaction.toDomain(), it.runningBalance) },
                result.prevKey, result.nextKey, result.itemsBefore, result.itemsAfter)
            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
        }
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) { LoadResult.Error(error) }

    /** Conserva el cálculo de posición del origen Room; entrada estado de páginas; salida clave de refresco. */
    override fun getRefreshKey(state: PagingState<Int, MovementWithBalance>): Int? = source.getRefreshKey(
        PagingState(state.pages.map { page -> LoadResult.Page(page.data.map { ExplorerRow(it.transaction.toEntity(), it.runningBalance) },
            page.prevKey, page.nextKey, page.itemsBefore, page.itemsAfter) }, state.anchorPosition, state.config, 0)
    )
}
