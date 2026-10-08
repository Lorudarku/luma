package dev.okami.luma.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.okami.luma.domain.model.Category
import dev.okami.luma.R
import dev.okami.luma.domain.validation.TransactionDateCodec
import java.time.LocalDate
import java.util.Locale

/**
 * Muestra una fecha de solo lectura y abre un calendario limitado hasta hoy.
 *
 * Entradas: texto visible, fecha máxima, etiqueta, error y callback de selección.
 * Efectos: mantiene únicamente el estado transitorio del diálogo; no persiste información.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastDatePickerField(
    value: String,
    today: LocalDate,
    label: String,
    errorText: String?,
    onDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // El registro se desactiva también para los savers internos de listas y campos de Material.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        var showPicker by remember { mutableStateOf(false) }
        val initialDate = TransactionDateCodec.parse(value)?.coerceAtMost(today) ?: today
        val selectableDates = remember(today) {
            object : SelectableDates {
                /**
                 * Permite únicamente días no posteriores a hoy.
                 *
                 * Entrada: milisegundos UTC proporcionados por Material.
                 * Salida: `true` cuando la fecha es válida para una operación.
                 */
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    pickerMillisToLocalDate(utcTimeMillis) in TransactionDateCodec.MIN_DATE..today

                /**
                 * Evita navegar a años completamente posteriores al actual.
                 *
                 * Entrada: año mostrado por el calendario.
                 * Salida: disponibilidad del año.
                 */
                override fun isSelectableYear(year: Int): Boolean = year in TransactionDateCodec.MIN_DATE.year..today.year
            }
        }
        val locale = Locale.getDefault()
        val pickerState = remember(value, today, locale) {
            androidx.compose.material3.DatePickerState(
                locale = locale,
                initialSelectedDateMillis = localDateToPickerMillis(initialDate),
                yearRange = TransactionDateCodec.MIN_DATE.year..today.year,
                selectableDates = selectableDates,
            )
        }

        // 1. El campo no admite texto ambiguo; toda entrada procede del calendario validado.
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            modifier = modifier,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { showPicker = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar),
                        contentDescription = stringResource(R.string.open_calendar),
                    )
                }
            },
            isError = errorText != null,
            supportingText = errorText?.let { message -> { Text(message) } },
            singleLine = true,
        )

        if (showPicker) {
            // 2. Confirmar convierte UTC a fecha civil antes de actualizar el ViewModel.
            DatePickerDialog(
                onDismissRequest = { showPicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                onDateSelected(
                                    TransactionDateCodec.format(pickerMillisToLocalDate(millis))
                                )
                            }
                            showPicker = false
                        }
                    ) { Text(stringResource(R.string.accept)) }
                },
                dismissButton = {
                    TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) }
                },
            ) {
                DatePicker(state = pickerState)
            }
        }
    }
}

/**
 * Presenta categorías opcionales en una fila desplazable de chips.
 *
 * Entradas: catálogo, selección y callback.
 * Efectos: delega la elección sin modificar modelos por sí mismo.
 */
@Composable
fun CategorySelector(
    categories: List<Category>,
    selectedId: String?,
    onSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelected(null) },
            label = { Text(stringResource(R.string.no_category)) },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelected(category.id) },
                label = { Text("${category.emoji} ${category.name}") },
            )
        }
    }
}
