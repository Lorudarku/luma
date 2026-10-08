@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.okami.luma.ui.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.okami.luma.R
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.RecurringExpense
import dev.okami.luma.domain.validation.AmountInputError
import dev.okami.luma.ui.common.CategorySelector
import dev.okami.luma.ui.common.PastDatePickerField
import dev.okami.luma.ui.common.formatMoneyDisplay
import java.time.LocalDate

/**
 * Recoge el estado de reglas mensuales y conecta sus acciones con Compose.
 *
 * Entrada: ViewModel de recurrencias.
 * Salida: pantalla lifecycle-aware.
 */
@Composable
fun RecurringExpensesRoute(
    viewModel: RecurringExpensesViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecurringExpensesScreen(
        state = state,
        onAdd = viewModel::showAddForm,
        onEdit = viewModel::edit,
        onDeactivate = { viewModel.requestDeactivate(it.id) },
        onDismissForm = viewModel::dismissForm,
        onAmountChange = viewModel::updateAmount,
        onDateChange = viewModel::updateStartDate,
        onDescriptionChange = viewModel::updateDescription,
        onCategoryChange = viewModel::updateCategory,
        onSave = viewModel::save,
        onDismissDeactivate = viewModel::dismissDeactivate,
        onConfirmDeactivate = viewModel::confirmDeactivate,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

/**
 * Renderiza reglas activas/inactivas y sus diálogos de gestión.
 *
 * Entradas: estado inmutable y callbacks.
 * Salida: superficie Material 3 sin acceso directo a Room.
 */
@Composable
fun RecurringExpensesScreen(
    state: RecurringExpensesUiState,
    onAdd: () -> Unit,
    onEdit: (RecurringExpense) -> Unit,
    onDeactivate: (RecurringExpense) -> Unit,
    onDismissForm: () -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onSave: () -> Unit,
    onDismissDeactivate: () -> Unit,
    onConfirmDeactivate: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.recurring_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd) { Text(stringResource(R.string.new_rule)) }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.error) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.recurring_save_error), modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.dismiss)) }
                    }
                }
            }
            when {
                state.isLoading -> CircularProgressIndicator()
                state.rules.isEmpty() -> Text(stringResource(R.string.recurring_empty))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rules, key = RecurringExpense::id) { rule ->
                        RecurringRuleRow(
                            rule = rule,
                            category = state.categories.firstOrNull { it.id == rule.categoryId },
                            onEdit = { onEdit(rule) },
                            onDeactivate = { onDeactivate(rule) },
                        )
                    }
                }
            }
        }
    }

    if (state.isFormVisible) {
        RecurringRuleFormDialog(
            form = state.form,
            categories = state.categories,
            today = state.today,
            isSaving = state.isSaving,
            onDismiss = onDismissForm,
            onAmountChange = onAmountChange,
            onDateChange = onDateChange,
            onDescriptionChange = onDescriptionChange,
            onCategoryChange = onCategoryChange,
            onSave = onSave,
        )
    }
    if (state.pendingDeactivateId != null) {
        AlertDialog(
            onDismissRequest = onDismissDeactivate,
            title = { Text(stringResource(R.string.deactivate_monthly_expense)) },
            text = { Text(stringResource(R.string.deactivate_rule_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDeactivate) { Text(stringResource(R.string.deactivate)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeactivate) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * Resume una regla mensual y expone sus acciones seguras.
 *
 * Entradas: regla, categoría resuelta y callbacks.
 * Salida: tarjeta de gestión.
 */
@Composable
private fun RecurringRuleRow(
    rule: RecurringExpense,
    category: Category?,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                rule.description ?: stringResource(R.string.monthly_expense),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(formatMoneyDisplay(-rule.money.amountMinor, rule.money.currencyCode))
            Text(stringResource(R.string.monthly_day, rule.dayOfMonth), style = MaterialTheme.typography.bodySmall)
            category?.let { Text("${it.emoji} ${it.name}", style = MaterialTheme.typography.bodySmall) }
            Text(
                stringResource(if (rule.active) R.string.active else R.string.inactive),
                style = MaterialTheme.typography.labelMedium,
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                if (rule.active) {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                    TextButton(onClick = onDeactivate) { Text(stringResource(R.string.deactivate)) }
                }
            }
        }
    }
}

/**
 * Edita una regla de gasto mensual con fecha, categoría y validación visibles.
 *
 * Entradas: formulario, catálogo y callbacks.
 * Salida: diálogo desplazable que delega el guardado al ViewModel.
 */
@Composable
private fun RecurringRuleFormDialog(
    form: RecurringExpenseFormState,
    categories: List<Category>,
    today: LocalDate,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onSave: () -> Unit,
) {
    val dateError = when {
        form.futureDate -> stringResource(R.string.future_date)
        form.invalidDate -> stringResource(R.string.invalid_date)
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (form.editingId == null) R.string.new_monthly_expense
                    else R.string.edit_monthly_expense
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = form.amountText,
                    onValueChange = onAmountChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.amount)) },
                    suffix = { Text(form.currencyCode) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = form.amountError != null,
                    supportingText = form.amountError?.let { error ->
                        { Text(recurringAmountError(error)) }
                    },
                    singleLine = true,
                )
                PastDatePickerField(
                    value = form.startDateText,
                    today = today,
                    label = stringResource(R.string.first_date),
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
                    isError = form.descriptionTooLong,
                    supportingText = if (form.descriptionTooLong) {
                        { Text(stringResource(R.string.description_too_long)) }
                    } else null,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !isSaving) { Text(stringResource(if (isSaving) R.string.saving else R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Traduce un error monetario de dominio a texto breve del formulario recurrente.
 *
 * Entrada: error tipado.
 * Salida: mensaje en español.
 */
@Composable
private fun recurringAmountError(error: AmountInputError): String = when (error) {
    AmountInputError.EMPTY -> stringResource(R.string.amount_empty)
    AmountInputError.INVALID_FORMAT -> stringResource(R.string.amount_invalid)
    AmountInputError.INVALID_CURRENCY -> stringResource(R.string.currency_invalid)
    AmountInputError.TOO_MANY_DECIMALS -> stringResource(R.string.amount_too_many_decimals)
    AmountInputError.NON_POSITIVE -> stringResource(R.string.amount_non_positive)
    AmountInputError.OVERFLOW -> stringResource(R.string.amount_too_large)
}
