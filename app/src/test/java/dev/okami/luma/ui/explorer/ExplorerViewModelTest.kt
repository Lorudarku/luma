package dev.okami.luma.ui.explorer

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.okami.luma.MainDispatcherRule
import dev.okami.luma.domain.model.*
import dev.okami.luma.domain.repository.*
import java.time.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExplorerViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)

    /** Un borrador inválido conserva la selección; el límite BHD mantiene tres decimales exactos. */
    @Test fun validatesDraftWithoutChangingFilterOnFailure() = runTest {
        val model = ExplorerViewModel(Queries(), Categories(), clock)
        val original = model.filter.value
        assertFalse(model.applyDraft(TransactionFilter(), "31/12/1899", "", "", ""))
        assertEquals(original, model.filter.value)
        assertFalse(model.applyDraft(TransactionFilter(currencies = setOf("BHD")), "", "", "1.2345", ""))
        assertEquals(original, model.filter.value)
        assertTrue(model.applyDraft(TransactionFilter(currencies = setOf("BHD")), "", "", "1.234", ""))
        assertEquals(1234L, model.filter.value.minimum)
    }

    /** Guardar captura el criterio visible al pulsar, aunque cambie antes de ejecutar IO. */
    @Test fun savedFilterUsesSubmissionSnapshot() = runTest {
        val repository = Queries()
        val model = ExplorerViewModel(repository, Categories(), clock)
        val original = model.filter.value
        model.save(" Ficticio "); model.clear(); runCurrent()
        assertEquals(listOf(SavedFilter("Ficticio", original)), repository.savedWrites)
    }

    /** Los errores de catálogo y filtros futuros se convierten en estado visible sin romper el flujo. */
    @Test fun readFailuresBecomeRecoverableUiErrors() = runTest {
        val repository = Queries().apply { failRead = true }
        val model = ExplorerViewModel(repository, Categories(), clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.currencies.collect() }
        runCurrent()
        assertTrue(model.error.value)
        assertTrue(model.currencies.value.isEmpty())
        model.dismissError(); assertFalse(model.error.value)
        model.load(SavedFilter("Futuro", TransactionFilter(end = LocalDate.of(2027, 1, 1))))
        assertTrue(model.error.value)
    }

    private class Queries : ExplorerRepository {
        val savedWrites = mutableListOf<SavedFilter>()
        var failRead = false
        /** Fuente vacía; entradas criterio/zona; permite construir el ViewModel sin SQLite. */
        override fun pages(filter: TransactionFilter, zone: ZoneId) = object : PagingSource<Int, MovementWithBalance>() {
            /** Devuelve una página ficticia vacía. */
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MovementWithBalance> = LoadResult.Page(emptyList(), null, null)
            /** No hay posición que restaurar en una página vacía. */
            override fun getRefreshKey(state: PagingState<Int, MovementWithBalance>): Int? = null
        }
        /** Emite agregados vacíos para aislar los casos de formulario/preferencias. */
        override fun aggregates(filter: TransactionFilter, zone: ZoneId): Flow<List<AggregateRow>> = flowOf(emptyList())
        /** Inyecta un fallo de lectura recuperable. */
        override fun currencies(): Flow<List<String>> = flow { if (failRead) error("injected read"); emit(listOf("EUR")) }
        /** Emite preferencias ficticias vacías. */
        override fun saved(): Flow<List<SavedFilter>> = flowOf(emptyList())
        /** Captura el snapshot persistido para compararlo con el visible al pulsar. */
        override suspend fun save(filter: SavedFilter) { savedWrites += filter }
        /** No modifica movimientos. */
        override suspend fun delete(name: String) = Unit
    }
    private class Categories : CategoryRepository {
        /** Emite categorías ficticias vacías. */
        override fun observeCategories(): Flow<List<Category>> = flowOf(emptyList())
        /** No se usa al consultar filtros. */
        override suspend fun add(category: Category) = Unit
        /** No se usa al consultar filtros. */
        override suspend fun update(category: Category) = Unit
        /** No se usa al consultar filtros. */
        override suspend fun delete(id: String) = Unit
    }
}
