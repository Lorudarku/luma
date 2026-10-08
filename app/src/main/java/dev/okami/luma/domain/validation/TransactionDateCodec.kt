package dev.okami.luma.domain.validation

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

object TransactionDateCodec {
    val MIN_DATE: LocalDate = LocalDate.of(1900, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(9999, 12, 31)
    private val formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    /**
     * Convierte una fecha de dominio al formato visible fijo de Luma.
     *
     * Entrada: fecha local válida.
     * Salida: texto `dd/MM/aaaa` independiente del locale del dispositivo.
     */
    fun format(date: LocalDate): String = formatter.format(date)

    /**
     * Interpreta estrictamente una fecha visible, rechazando días o meses inexistentes.
     *
     * Entrada: texto potencialmente no confiable.
     * Salida: fecha entre 1900 y 9999 o `null` cuando el formato/rango es inválido.
     */
    fun parse(text: String): LocalDate? = try {
        LocalDate.parse(text.trim(), formatter).takeIf { it in MIN_DATE..MAX_DATE }
    } catch (_: DateTimeParseException) {
        null
    }
}
