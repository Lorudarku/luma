@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.okami.luma.ui.categories

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.okami.luma.R
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.CategorySpendingSummary
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.ui.common.formatDateDisplay
import dev.okami.luma.ui.common.formatMoneyDisplay
import dev.okami.luma.ui.theme.LumaTheme

private val COMMON_EMOJIS = listOf("🍎", "💻", "🍽️", "⛽", "🏠", "🚗", "💊", "🎮", "📦")

/**
 * Recoge el estado de categorías y conecta lista, detalle y operaciones de gestión.
 *
 * Entrada: ViewModel retenido por la Activity.
 * Salida: superficie Compose reactiva y lifecycle-aware.
 */
@Composable
fun CategoriesRoute(viewModel: CategoriesViewModel, modifier: Modifier = Modifier,
    explorer: dev.okami.luma.ui.explorer.ExplorerViewModel? = null,
    editor: dev.okami.luma.ui.transactions.TransactionsViewModel? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var previousFilter by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<dev.okami.luma.domain.model.TransactionFilter?>(null) }
    val close: () -> Unit = {
        previousFilter?.let { explorer?.apply(it) }
        previousFilter = null
        viewModel.closeDetails()
    }
    androidx.activity.compose.BackHandler(state.selectedCategoryId != null, onBack = close)
    if (editor != null) dev.okami.luma.ui.transactions.TransactionsRoute(editor, dialogsOnly = true)
    CategoriesScreen(
        state = state,
        onAdd = viewModel::showAddForm,
        onOpenDetails = { viewModel.openDetails(it.id) },
        onCloseDetails = close,
        onEdit = viewModel::edit,
        onDelete = { viewModel.requestDelete(it.id) },
        onDismissForm = viewModel::dismissForm,
        onNameChange = viewModel::updateName,
        onEmojiChange = viewModel::updateEmoji,
        onSave = viewModel::save,
        onDismissDelete = viewModel::dismissDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
        contentOverride = if (explorer != null && editor != null) {
            {
                dev.okami.luma.ui.explorer.ExplorerCategoriesContent(explorer, editor, state,
                    onSelect = { category ->
                        previousFilter = explorer.filter.value
                        explorer.category(category.id)
                        viewModel.openDetails(category.id)
                    }, onEdit = viewModel::edit, onDelete = { viewModel.requestDelete(it.id) })
            }
        } else null,
    )
}

/**
 * Alterna entre el resumen de categorías y el desglose de la selección actual.
 *
 * Entradas: estado inmutable y callbacks explícitos.
 * Salida: pantalla Material 3 sin acceso directo a repositorios.
 */
@Composable
fun CategoriesScreen(
    state: CategoriesUiState,
    onAdd: () -> Unit,
    onOpenDetails: (Category) -> Unit,
    onCloseDetails: () -> Unit,
    onEdit: (Category) -> Unit,
    onDelete: (Category) -> Unit,
    onDismissForm: () -> Unit,
    onNameChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    contentOverride: (@Composable () -> Unit)? = null,
) {
    val selectedCategory = state.categories.firstOrNull { it.id == state.selectedCategoryId }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(selectedCategory?.name ?: stringResource(R.string.categories_title)) },
                navigationIcon = {
                    if (selectedCategory != null) {
                        TextButton(onClick = onCloseDetails) { Text(stringResource(R.string.back_to_categories)) }
                    }
                },
            )
        },
        floatingActionButton = {
            if (selectedCategory == null) {
                ExtendedFloatingActionButton(onClick = onAdd) { Text(stringResource(R.string.new_category)) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.error) CategoryErrorCard(onDismissError)
            when {
                contentOverride != null -> contentOverride()
                state.isLoading -> CircularProgressIndicator()
                selectedCategory != null -> CategoryDetails(
                    category = selectedCategory,
                    summary = state.summaries[selectedCategory.id],
                    onEdit = { onEdit(selectedCategory) },
                    onDelete = { onDelete(selectedCategory) },
                    modifier = Modifier.weight(1f),
                )
                state.categories.isEmpty() -> Text(stringResource(R.string.categories_empty))
                else -> CategorySummaryList(
                    categories = state.categories,
                    summaries = state.summaries,
                    onOpenDetails = onOpenDetails,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (state.isFormVisible) {
        CategoryFormDialog(state.form, onDismissForm, onNameChange, onEmojiChange, onSave)
    }
    if (state.pendingDeleteId != null) {
        CategoryDeleteDialog(onDismissDelete, onConfirmDelete)
    }
}

/** Muestra un fallo genérico sin exponer nombres internos de Room o datos financieros. */
@Composable
private fun CategoryErrorCard(onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.categories_error),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

/** Renderiza tarjetas pulsables con gasto del mes y del año por moneda. */
@Composable
private fun CategorySummaryList(
    categories: List<Category>,
    summaries: Map<String, CategorySpendingSummary>,
    onOpenDetails: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(categories, key = Category::id) { category ->
            CategorySummaryCard(category, summaries[category.id], onClick = { onOpenDetails(category) })
        }
    }
}

/** Presenta el resumen financiero sin acciones destructivas en la lista. */
@Composable
private fun CategorySummaryCard(
    category: Category,
    summary: CategorySpendingSummary?,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${category.emoji} ${category.name}", style = MaterialTheme.typography.titleMedium)
            if (summary == null || summary.totals.isEmpty()) {
                Text(stringResource(R.string.no_expenses), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                summary.totals.forEach { total ->
                    SpendingTotalRow(stringResource(R.string.month_label, total.currencyCode), total.monthMinor, total.currencyCode)
                    SpendingTotalRow(stringResource(R.string.year_label, total.currencyCode), total.yearMinor, total.currencyCode)
                }
            }
            Text(stringResource(R.string.view_breakdown), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Alinea una etiqueta temporal con un total monetario exacto. */
@Composable
private fun SpendingTotalRow(label: String, amountMinor: Long, currencyCode: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(formatMoneyDisplay(amountMinor, currencyCode), fontWeight = FontWeight.SemiBold)
    }
}

/** Muestra totales, movimientos históricos y las únicas acciones de gestión. */
@Composable
private fun CategoryDetails(
    category: Category,
    summary: CategorySpendingSummary?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expenses = summary?.expenses.orEmpty()
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${category.emoji} ${category.name}", style = MaterialTheme.typography.headlineSmall)
                    summary?.totals.orEmpty().forEach { total ->
                        Text(
                            stringResource(
                                R.string.this_month_amount,
                                formatMoneyDisplay(total.monthMinor, total.currencyCode),
                            ),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(
                                R.string.this_year_amount,
                                formatMoneyDisplay(total.yearMinor, total.currencyCode),
                            )
                        )
                    }
                    if (summary?.totals.isNullOrEmpty()) Text(stringResource(R.string.no_expenses))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick = onDelete) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item { Text(stringResource(R.string.movements), style = MaterialTheme.typography.titleMedium) }
        if (expenses.isEmpty()) {
            item { Text(stringResource(R.string.category_no_expenses)) }
        } else {
            items(expenses, key = Transaction::id) { transaction ->
                CategoryExpenseRow(transaction)
            }
        }
    }
}

/** Presenta una operación individual con fecha, descripción e importe. */
@Composable
private fun CategoryExpenseRow(transaction: Transaction) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(transaction.description ?: stringResource(R.string.expense_without_description))
                Text(formatDateDisplay(transaction.occurredAt), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                formatMoneyDisplay(-transaction.money.amountMinor, transaction.money.currencyCode),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Edita nombre y emoji ofreciendo accesos rápidos Unicode del sistema. */
@Composable
private fun CategoryFormDialog(
    form: CategoryFormState,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (form.editingId == null) R.string.new_category_title
                    else R.string.edit_category_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.category_name)) },
                    isError = form.invalidName,
                    supportingText = if (form.invalidName) {
                        { Text(stringResource(R.string.category_name_error)) }
                    } else null,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.emoji,
                    onValueChange = onEmojiChange,
                    label = { Text(stringResource(R.string.category_emoji)) },
                    isError = form.invalidEmoji,
                    supportingText = if (form.invalidEmoji) {
                        { Text(stringResource(R.string.category_emoji_error)) }
                    } else {
                        { Text(stringResource(R.string.category_emoji_hint)) }
                    },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    COMMON_EMOJIS.take(6).forEach { emoji ->
                        TextButton(onClick = { onEmojiChange(emoji) }) { Text(emoji) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Solicita confirmación antes de retirar una categoría y limpiar referencias. */
@Composable
private fun CategoryDeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_category_title)) },
        text = { Text(stringResource(R.string.delete_category_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Proporciona un resumen ficticio para revisar contraste y jerarquía visual. */
@Preview(name = "Categorías claro", showBackground = true, widthDp = 360)
@Preview(
    name = "Categorías oscuro",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CategoriesScreenPreview() {
    val category = Category("preview", "Alimentación y supermercado", "🍎", false)
    LumaTheme {
        CategoriesScreen(
            state = CategoriesUiState(
                categories = listOf(category),
                summaries = mapOf(
                    category.id to CategorySpendingSummary(
                        category.id,
                        listOf(dev.okami.luma.domain.model.CategoryCurrencySpending("EUR", 12_345, 98_765)),
                        emptyList(),
                    )
                ),
                isLoading = false,
            ),
            onAdd = {}, onOpenDetails = {}, onCloseDetails = {}, onEdit = {}, onDelete = {},
            onDismissForm = {}, onNameChange = {}, onEmojiChange = {}, onSave = {},
            onDismissDelete = {}, onConfirmDelete = {}, onDismissError = {},
        )
    }
}
