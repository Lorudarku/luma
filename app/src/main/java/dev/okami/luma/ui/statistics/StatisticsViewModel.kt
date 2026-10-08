package dev.okami.luma.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.okami.luma.domain.model.BalanceSeries
import dev.okami.luma.domain.model.StatisticsPeriod
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.calculateBalanceSeries
import dev.okami.luma.domain.model.periodRange
import dev.okami.luma.domain.repository.TransactionRepository
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatisticsUiState(
    val currencies: List<String> = emptyList(),
    val selectedCurrency: String? = null,
    val period: StatisticsPeriod = StatisticsPeriod.MONTH,
    val anchorDate: LocalDate,
    val series: BalanceSeries? = null,
    val canNavigateNext: Boolean = false,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

class StatisticsViewModel(
    private val repository: TransactionRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        StatisticsUiState(anchorDate = LocalDate.now(clock))
    )
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()
    private var transactions: List<Transaction> = emptyList()

    init {
        observeTransactions()
    }

    /**
     * Cambia la granularidad y vuelve al periodo actual.
     *
     * Entrada: semana, mes o año.
     * Efectos: recalcula la serie para hoy.
     */
    fun selectPeriod(period: StatisticsPeriod) {
        _uiState.update { it.copy(period = period, anchorDate = LocalDate.now(clock)) }
        recalculate()
    }

    /**
     * Selecciona una moneda disponible sin realizar conversiones.
     *
     * Entrada: código ISO observado en movimientos.
     */
    fun selectCurrency(currencyCode: String) {
        if (currencyCode !in _uiState.value.currencies) return
        _uiState.update { it.copy(selectedCurrency = currencyCode) }
        recalculate()
    }

    /**
     * Mueve la gráfica al periodo inmediatamente anterior.
     *
     * Efectos: conserva moneda y granularidad.
     */
    fun previousPeriod() {
        _uiState.update { it.copy(anchorDate = shift(it.anchorDate, it.period, -1)) }
        recalculate()
    }

    /**
     * Mueve la gráfica al periodo siguiente si no comienza en el futuro.
     *
     * Efectos: ignora la acción cuando se alcanzó el periodo actual.
     */
    fun nextPeriod() {
        if (!_uiState.value.canNavigateNext) return
        _uiState.update { it.copy(anchorDate = shift(it.anchorDate, it.period, 1)) }
        recalculate()
    }

    /**
     * Descarta un error de cálculo después de mostrarlo.
     *
     * Efectos: modifica solo la bandera visible.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = false) }
    }

    /**
     * Observa movimientos y mantiene el selector de monedas actualizado.
     *
     * Efectos: conserva la moneda elegida cuando aún existe y recalcula la serie.
     */
    private fun observeTransactions() {
        viewModelScope.launch {
            repository.observeTransactions()
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    _uiState.update { it.copy(isLoading = false, error = true) }
                }
                .collect { observed ->
                    transactions = observed
                    val currencies = observed.map { it.money.currencyCode }.distinct().sorted()
                    val selected = _uiState.value.selectedCurrency?.takeIf { it in currencies }
                        ?: currencies.firstOrNull()
                    _uiState.update {
                        it.copy(
                            currencies = currencies,
                            selectedCurrency = selected,
                            isLoading = false,
                        )
                    }
                    recalculate()
                }
        }
    }

    /**
     * Deriva la serie exacta a partir de la selección y los movimientos actuales.
     *
     * Efectos: publica puntos, navegación futura y errores de overflow de forma atómica.
     */
    private fun recalculate() {
        val state = _uiState.value
        val currency = state.selectedCurrency
        val nextAnchor = shift(state.anchorDate, state.period, 1)
        val canNavigateNext = periodRange(state.period, nextAnchor).first <= LocalDate.now(clock)
        if (currency == null) {
            _uiState.update { it.copy(series = null, canNavigateNext = canNavigateNext) }
            return
        }
        try {
            // 1. Todo cálculo financiero ocurre en dominio antes de entregar coordenadas a Canvas.
            val series = calculateBalanceSeries(
                transactions = transactions,
                currencyCode = currency,
                period = state.period,
                anchorDate = state.anchorDate,
                zoneId = clock.zone,
            )
            _uiState.update {
                it.copy(series = series, canNavigateNext = canNavigateNext, error = false)
            }
        } catch (_: ArithmeticException) {
            // 2. Un overflow nunca se representa como un saldo visual falso.
            _uiState.update { it.copy(series = null, canNavigateNext = canNavigateNext, error = true) }
        }
    }

    /**
     * Desplaza una fecha un número entero de periodos homogéneos.
     *
     * Entradas: ancla actual, granularidad y dirección positiva o negativa.
     * Salida: nueva fecha de referencia.
     */
    private fun shift(
        anchor: LocalDate,
        period: StatisticsPeriod,
        amount: Long,
    ): LocalDate = when (period) {
        StatisticsPeriod.WEEK -> anchor.plusWeeks(amount)
        StatisticsPeriod.MONTH -> anchor.plusMonths(amount)
        StatisticsPeriod.YEAR -> anchor.plusYears(amount)
    }

    companion object {
        /**
         * Crea la factory manual del ViewModel de estadísticas.
         *
         * Entrada: repositorio observable de transacciones.
         */
        fun factory(repository: TransactionRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                /**
                 * Construye la instancia compatible solicitada por Android.
                 *
                 * Entrada: clase de ViewModel.
                 * Salida: [StatisticsViewModel].
                 */
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(StatisticsViewModel::class.java))
                    return StatisticsViewModel(repository) as T
                }
            }
    }
}
