@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.okami.luma.ui.explorer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.okami.luma.R
import dev.okami.luma.domain.model.*
import dev.okami.luma.ui.common.formatMoneyDisplay
import dev.okami.luma.ui.transactions.TransactionsViewModel
import dev.okami.luma.ui.transactions.TransactionRow
import dev.okami.luma.ui.theme.IncomeGreenLight
import dev.okami.luma.ui.theme.ExpenseRedLight
import dev.okami.luma.ui.theme.IncomeGreenDark
import dev.okami.luma.ui.theme.ExpenseRedDark
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

/** Lista paginada reutilizada en movimientos y detalle: todas las acciones conservan confirmaciones. */
@Composable
fun ExplorerMovements(explorer: ExplorerViewModel, editor: TransactionsViewModel, modifier: Modifier = Modifier) {
    val rows = explorer.pages.collectAsLazyPagingItems()
    val state by editor.uiState.collectAsStateWithLifecycle()
    val summary by explorer.summary.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (summary.error) item { Text(stringResource(R.string.explore_error), color = MaterialTheme.colorScheme.error) }
        items(summary.dashboards, key = { it.currency }) { data -> TotalsCard(data) }
        if (rows.loadState.refresh is LoadState.Loading) item { CircularProgressIndicator() }
        if (rows.loadState.refresh is LoadState.Error || rows.loadState.append is LoadState.Error) item {
            Text(stringResource(R.string.explore_error))
            TextButton(onClick = rows::retry) { Text(stringResource(R.string.explore_retry)) }
        }
        if (rows.loadState.refresh is LoadState.NotLoading && rows.itemCount == 0) item { Text(stringResource(R.string.explore_no_results)) }
        items(rows.itemCount, key = rows.itemKey { it.transaction.id }) { index ->
            rows[index]?.let { row ->
                val transaction = row.transaction
                Card(Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        TransactionRow(transaction, row.runningBalance,
                            state.categories.firstOrNull { it.id == transaction.categoryId },
                            state.recurringRules.any { it.id == transaction.recurringExpenseId && it.active },
                            onEdit = { editor.edit(transaction) }, onDelete = { editor.requestDelete(transaction.id) },
                            onRecurrenceChange = { editor.requestRecurrenceChange(transaction, it) })
                    }
                }
            }
        }
        if (rows.loadState.append is LoadState.Loading) item { CircularProgressIndicator() }
    }
}

/** Resumen de resultados exactos; la variación filtrada no se etiqueta como saldo de cuenta. */
@Composable
private fun TotalsCard(data: ExplorerDashboard) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(data.currency, style = MaterialTheme.typography.titleLarge)
            Text(pluralStringResource(R.plurals.explore_results, if (data.count == 1L) 1 else 0, data.count))
            Text("${stringResource(R.string.income)}: ${formatMoneyDisplay(data.income, data.currency)}")
            Text("${stringResource(R.string.expenses)}: ${formatMoneyDisplay(data.expense, data.currency)}")
            Text("${stringResource(R.string.explore_net)}: ${formatMoneyDisplay(Math.subtractExact(data.income, data.expense), data.currency)}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Panel por moneda con saldo, flujos, categoría y comparación; cambios de periodo pasan al ViewModel. */
@Composable
fun ExplorerStatistics(explorer: ExplorerViewModel, onDrillDown: () -> Unit, modifier: Modifier = Modifier) {
    val summary by explorer.summary.collectAsStateWithLifecycle()
    val filter by explorer.filter.collectAsStateWithLifecycle()
    val categories by explorer.catalog.collectAsStateWithLifecycle()
    var currency by remember { mutableStateOf<String?>(null) }
    val selected = summary.dashboards.firstOrNull { it.currency == currency } ?: summary.dashboards.firstOrNull()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.statistics_title), style = MaterialTheme.typography.headlineMedium) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            summary.dashboards.forEach { data -> FilterChip(data == selected, { currency = data.currency }, label = { Text(data.currency) }) }
        } }
        if (summary.loading) item { CircularProgressIndicator() }
        if (summary.error) item { Text(stringResource(R.string.explore_error)) }
        if (!summary.loading && selected == null) item { Text(stringResource(R.string.explore_no_results)) }
        if (selected != null) {
            item { TotalsCard(selected) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { explorer.shift(-1) }) { Text(stringResource(R.string.previous_period)) }
                TextButton(onClick = { explorer.shift(1) }, enabled = shiftedRange(filter, 1, explorer.today).first <= explorer.today) { Text(stringResource(R.string.next_period)) }
            } }
            if (filter.isSelection) item { BalancePlot(selected.points, selected.currency, stringResource(R.string.explore_selection_curve), explorer::shift) }
            item {
                BalancePlot(selected.realPoints, selected.currency, stringResource(R.string.explore_real_balance), explorer::shift)
                Text(stringResource(R.string.explore_balance_hint), style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("${stringResource(R.string.income)} / ${stringResource(R.string.expenses)}", style = MaterialTheme.typography.titleLarge)
                FlowBars(selected, onSelect = { date ->
                    val index = selected.flow.indexOfFirst { it.date == date }
                    val start = if (index > 0) selected.flow[index - 1].date.plusDays(1) else filter.start ?: selected.flow.first().date
                    explorer.apply(filter.copy(start = start, end = date, currencies = setOf(selected.currency), period = null))
                    onDrillDown()
                })
            }
            item { Text(stringResource(R.string.explore_category_spending), style = MaterialTheme.typography.titleLarge) }
            items(selected.categoryExpenses.entries.sortedByDescending { it.value }) { (id, amount) ->
                val category = categories.firstOrNull { it.id == id }
                val label = category?.let { "${it.emoji} ${it.name}" } ?: stringResource(R.string.explore_uncategorized)
                Card(onClick = {
                    explorer.apply(filter.copy(currencies = setOf(selected.currency), categoryIds = setOfNotNull(id), uncategorized = id == null, types = setOf(TransactionType.EXPENSE)))
                    onDrillDown()
                }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(label)
                        Text(formatMoneyDisplay(amount, selected.currency))
                        val percent = selected.categoryPercentages.getValue(id)
                        Text("$percent %")
                        LinearProgressIndicator(progress = { percent.divide(BigDecimal(100)).toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item { Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.explore_comparison), style = MaterialTheme.typography.titleMedium)
                    Text("Δ ${formatMoneyDisplay(selected.difference, selected.currency)}")
                    Text(selected.percentage?.let { "$it %" } ?: stringResource(R.string.explore_no_percentage))
                }
            } }
        }
    }
}

/** Dibuja ejes exactos, puntos seleccionables y gesto horizontal; Float se limita a coordenadas. */
@Composable
private fun BalancePlot(points: List<BalancePoint>, currency: String, title: String, onShift: (Int) -> Unit) {
    if (points.isEmpty()) return
    val low = points.minOf { it.balanceMinor }
    val high = points.maxOf { it.balanceMinor }
    val range = BigDecimal.valueOf(high).subtract(BigDecimal.valueOf(low))
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val green = if (dark) IncomeGreenDark else IncomeGreenLight
    val red = if (dark) ExpenseRedDark else ExpenseRedLight
    val neutral = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    var table by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("${points[selected].date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))} · ${formatMoneyDisplay(points[selected].balanceMinor, currency)}")
            Row(Modifier.height(210.dp)) {
                Column(Modifier.fillMaxHeight().widthIn(max = 130.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    repeat(5) { index ->
                        val tick = BigDecimal.valueOf(high).subtract(range.multiply(BigDecimal(index)).divide(BigDecimal(4), 0, RoundingMode.HALF_UP)).longValueExact()
                        Text(formatMoneyDisplay(tick, currency), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().padding(8.dp)
                    .semantics { contentDescription = title }
                    .pointerInput(points) { detectTapGestures { offset -> selected = ((offset.x / size.width) * points.lastIndex).toInt().coerceIn(points.indices) } }
                    .pointerInput(points) {
                        var drag = 0f
                        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                            onDragEnd = { if (kotlin.math.abs(drag) > 56.dp.toPx()) onShift(if (drag < 0) 1 else -1) }) { change, amount -> change.consume(); drag += amount }
                    }) {
                    repeat(5) { index -> val y = size.height * index / 4; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
                    if (low < 0 && high > 0) {
                        val zeroY = BigDecimal.valueOf(high).divide(range, 12, RoundingMode.HALF_UP).toFloat() * size.height
                        drawLine(neutral, Offset(0f, zeroY), Offset(size.width, zeroY), 1.5.dp.toPx())
                    }
                    val coordinates = points.mapIndexed { index, point ->
                        val fraction = if (range.signum() == 0) 0.5f else BigDecimal.valueOf(point.balanceMinor).subtract(BigDecimal.valueOf(low)).divide(range, 12, RoundingMode.HALF_UP).toFloat()
                        Offset(size.width * index / points.lastIndex.coerceAtLeast(1), size.height * (1f - fraction))
                    }
                    coordinates.zipWithNext().forEachIndexed { index, (a, b) ->
                        val color = when { points[index + 1].balanceMinor > points[index].balanceMinor -> green; points[index + 1].balanceMinor < points[index].balanceMinor -> red; else -> neutral }
                        drawLine(color, a, b, 2.dp.toPx(), StrokeCap.Round)
                    }
                    drawCircle(neutral, 4.dp.toPx(), coordinates[selected])
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(points.first().date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")), style = MaterialTheme.typography.labelSmall)
                Text(points.last().date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")), style = MaterialTheme.typography.labelSmall)
            }
            Text(stringResource(R.string.explore_legend), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { table = !table }) { Text(stringResource(R.string.explore_points)) }
            if (table) points.forEach { Text("${it.date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))} · ${formatMoneyDisplay(it.balanceMinor, currency)}") }
        }
    }
}

/** Barras accesibles por intervalo; tocar abre la lista exacta que compone ese intervalo. */
@Composable
private fun FlowBars(data: ExplorerDashboard, onSelect: (java.time.LocalDate) -> Unit) {
    val max = data.flow.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1) ?: 1
    var table by remember { mutableStateOf(false) }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val green = if (dark) IncomeGreenDark else IncomeGreenLight
    val red = if (dark) ExpenseRedDark else ExpenseRedLight
    Canvas(Modifier.fillMaxWidth().height(170.dp).pointerInput(data.flow) {
        detectTapGestures { offset ->
            if (data.flow.isNotEmpty()) onSelect(data.flow[((offset.x / size.width) * data.flow.size).toInt().coerceIn(data.flow.indices)].date)
        }
    }) {
        val width = size.width / data.flow.size.coerceAtLeast(1)
        data.flow.forEachIndexed { index, point ->
            listOf(point.income to green, point.expense to red).forEachIndexed { side, (amount, color) ->
                val height = BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(max), 12, RoundingMode.HALF_UP).toFloat() * size.height
                drawRect(color, Offset(index * width + side * width / 2, size.height - height), androidx.compose.ui.geometry.Size(width * 0.4f, height))
            }
        }
    }
    Text("0 — ${formatMoneyDisplay(max, data.currency)}", style = MaterialTheme.typography.labelSmall)
    if (data.flow.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(data.flow.first().date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")), style = MaterialTheme.typography.labelSmall)
        Text(data.flow.last().date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")), style = MaterialTheme.typography.labelSmall)
    }
    Text(stringResource(R.string.explore_flow_legend), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { table = !table }) { Text(stringResource(R.string.explore_points)) }
    if (table) data.flow.forEach { point ->
        TextButton(onClick = { onSelect(point.date) }) {
            Text("${point.date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))}: +${formatMoneyDisplay(point.income, data.currency)} / −${formatMoneyDisplay(point.expense, data.currency)}")
        }
    }
}

/** Preview ficticia con saldos negativos y grandes para revisar ejes, tema oscuro y texto ampliado. */
@androidx.compose.ui.tooling.preview.Preview(name = "Gráfica clara", widthDp = 360)
@androidx.compose.ui.tooling.preview.Preview(name = "Gráfica estrecha", widthDp = 320, fontScale = 1.5f)
@androidx.compose.ui.tooling.preview.Preview(name = "Gráfica oscura", widthDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BalancePlotPreview() {
    dev.okami.luma.ui.theme.LumaTheme {
        BalancePlot(listOf(BalancePoint(java.time.LocalDate.of(2026, 9, 1), -120_000),
            BalancePoint(java.time.LocalDate.of(2026, 9, 2), 240_000),
            BalancePoint(java.time.LocalDate.of(2026, 9, 3), 180_000)), "EUR", "Saldo ficticio", {})
    }
}
