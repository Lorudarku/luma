@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.okami.luma.ui.transactions

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.okami.luma.R
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.CurrencyTotals
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import dev.okami.luma.domain.validation.AmountInputError
import dev.okami.luma.ui.common.CategorySelector
import dev.okami.luma.ui.common.PastDatePickerField
import dev.okami.luma.ui.common.formatDateDisplay
import dev.okami.luma.ui.common.formatMoneyDisplay
import dev.okami.luma.ui.theme.ExpenseRedDark
import dev.okami.luma.ui.theme.ExpenseRedLight
import dev.okami.luma.ui.theme.IncomeGreenDark
import dev.okami.luma.ui.theme.IncomeGreenLight
import dev.okami.luma.ui.theme.LumaTheme
import java.time.Instant

/**
 * Recoge el estado del ViewModel respetando el ciclo de vida y conecta cada acción de usuario.
 *
 * Entrada: ViewModel de movimientos y modificador opcional.
 * Salida: árbol Compose reactivo sin acceso directo a Room.
 */
@Composable
fun TransactionsRoute(viewModel: TransactionsViewModel, modifier: Modifier = Modifier,
    explorer: dev.okami.luma.ui.explorer.ExplorerViewModel? = null, dialogsOnly: Boolean = false) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TransactionsScreen(
        state = state,
        onAdd = viewModel::showAddForm,
        onEdit = viewModel::edit,
        onDelete = { viewModel.requestDelete(it.id) },
        onDismissForm = viewModel::dismissForm,
        onTypeChange = viewModel::updateType,
        onAmountChange = viewModel::updateAmount,
        onDateChange = viewModel::updateDate,
        onDescriptionChange = viewModel::updateDescription,
        onCategoryChange = viewModel::updateCategory,
        onRepeatMonthlyChange = viewModel::updateRepeatMonthly,
        onReview = viewModel::reviewTransaction,
        onDismissSave = viewModel::dismissSaveConfirmation,
        onConfirmSave = viewModel::confirmSave,
        onDismissDelete = viewModel::dismissDeleteConfirmation,
        onConfirmDelete = viewModel::confirmDelete,
        onRecurrenceChange = viewModel::requestRecurrenceChange,
        onDismissRecurrence = viewModel::dismissRecurrenceConfirmation,
        onConfirmRecurrence = viewModel::confirmRecurrenceChange,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
        listContent = explorer?.let { model -> { dev.okami.luma.ui.explorer.ExplorerMovements(model, viewModel) } },
        showContent = !dialogsOnly,
    )
}

/**
 * Renderiza resumen, lista, formulario y confirmaciones de movimientos.
 *
 * Entradas: estado inmutable y callbacks explícitos.
 * Salida: superficie Material 3; la persistencia permanece en capas inferiores.
 */
@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    onAdd: () -> Unit,
    onEdit: (Transaction) -> Unit,
    onDelete: (Transaction) -> Unit,
    onDismissForm: () -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onRepeatMonthlyChange: (Boolean) -> Unit,
    onReview: () -> Unit,
    onDismissSave: () -> Unit,
    onConfirmSave: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onRecurrenceChange: (Transaction, Boolean) -> Unit,
    onDismissRecurrence: () -> Unit,
    onConfirmRecurrence: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    listContent: (@Composable () -> Unit)? = null,
    showContent: Boolean = true,
) {
    if (showContent) Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.transactions_title)) }) },
        floatingActionButton = {
            if (!state.isFormVisible) {
                ExtendedFloatingActionButton(onClick = onAdd) { Text(stringResource(R.string.add_transaction)) }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        ) {
            state.error?.let { error ->
                ErrorCard(error, onDismissError, Modifier.padding(bottom = 12.dp))
            }
            if (listContent == null) state.totals.forEach { totals ->
                SummaryCard(totals, Modifier.padding(bottom = 12.dp))
            }
            when {
                listContent != null -> Box(Modifier.weight(1f)) { listContent() }
                state.isLoading -> LoadingContent()
                state.transactions.isEmpty() -> EmptyContent()
                else -> TransactionList(
                    transactions = state.transactions,
                    runningBalances = state.runningBalances,
                    categories = state.categories,
                    activeRecurringRuleIds = state.recurringRules
                        .filter { it.active }
                        .mapTo(mutableSetOf()) { it.id },
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onRecurrenceChange = onRecurrenceChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (state.isFormVisible && !state.showSaveConfirmation) {
        TransactionFormDialog(
            form = state.form,
            categories = state.categories,
            today = state.today,
            onDismiss = onDismissForm,
            onTypeChange = onTypeChange,
            onAmountChange = onAmountChange,
            onDateChange = onDateChange,
            onDescriptionChange = onDescriptionChange,
            onCategoryChange = onCategoryChange,
            onRepeatMonthlyChange = onRepeatMonthlyChange,
            onReview = onReview,
        )
    }
    if (state.showSaveConfirmation) {
        SaveConfirmationDialog(state.form, state.isSaving, onDismissSave, onConfirmSave)
    }
    if (state.pendingDeleteId != null) {
        DeleteConfirmationDialog(onDismissDelete, onConfirmDelete)
    }
    if (state.pendingRecurrenceTransactionId != null && state.pendingRecurrenceEnabled != null) {
        RecurrenceConfirmationDialog(
            enabled = state.pendingRecurrenceEnabled,
            isSaving = state.isUpdatingRecurrence,
            onDismiss = onDismissRecurrence,
            onConfirm = onConfirmRecurrence,
        )
    }
}

/**
 * Muestra ingresos, gastos y balance de una moneda.
 *
 * Entrada: totales exactos y modificador.
 * Salida: tarjeta compacta de resumen.
 */
@Composable
private fun SummaryCard(totals: CurrencyTotals, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.summary_currency, totals.currencyCode), fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryValue(stringResource(R.string.income), formatMoneyDisplay(totals.incomeMinor, totals.currencyCode))
                SummaryValue(stringResource(R.string.expenses), formatMoneyDisplay(totals.expenseMinor, totals.currencyCode))
                SummaryValue(stringResource(R.string.balance), formatMoneyDisplay(totals.balanceMinor, totals.currencyCode))
            }
        }
    }
}

/**
 * Presenta una etiqueta y un valor del resumen alineados verticalmente.
 *
 * Entradas: etiqueta y dinero ya formateado.
 */
@Composable
private fun SummaryValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Presenta un fallo recuperable sin mostrar información financiera interna.
 *
 * Entradas: tipo de error, callback y modificador.
 */
@Composable
private fun ErrorCard(
    error: TransactionsUiError,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (error) {
        TransactionsUiError.LOAD_FAILED -> stringResource(R.string.error_load)
        TransactionsUiError.SAVE_FAILED -> stringResource(R.string.error_save)
        TransactionsUiError.DELETE_FAILED -> stringResource(R.string.error_delete)
        TransactionsUiError.TOTALS_OVERFLOW -> stringResource(R.string.error_totals)
        TransactionsUiError.RECURRENCE_FAILED -> stringResource(R.string.recurrence_error)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

/**
 * Muestra el estado de carga inicial centrado.
 *
 * Salida: indicador y descripción textual.
 */
@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.loading_transactions))
    }
}

/**
 * Explica el estado vacío y orienta hacia la acción flotante.
 *
 * Salida: mensaje puramente presentacional.
 */
@Composable
private fun EmptyContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.empty_transactions_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_transactions_message))
    }
}

/**
 * Renderiza movimientos con claves estables y separadores.
 *
 * Entradas: lista, balances, catálogo y callbacks.
 * Salida: LazyColumn eficiente.
 */
@Composable
private fun TransactionList(
    transactions: List<Transaction>,
    runningBalances: Map<String, Long>,
    categories: List<Category>,
    activeRecurringRuleIds: Set<String>,
    onEdit: (Transaction) -> Unit,
    onDelete: (Transaction) -> Unit,
    onRecurrenceChange: (Transaction, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(transactions, key = Transaction::id) { transaction ->
            TransactionRow(
                transaction = transaction,
                runningBalance = runningBalances[transaction.id],
                category = categories.firstOrNull { it.id == transaction.categoryId },
                isMonthlyActive = transaction.recurringExpenseId in activeRecurringRuleIds,
                onEdit = { onEdit(transaction) },
                onDelete = { onDelete(transaction) },
                onRecurrenceChange = { enabled -> onRecurrenceChange(transaction, enabled) },
            )
            HorizontalDivider()
        }
    }
}

/**
 * Presenta un movimiento con color semántico, categoría y saldo posterior.
 *
 * Entradas: transacción, balance, categoría y acciones.
 * Salida: fila que conserva signo y etiquetas además del color.
 */
@Composable
fun TransactionRow(
    transaction: Transaction,
    runningBalance: Long?,
    category: Category?,
    isMonthlyActive: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRecurrenceChange: (Boolean) -> Unit,
) {
    val isIncome = transaction.type == TransactionType.INCOME
    val amountColor = if (isSystemInDarkTheme()) {
        if (isIncome) IncomeGreenDark else ExpenseRedDark
    } else {
        if (isIncome) IncomeGreenLight else ExpenseRedLight
    }
    val signedAmount = if (isIncome) transaction.money.amountMinor else -transaction.money.amountMinor
    val typeLabel = stringResource(
        if (isIncome) R.string.income_singular else R.string.expense
    )
    val monthlyDescription = stringResource(R.string.monthly_expense)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(typeLabel, style = MaterialTheme.typography.labelLarge)
                transaction.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                category?.let { Text("${it.emoji} ${it.name}", style = MaterialTheme.typography.bodySmall) }
                Text(formatDateDisplay(transaction.occurredAt), style = MaterialTheme.typography.bodySmall)
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.semantics {
                    contentDescription = "$typeLabel ${formatMoneyDisplay(signedAmount, transaction.money.currencyCode)}"
                },
            ) {
                Text(
                    formatMoneyDisplay(signedAmount, transaction.money.currencyCode),
                    color = amountColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                runningBalance?.let {
                    Text(
                        "Balance: ${formatMoneyDisplay(it, transaction.money.currencyCode)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!isIncome) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = isMonthlyActive,
                    onCheckedChange = onRecurrenceChange,
                    modifier = Modifier.semantics {
                        contentDescription = monthlyDescription
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.monthly_expense), style = MaterialTheme.typography.labelLarge)
                    if (transaction.recurringExpenseId != null) {
                        Text(
                            stringResource(
                                if (isMonthlyActive) R.string.monthly_rule_active
                                else R.string.monthly_rule_inactive
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    }
}

/**
 * Confirma un cambio que afecta a toda una regla mensual y no solo a la fila pulsada.
 *
 * Entradas: estado deseado, progreso y callbacks.
 * Salida: explicación explícita sobre generación futura y conservación del histórico.
 */
@Composable
private fun RecurrenceConfirmationDialog(
    enabled: Boolean,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (enabled) R.string.activate_monthly_expense
                    else R.string.deactivate_monthly_expense
                )
            )
        },
        text = {
            Text(
                if (enabled) {
                    stringResource(R.string.activate_monthly_message)
                } else {
                    stringResource(R.string.deactivate_monthly_message)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isSaving) {
                Text(
                    if (isSaving) stringResource(R.string.saving)
                    else stringResource(if (enabled) R.string.activate else R.string.deactivate)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Solicita todos los campos de un movimiento sin persistir durante la edición.
 *
 * Entradas: borrador, catálogo, fecha máxima y callbacks.
 * Salida: diálogo desplazable con calendario y recurrencia opcional.
 */
@Composable
private fun TransactionFormDialog(
    form: TransactionFormState,
    categories: List<Category>,
    today: java.time.LocalDate,
    onDismiss: () -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onRepeatMonthlyChange: (Boolean) -> Unit,
    onReview: () -> Unit,
) {
    val dateError = when {
        form.futureDate -> stringResource(R.string.future_date)
        form.invalidDate -> stringResource(R.string.invalid_date)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (form.editingId == null) R.string.new_transaction
                    else R.string.edit_transaction
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.type == TransactionType.EXPENSE,
                        onClick = { onTypeChange(TransactionType.EXPENSE) },
                        label = { Text(stringResource(R.string.expense)) },
                    )
                    FilterChip(
                        selected = form.type == TransactionType.INCOME,
                        onClick = { onTypeChange(TransactionType.INCOME) },
                        label = { Text(stringResource(R.string.income)) },
                    )
                }
                OutlinedTextField(
                    value = form.amountText,
                    onValueChange = onAmountChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.amount)) },
                    suffix = { Text(form.currencyCode) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = form.amountError != null,
                    supportingText = form.amountError?.let { error -> { Text(amountErrorMessage(error)) } },
                    singleLine = true,
                )
                PastDatePickerField(
                    value = form.dateText,
                    today = today,
                    label = stringResource(R.string.date_iso),
                    errorText = dateError,
                    onDateSelected = onDateChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.optional_category), style = MaterialTheme.typography.labelLarge)
                CategorySelector(categories, form.categoryId, onCategoryChange)
                OutlinedTextField(
                    value = form.descriptionText,
                    onValueChange = onDescriptionChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.description_optional)) },
                    minLines = 2,
                    maxLines = 4,
                    isError = form.descriptionTooLong,
                    supportingText = if (form.descriptionTooLong) {
                        { Text(stringResource(R.string.description_too_long)) }
                    } else null,
                )
                if (form.editingId == null && form.type == TransactionType.EXPENSE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.monthly_expense))
                            Text(stringResource(R.string.monthly_same_day_hint), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = form.repeatMonthly, onCheckedChange = onRepeatMonthlyChange)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onReview) { Text(stringResource(R.string.review)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Muestra una previsualización antes de crear el movimiento o la regla mensual.
 *
 * Entradas: formulario, progreso y callbacks.
 * Salida: confirmación explícita que evita escrituras accidentales.
 */
@Composable
private fun SaveConfirmationDialog(
    form: TransactionFormState,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (form.repeatMonthly) R.string.confirm_monthly_expense
                    else R.string.confirm_transaction_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.confirm_amount, form.amountText, form.currencyCode))
                Text(stringResource(R.string.confirm_date, form.dateText))
                form.descriptionText.trim().takeIf { it.isNotEmpty() }?.let {
                    Text(stringResource(R.string.confirm_description, it))
                }
                if (form.repeatMonthly) Text(stringResource(R.string.monthly_no_future_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isSaving) {
                Text(if (isSaving) stringResource(R.string.saving) else stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.back)) }
        },
    )
}

/**
 * Solicita confirmación antes de retirar un movimiento local.
 *
 * Entradas: callbacks de cancelación y confirmación.
 */
@Composable
private fun DeleteConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_transaction_title)) },
        text = { Text(stringResource(R.string.delete_transaction_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Traduce un error monetario tipado a un mensaje del formulario.
 *
 * Entrada: error del parser exacto.
 * Salida: texto en español.
 */
@Composable
private fun amountErrorMessage(error: AmountInputError): String = when (error) {
    AmountInputError.EMPTY -> stringResource(R.string.amount_empty)
    AmountInputError.INVALID_FORMAT -> stringResource(R.string.amount_invalid)
    AmountInputError.INVALID_CURRENCY -> stringResource(R.string.currency_invalid)
    AmountInputError.TOO_MANY_DECIMALS -> stringResource(R.string.amount_too_many_decimals)
    AmountInputError.NON_POSITIVE -> stringResource(R.string.amount_non_positive)
    AmountInputError.OVERFLOW -> stringResource(R.string.amount_too_large)
}

/**
 * Proporciona datos ficticios para inspeccionar la nueva fila monetaria.
 *
 * Salida: preview sin repositorios ni datos reales.
 */
@Preview(name = "Claro", showBackground = true)
@Preview(name = "Oscuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TransactionsScreenPreview() {
    LumaTheme {
        TransactionsScreen(
            state = TransactionsUiState(
                transactions = listOf(
                    Transaction(
                        id = "preview-1",
                        type = TransactionType.EXPENSE,
                        money = Money(1_250, "EUR"),
                        occurredAt = Instant.parse("2026-08-30T10:00:00Z"),
                        description = "Compra ficticia",
                    )
                ),
                totals = listOf(CurrencyTotals("EUR", 0, 1_250, -1_250)),
                runningBalances = mapOf("preview-1" to -1_250),
                form = TransactionFormState(dateText = "30/08/2026"),
                isLoading = false,
            ),
            onAdd = {}, onEdit = {}, onDelete = {}, onDismissForm = {},
            onTypeChange = {}, onAmountChange = {}, onDateChange = {},
            onDescriptionChange = {}, onCategoryChange = {},
            onRepeatMonthlyChange = {}, onReview = {}, onDismissSave = {},
            onConfirmSave = {}, onDismissDelete = {}, onConfirmDelete = {},
            onRecurrenceChange = { _, _ -> }, onDismissRecurrence = {},
            onConfirmRecurrence = {},
            onDismissError = {},
        )
    }
}
