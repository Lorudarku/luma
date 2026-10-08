package dev.okami.luma.ui.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import dev.okami.luma.domain.model.*
import dev.okami.luma.domain.repository.CategoryRepository
import dev.okami.luma.domain.repository.ExplorerRepository
import dev.okami.luma.domain.repository.SavedFilter
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ExplorerSummary(val rows: List<AggregateRow> = emptyList(), val dashboards: List<ExplorerDashboard> = emptyList(), val loading: Boolean = true, val error: Boolean = false)

/** Fuente compartida de filtros, páginas y agregados; no conserva movimientos del histórico completo. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplorerViewModel(private val repository: ExplorerRepository, categories: CategoryRepository,
    private val clock: Clock = Clock.systemDefaultZone()) : ViewModel() {
    private val mutableFilter = MutableStateFlow(TransactionFilter(start = LocalDate.now(clock).withDayOfMonth(1), end = LocalDate.now(clock), period = StatisticsPeriod.MONTH))
    val filter = mutableFilter.asStateFlow()
    val today: LocalDate get() = LocalDate.now(clock)
    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()
    val catalog = categories.observeCategories().recoverReadError().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val currencies = repository.currencies().recoverReadError().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val saved = repository.saved().recoverReadError().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Convierte fallos de lectura en un estado visible; conserva la cancelación de la sesión. */
    private fun <T> Flow<List<T>>.recoverReadError(): Flow<List<T>> = catch { cause ->
        if (cause is CancellationException) throw cause
        mutableError.value = true
        emit(emptyList())
    }

    val pages = filter.flatMapLatest { selected ->
        Pager(PagingConfig(pageSize = 40, initialLoadSize = 40, enablePlaceholders = false)) {
            repository.pages(selected, clock.zone)
        }.flow
    }.cachedIn(viewModelScope)

    val summary = filter.flatMapLatest { selected ->
        combine(
            repository.aggregates(selected, clock.zone),
            repository.aggregates(TransactionFilter(currencies = selected.currencies, end = today), clock.zone),
            repository.aggregates(previousFilter(selected, today), clock.zone),
        ) { rows, history, previous ->
            val dashboards = (rows + history).map { it.currencyCode }.distinct().sorted().map { currency ->
                calculateExplorerDashboard(rows, history, previous, selected, currency, today)
            }
            ExplorerSummary(rows, dashboards, loading = false)
        }
            .onStart { emit(ExplorerSummary()) }
            .catch { cause ->
                if (cause is CancellationException) throw cause
                emit(ExplorerSummary(loading = false, error = true))
            }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExplorerSummary())

    /** Aplica una selección validada a todas las pantallas; nunca publica fechas futuras. */
    fun apply(value: TransactionFilter) {
        require(value.end == null || value.end <= today)
        require(value.start == null || value.start <= today)
        mutableFilter.value = value
    }

    /** Valida fechas e importes del borrador; devuelve false sin alterar la selección ante error. */
    fun applyDraft(draft: TransactionFilter, start: String, end: String, min: String, max: String): Boolean = try {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val currency = draft.currencies.singleOrNull()
        apply(draft.copy(start = start.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it, formatter) },
            end = end.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it, formatter) },
            minimum = parseLimit(min, currency), maximum = parseLimit(max, currency)))
        true
    } catch (_: Exception) { false }

    /** Convierte un límite no negativo sin redondear ni aceptar separadores de miles ambiguos. */
    private fun parseLimit(text: String, currency: String?): Long? {
        if (text.isBlank()) return null
        require(text.trim().matches(Regex("[0-9]+([.,][0-9]+)?")))
        val digits = currencyScale(requireNotNull(currency))
        val decimal = java.math.BigDecimal(text.trim().replace(',', '.'))
        require(digits in 0..9 && decimal.scale() <= digits)
        return decimal.movePointRight(digits).longValueExact().also { require(it >= 0) }
    }

    /** Retira criterios y muestra todo el histórico hasta hoy. */
    fun clear() { mutableFilter.value = TransactionFilter(end = today) }

    /** Guarda en SQLCipher un filtro con nombre visible; errores recuperables sin logs sensibles. */
    fun save(name: String) {
        if (name.trim().length !in 1..40) { mutableError.value = true; return }
        val selected = SavedFilter(name.trim(), filter.value)
        viewModelScope.launch {
            try { repository.save(selected) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableError.value = true }
        }
    }

    /** Restaura un filtro cifrado después de validarlo y limita fechas a hoy. */
    fun load(saved: SavedFilter) {
        try { apply(saved.filter) }
        catch (_: Exception) { mutableError.value = true }
    }

    /** Elimina una preferencia elegida sin afectar a movimientos. */
    fun delete(name: String) {
        viewModelScope.launch {
            try { repository.delete(name) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableError.value = true }
        }
    }

    /** Descarta un error de preferencias después de mostrarlo. */
    fun dismissError() { mutableError.value = false }

    /** Acota el contexto a una categoría; los demás criterios continúan activos. */
    fun category(id: String?) { apply(filter.value.copy(categoryIds = setOfNotNull(id), uncategorized = id == null, types = setOf(TransactionType.EXPENSE))) }

    /** Desplaza un rango completo manteniendo su longitud; rechaza todo avance al futuro. */
    fun shift(direction: Int) {
        val f = filter.value
        val next = shiftedRange(f, direction, today)
        if (next.first <= today) apply(f.copy(start = next.first, end = next.second.coerceAtMost(today)))
    }
}
