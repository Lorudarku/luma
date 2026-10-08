package dev.okami.luma.ui.explorer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.okami.luma.R
import dev.okami.luma.domain.model.Category
import dev.okami.luma.ui.categories.CategoriesUiState
import dev.okami.luma.ui.common.formatMoneyDisplay
import dev.okami.luma.ui.transactions.TransactionsViewModel

/** Resúmenes derivados del mismo conjunto filtrado; edición y borrado aparecen solo en el detalle. */
@Composable
fun ExplorerCategoriesContent(explorer: ExplorerViewModel, editor: TransactionsViewModel, state: CategoriesUiState,
    onSelect: (Category) -> Unit, onEdit: (Category) -> Unit, onDelete: (Category) -> Unit) {
    val summary by explorer.summary.collectAsStateWithLifecycle()
    val selected = state.categories.firstOrNull { it.id == state.selectedCategoryId }
    if (selected != null) {
        Text("${selected.emoji} ${selected.name}", style = MaterialTheme.typography.titleLarge)
        Row {
            TextButton(onClick = { onEdit(selected) }) { Text(stringResource(R.string.edit)) }
            TextButton(onClick = { onDelete(selected) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        }
        ExplorerMovements(explorer, editor)
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
            if (summary.loading) item { CircularProgressIndicator() }
            if (summary.error) item { Text(stringResource(R.string.explore_error)) }
            item { Text(stringResource(R.string.explore_filtered), style = MaterialTheme.typography.bodySmall) }
            items(state.categories, key = { it.id }) { category ->
                Card(onClick = { onSelect(category) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${category.emoji} ${category.name}", style = MaterialTheme.typography.titleMedium)
                        val totals = summary.dashboards.filter { category.id in it.categoryExpenses }
                        if (totals.isEmpty()) Text(stringResource(R.string.no_expenses))
                        totals.forEach { data ->
                            Text(formatMoneyDisplay(data.categoryExpenses.getValue(category.id), data.currency))
                            Text(stringResource(R.string.this_month_amount, formatMoneyDisplay(data.categoryMonthExpenses[category.id] ?: 0, data.currency)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.this_year_amount, formatMoneyDisplay(data.categoryYearExpenses[category.id] ?: 0, data.currency)), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.view_breakdown), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
