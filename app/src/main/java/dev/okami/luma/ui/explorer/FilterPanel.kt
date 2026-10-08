@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.okami.luma.ui.explorer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.okami.luma.R
import dev.okami.luma.domain.model.*
import dev.okami.luma.ui.common.PastDatePickerField
import dev.okami.luma.domain.validation.TransactionDateCodec
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** Barra común: muestra contexto activo y permite editar una copia del filtro antes de aplicarlo. */
@Composable
fun FilterBar(viewModel: ExplorerViewModel) {
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.explore_filters)) }
        TextButton(onClick = viewModel::clear) { Text(stringResource(R.string.explore_clear)) }
    }
    Text(
        "${filter.start?.let(TransactionDateCodec::format) ?: stringResource(R.string.explore_all)} — ${filter.end?.let(TransactionDateCodec::format) ?: TransactionDateCodec.format(viewModel.today)} · ${filter.currencies.joinToString().ifEmpty { stringResource(R.string.explore_all_currencies) }}",
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
    )
    if (filter.isSelection) Text(stringResource(R.string.explore_filtered), modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        filter.types.forEach { type -> InputChip(true, { viewModel.apply(filter.copy(types = filter.types - type)) },
            label = { Text(stringResource(if (type == TransactionType.EXPENSE) R.string.expenses else R.string.income)) }) }
        filter.categoryIds.forEach { id -> InputChip(true, { viewModel.apply(filter.copy(categoryIds = filter.categoryIds - id)) },
            label = { Text(catalog.firstOrNull { it.id == id }?.name ?: stringResource(R.string.no_category)) }) }
        if (filter.uncategorized) InputChip(true, { viewModel.apply(filter.copy(uncategorized = false)) }, label = { Text(stringResource(R.string.no_category)) })
        if (filter.text.isNotEmpty()) InputChip(true, { viewModel.apply(filter.copy(text = "")) }, label = { Text(filter.text) })
        if (filter.minimum != null || filter.maximum != null) InputChip(true, { viewModel.apply(filter.copy(minimum = null, maximum = null)) },
            label = { Text("${filter.minimum?.let { minorInput(it, filter.currencies.single()) }.orEmpty()} — ${filter.maximum?.let { minorInput(it, filter.currencies.single()) }.orEmpty()} ${filter.currencies.single()}") })
        if (filter.recurrence != RecurrenceFilter.ALL) InputChip(true, { viewModel.apply(filter.copy(recurrence = RecurrenceFilter.ALL)) }, label = { Text(stringResource(recurrenceLabel(filter.recurrence))) })
        if (filter.description != DescriptionFilter.ALL) InputChip(true, { viewModel.apply(filter.copy(description = DescriptionFilter.ALL)) }, label = { Text(stringResource(descriptionLabel(filter.description))) })
    }
    if (error) TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.explore_error), color = MaterialTheme.colorScheme.error) }
    if (expanded) FilterDialog(viewModel, onDismiss = { expanded = false })
}

/** Presenta todos los criterios en un diálogo desplazable; los borradores no sobreviven al bloqueo. */
@Composable
private fun FilterDialog(viewModel: ExplorerViewModel, onDismiss: () -> Unit) {
    val current by viewModel.filter.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val currencies by viewModel.currencies.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(current) }
    var start by remember { mutableStateOf(current.start?.let(TransactionDateCodec::format).orEmpty()) }
    var end by remember { mutableStateOf(current.end?.let(TransactionDateCodec::format).orEmpty()) }
    var minimum by remember { mutableStateOf(current.minimum?.let { minorInput(it, current.currencies.single()) }.orEmpty()) }
    var maximum by remember { mutableStateOf(current.maximum?.let { minorInput(it, current.currencies.single()) }.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explore_filters)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(R.string.explore_week, R.string.explore_month, R.string.explore_year, R.string.explore_30, R.string.explore_90, R.string.explore_all).forEachIndexed { index, label ->
                        AssistChip(onClick = {
                            val today = viewModel.today
                            draft = draft.copy(period = when (index) { 0 -> StatisticsPeriod.WEEK; 1 -> StatisticsPeriod.MONTH; 2 -> StatisticsPeriod.YEAR; else -> null })
                            start = when (index) {
                                0 -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                                1 -> today.withDayOfMonth(1)
                                2 -> today.withDayOfYear(1)
                                3 -> today.minusDays(29)
                                4 -> today.minusDays(89)
                                else -> null
                            }?.let(TransactionDateCodec::format).orEmpty()
                            end = TransactionDateCodec.format(today)
                        }, label = { Text(stringResource(label)) })
                    }
                }
                Text(stringResource(R.string.explore_from))
                PastDatePickerField(value = start, onDateSelected = { start = it; draft = draft.copy(period = null) }, today = viewModel.today, label = stringResource(R.string.explore_from), errorText = null)
                TextButton(onClick = { start = "" }) { Text(stringResource(R.string.explore_no_start)) }
                Text(stringResource(R.string.explore_to))
                PastDatePickerField(value = end, onDateSelected = { end = it; draft = draft.copy(period = null) }, today = viewModel.today, label = stringResource(R.string.explore_to), errorText = null)
                OutlinedTextField(draft.text, { draft = draft.copy(text = it.take(200)) }, label = { Text(stringResource(R.string.explore_search)) }, singleLine = true)
                Text(stringResource(R.string.explore_type), style = MaterialTheme.typography.titleSmall)
                FlowRow {
                    TransactionType.entries.forEach { type -> FilterChip(selected = type in draft.types,
                        onClick = { draft = draft.copy(types = toggle(draft.types, type)) },
                        label = { Text(stringResource(if (type == TransactionType.EXPENSE) R.string.expenses else R.string.income)) }) }
                }
                Text(stringResource(R.string.explore_currency), style = MaterialTheme.typography.titleSmall)
                FlowRow {
                    currencies.forEach { currency -> FilterChip(currency in draft.currencies, onClick = {
                        draft = draft.copy(currencies = toggle(draft.currencies, currency), minimum = null, maximum = null, order = TransactionOrder.NEWEST)
                        minimum = ""; maximum = ""
                    }, label = { Text(currency) }) }
                }
                Text(stringResource(R.string.categories_title), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(draft.uncategorized, { draft = draft.copy(uncategorized = !draft.uncategorized) }, label = { Text(stringResource(R.string.explore_uncategorized)) })
                    catalog.forEach { category -> FilterChip(category.id in draft.categoryIds,
                        { draft = draft.copy(categoryIds = toggle(draft.categoryIds, category.id)) },
                        label = { Text("${category.emoji} ${category.name}") }) }
                }
                Text(stringResource(R.string.explore_amount_hint))
                OutlinedTextField(minimum, { minimum = it }, enabled = draft.currencies.size == 1,
                    label = { Text(stringResource(R.string.explore_minimum)) }, singleLine = true)
                OutlinedTextField(maximum, { maximum = it }, enabled = draft.currencies.size == 1,
                    label = { Text(stringResource(R.string.explore_maximum)) }, singleLine = true)
                Text(stringResource(R.string.monthly_expense), style = MaterialTheme.typography.titleSmall)
                FlowRow {
                    RecurrenceFilter.entries.forEach { value -> FilterChip(value == draft.recurrence,
                        { draft = draft.copy(recurrence = value) }, label = { Text(stringResource(recurrenceLabel(value))) }) }
                }
                Text(stringResource(R.string.explore_description), style = MaterialTheme.typography.titleSmall)
                FlowRow {
                    DescriptionFilter.entries.forEach { value -> FilterChip(value == draft.description,
                        { draft = draft.copy(description = value) }, label = { Text(stringResource(descriptionLabel(value))) }) }
                }
                Text(stringResource(R.string.explore_order), style = MaterialTheme.typography.titleSmall)
                FlowRow {
                    TransactionOrder.entries.forEach { value -> FilterChip(value == draft.order,
                        onClick = { draft = draft.copy(order = value) },
                        enabled = value in setOf(TransactionOrder.NEWEST, TransactionOrder.OLDEST) || draft.currencies.size == 1,
                        label = { Text(stringResource(orderLabel(value))) }) }
                }
                OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.explore_save_name)) })
                saved.forEach { entry -> Row {
                    TextButton(onClick = { viewModel.load(entry); onDismiss() }, modifier = Modifier.weight(1f)) { Text(entry.name) }
                    TextButton(onClick = { viewModel.delete(entry.name) }) { Text(stringResource(R.string.delete)) }
                } }
                if (error) Text(stringResource(R.string.explore_invalid), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            error = !viewModel.applyDraft(draft, start, end, minimum, maximum)
            if (!error) { if (name.isNotBlank()) viewModel.save(name); onDismiss() }
        }) { Text(stringResource(R.string.explore_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Alterna una selección visual sin mutar el conjunto compartido. */
private fun <T> toggle(values: Set<T>, value: T): Set<T> = if (value in values) values - value else values + value

/** Formatea un límite exacto editable según la escala ISO de su moneda. */
private fun minorInput(minor: Long, currency: String): String = java.math.BigDecimal.valueOf(minor, java.util.Currency.getInstance(currency).defaultFractionDigits).toPlainString()

/** Resuelve etiqueta localizada de recurrencia. */
private fun recurrenceLabel(value: RecurrenceFilter): Int = when (value) {
    RecurrenceFilter.ALL -> R.string.explore_all
    RecurrenceFilter.NONE -> R.string.explore_non_recurring
    RecurrenceFilter.LINKED -> R.string.explore_recurring
    RecurrenceFilter.ACTIVE -> R.string.explore_active
    RecurrenceFilter.INACTIVE -> R.string.explore_inactive
}

/** Resuelve etiqueta localizada para presencia de descripción. */
private fun descriptionLabel(value: DescriptionFilter): Int = when (value) {
    DescriptionFilter.ALL -> R.string.explore_all
    DescriptionFilter.PRESENT -> R.string.explore_present
    DescriptionFilter.EMPTY -> R.string.explore_empty
}

/** Resuelve etiqueta localizada de ordenación. */
private fun orderLabel(value: TransactionOrder): Int = when (value) {
    TransactionOrder.NEWEST -> R.string.explore_newest
    TransactionOrder.OLDEST -> R.string.explore_oldest
    TransactionOrder.LARGEST -> R.string.explore_largest
    TransactionOrder.SMALLEST -> R.string.explore_smallest
}
