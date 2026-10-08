package dev.okami.luma.ui.common

import dev.okami.luma.domain.validation.TransactionDateCodec
import java.math.BigDecimal
import java.math.RoundingMode
import dev.okami.luma.domain.model.currencyScale
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/**
 * Formatea unidades monetarias mínimas sin usar coma flotante.
 *
 * Entradas: cantidad firmada y código ISO.
 * Salida: texto localizado con símbolo y escala de la moneda.
 */
fun formatMoneyDisplay(amountMinor: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
    val currency = Currency.getInstance(currencyCode)
    val scale = currencyScale(currencyCode)
    val formatter = NumberFormat.getCurrencyInstance(locale).apply {
        this.currency = currency
        minimumFractionDigits = scale
        maximumFractionDigits = scale
        roundingMode = RoundingMode.UNNECESSARY
    }
    return formatter.format(BigDecimal.valueOf(amountMinor, scale))
}

/**
 * Convierte un instante persistido al formato fijo visible de Luma.
 *
 * Entradas: instante UTC y zona del dispositivo.
 * Salida: fecha `dd/MM/aaaa`.
 */
fun formatDateDisplay(
    instant: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = TransactionDateCodec.format(instant.atZone(zoneId).toLocalDate())

/**
 * Convierte una fecha local al convenio UTC usado por Material DatePicker.
 *
 * Entrada: fecha sin hora.
 * Salida: milisegundos del inicio de ese día en UTC.
 */
fun localDateToPickerMillis(date: LocalDate): Long =
    date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * Convierte la selección UTC de Material DatePicker a fecha local sin desplazamiento horario.
 *
 * Entrada: milisegundos UTC del selector.
 * Salida: fecha civil seleccionada.
 */
fun pickerMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
