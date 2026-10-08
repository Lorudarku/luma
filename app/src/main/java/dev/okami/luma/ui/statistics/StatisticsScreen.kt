@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.okami.luma.ui.statistics

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.okami.luma.R
import dev.okami.luma.domain.model.BalancePoint
import dev.okami.luma.domain.model.BalanceSeries
import dev.okami.luma.domain.model.StatisticsPeriod
import dev.okami.luma.domain.model.calculateChartAxisScale
import dev.okami.luma.domain.validation.TransactionDateCodec
import dev.okami.luma.ui.common.formatMoneyDisplay
import dev.okami.luma.ui.theme.ExpenseRedDark
import dev.okami.luma.ui.theme.ExpenseRedLight
import dev.okami.luma.ui.theme.IncomeGreenDark
import dev.okami.luma.ui.theme.IncomeGreenLight
import dev.okami.luma.ui.theme.LumaTheme
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/**
 * Recoge el estado estadístico y conecta los selectores con el ViewModel.
 *
 * Entrada: ViewModel de estadísticas.
 * Salida: pantalla lifecycle-aware.
 */
@Composable
fun StatisticsRoute(
    viewModel: StatisticsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatisticsScreen(
        state = state,
        onPeriodSelected = viewModel::selectPeriod,
        onCurrencySelected = viewModel::selectCurrency,
        onPrevious = viewModel::previousPeriod,
        onNext = viewModel::nextPeriod,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

/**
 * Renderiza selectores, gráfica acumulada y alternativa textual accesible.
 *
 * Entradas: estado exacto y callbacks de navegación.
 * Salida: superficie estadística sin cálculos financieros propios.
 */
@Composable
fun StatisticsScreen(
    state: StatisticsUiState,
    onPeriodSelected: (StatisticsPeriod) -> Unit,
    onCurrencySelected: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.statistics_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Periodo y moneda controlan una sola serie, sin conversiones implícitas.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatisticsPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = state.period == period,
                        onClick = { onPeriodSelected(period) },
                        label = { Text(periodLabel(period)) },
                    )
                }
            }
            if (state.currencies.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.currencies.forEach { currency ->
                        FilterChip(
                            selected = state.selectedCurrency == currency,
                            onClick = { onCurrencySelected(currency) },
                            label = { Text(currency) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onPrevious) { Text("‹ ${stringResource(R.string.previous_period)}") }
                TextButton(onClick = onNext, enabled = state.canNavigateNext) {
                    Text("${stringResource(R.string.next_period)} ›")
                }
            }

            // 2. Carga, error, vacío y gráfica se presentan de forma explícita.
            when {
                state.isLoading -> CircularProgressIndicator()
                state.error -> Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(stringResource(R.string.statistics_error), modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.dismiss)) }
                    }
                }
                state.series == null -> Text(stringResource(R.string.statistics_empty))
                else -> BalanceChartCard(
                    series = state.series,
                    canNavigateNext = state.canNavigateNext,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )
            }
        }
    }
}

/**
 * Dibuja la curva acumulada y enumera sus puntos con texto legible.
 *
 * Entrada: serie de una única moneda ya calculada.
 * Salida: tarjeta accesible con Canvas y resumen textual.
 */
@Composable
private fun BalanceChartCard(
    series: BalanceSeries,
    canNavigateNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val upColor = if (darkTheme) IncomeGreenDark else IncomeGreenLight
    val downColor = if (darkTheme) ExpenseRedDark else ExpenseRedLight
    val neutralColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(
        R.string.chart_description,
        series.currencyCode,
        TransactionDateCodec.format(series.rangeStart),
        TransactionDateCodec.format(series.rangeEnd),
    )

    val axisScale = calculateChartAxisScale(series)
    var horizontalDrag = 0f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(series.rangeStart, canNavigateNext) {
                val threshold = 56.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDrag = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        horizontalDrag += amount
                    },
                    onDragEnd = {
                        // 1. Derecha retrocede; izquierda avanza solo si el ViewModel lo permite.
                        when {
                            horizontalDrag >= threshold -> onPrevious()
                            horizontalDrag <= -threshold && canNavigateNext -> onNext()
                        }
                        horizontalDrag = 0f
                    },
                    onDragCancel = { horizontalDrag = 0f },
                )
            }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(description, style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                Column(
                    modifier = Modifier.fillMaxHeight().padding(end = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    axisScale.yValuesMinor.forEach { value ->
                        Text(
                            formatMoneyDisplay(value, series.currencyCode),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                BalanceCanvas(
                    points = series.points,
                    upColor = upColor,
                    downColor = downColor,
                    neutralColor = neutralColor,
                    gridColor = gridColor,
                    description = description,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                axisScale.xPoints.forEach { point ->
                    Text(
                        formatAxisDate(series.period, point),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(stringResource(R.string.chart_swipe_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.chart_legend))
            series.points.forEach { point ->
                Text(
                    "${TransactionDateCodec.format(point.date)} · " +
                        formatMoneyDisplay(point.balanceMinor, series.currencyCode),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Convierte saldos Long en coordenadas de presentación y colorea cada segmento por dirección.
 *
 * Entradas: puntos exactos y colores semánticos del tema.
 * Salida: Canvas; los Float se usan exclusivamente para píxeles.
 */
@Composable
private fun BalanceCanvas(
    points: List<BalancePoint>,
    upColor: Color,
    downColor: Color,
    neutralColor: Color,
    gridColor: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
    ) {
        if (points.isEmpty()) return@Canvas
        // 1. El rango BigDecimal evita overflow al restar extremos Long antes de escalar.
        val min = points.minOf { it.balanceMinor }
        val max = points.maxOf { it.balanceMinor }
        val span = BigDecimal.valueOf(max).subtract(BigDecimal.valueOf(min))
            .takeUnless { it.signum() == 0 } ?: BigDecimal.ONE
        val xStep = if (points.size == 1) 0f else size.width / (points.size - 1)

        repeat(5) { index ->
            val y = size.height * index / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        // 2. Cada coordenada deriva de la proporción visual; el saldo original nunca cambia de tipo.
        val offsets = points.mapIndexed { index, point ->
            val normalized = BigDecimal.valueOf(point.balanceMinor)
                .subtract(BigDecimal.valueOf(min))
                .divide(span, 8, java.math.RoundingMode.HALF_UP)
                .toFloat()
            Offset(index * xStep, size.height - normalized * size.height)
        }
        offsets.zipWithNext().forEachIndexed { index, (start, end) ->
            val previous = points[index].balanceMinor
            val current = points[index + 1].balanceMinor
            val color = when {
                current > previous -> upColor
                current < previous -> downColor
                else -> neutralColor
            }
            drawLine(color, start, end, strokeWidth = 6f, cap = StrokeCap.Round)
        }
        offsets.forEach { offset -> drawCircle(neutralColor, radius = 4f, center = offset) }
    }
}

/**
 * Formatea una etiqueta compacta del eje X según la granularidad visible.
 *
 * Entradas: periodo y punto seleccionado.
 * Salida: día/mes para semana y mes, o mes/año para la vista anual.
 */
private fun formatAxisDate(period: StatisticsPeriod, point: BalancePoint): String {
    val formatter = when (period) {
        StatisticsPeriod.WEEK,
        StatisticsPeriod.MONTH,
        -> DAY_MONTH_FORMATTER
        StatisticsPeriod.YEAR -> MONTH_YEAR_FORMATTER
    }
    return formatter.format(point.date)
}

/**
 * Traduce la granularidad técnica a una etiqueta breve.
 *
 * Entrada: periodo estadístico.
 * Salida: texto en español.
 */
@Composable
private fun periodLabel(period: StatisticsPeriod): String = when (period) {
    StatisticsPeriod.WEEK -> stringResource(R.string.period_week)
    StatisticsPeriod.MONTH -> stringResource(R.string.period_month)
    StatisticsPeriod.YEAR -> stringResource(R.string.period_year)
}

private val DAY_MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")
private val MONTH_YEAR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/uuuu")

/** Proporciona una serie ficticia para revisar ejes, contraste y anchuras estrechas. */
@Preview(name = "Estadísticas claro", showBackground = true, widthDp = 360)
@Preview(
    name = "Estadísticas oscuro",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun StatisticsScreenPreview() {
    val start = java.time.LocalDate.of(2026, 8, 1)
    val series = BalanceSeries(
        currencyCode = "EUR",
        period = StatisticsPeriod.MONTH,
        rangeStart = start,
        rangeEnd = start.plusDays(2),
        points = listOf(
            BalancePoint(start, 10_000),
            BalancePoint(start.plusDays(1), 7_500),
            BalancePoint(start.plusDays(2), 12_500),
        ),
    )
    LumaTheme {
        StatisticsScreen(
            state = StatisticsUiState(
                currencies = listOf("EUR"),
                selectedCurrency = "EUR",
                anchorDate = start,
                series = series,
                isLoading = false,
            ),
            onPeriodSelected = {}, onCurrencySelected = {}, onPrevious = {}, onNext = {},
            onDismissError = {},
        )
    }
}
