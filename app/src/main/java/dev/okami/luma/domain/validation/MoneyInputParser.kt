package dev.okami.luma.domain.validation

import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.currencyScale
import java.util.Locale

enum class AmountInputError {
    EMPTY,
    INVALID_FORMAT,
    INVALID_CURRENCY,
    TOO_MANY_DECIMALS,
    NON_POSITIVE,
    OVERFLOW,
}

sealed interface AmountParseResult {
    data class Valid(val money: Money) : AmountParseResult

    data class Invalid(val error: AmountInputError) : AmountParseResult
}

object MoneyInputParser {
    /**
     * Convierte texto monetario en [Money] usando unidades mínimas enteras.
     *
     * Entradas: texto del importe y código de moneda ISO potencialmente no normalizados.
     * Salida: [AmountParseResult.Valid] con un importe exacto o [AmountParseResult.Invalid].
     * Efectos: ninguno; la función es pura y no registra ni persiste la entrada.
     * Pasos: normaliza, valida moneda y formato, escala la parte fraccionaria, comprueba overflow
     * y finalmente construye el valor de dominio.
     */
    fun parse(rawAmount: String, rawCurrencyCode: String): AmountParseResult {
        // 1. Recortar la entrada permite tolerar espacios, pero no modifica el valor persistido.
        val amount = rawAmount.trim()
        if (amount.isEmpty()) return AmountParseResult.Invalid(AmountInputError.EMPTY)
        if (amount.startsWith('-')) {
            return AmountParseResult.Invalid(AmountInputError.NON_POSITIVE)
        }

        // 2. Resolver la moneda determina cuántos decimales son válidos.
        val currencyCode = rawCurrencyCode.trim().uppercase(Locale.ROOT)
        val fractionDigits = runCatching { currencyScale(currencyCode) }.getOrNull()
            ?: return AmountParseResult.Invalid(AmountInputError.INVALID_CURRENCY)

        // 3. Rechazar formatos ambiguos evita interpretar separadores de miles como decimales.
        val decimalSeparators = amount.count { it == ',' || it == '.' }
        if (decimalSeparators > 1 || amount.any { !it.isDigit() && it != ',' && it != '.' }) {
            return AmountParseResult.Invalid(AmountInputError.INVALID_FORMAT)
        }

        val separatorIndex = amount.indexOfFirst { it == ',' || it == '.' }
        val majorPart = if (separatorIndex >= 0) amount.substring(0, separatorIndex) else amount
        val fractionPart = if (separatorIndex >= 0) amount.substring(separatorIndex + 1) else ""
        if (majorPart.isEmpty() || !majorPart.all(Char::isDigit) || !fractionPart.all(Char::isDigit)) {
            return AmountParseResult.Invalid(AmountInputError.INVALID_FORMAT)
        }
        if (fractionPart.length > fractionDigits) {
            return AmountParseResult.Invalid(AmountInputError.TOO_MANY_DECIMALS)
        }

        // 4. Convertir cada parte como entero mantiene precisión y detecta overflow.
        val major = majorPart.toLongOrNull()
            ?: return AmountParseResult.Invalid(AmountInputError.OVERFLOW)
        val scale = decimalScale(fractionDigits)
            ?: return AmountParseResult.Invalid(AmountInputError.OVERFLOW)
        val paddedFraction = fractionPart.padEnd(fractionDigits, '0')
        val fraction = paddedFraction.toLongOrNull() ?: 0L
        val amountMinor = try {
            Math.addExact(Math.multiplyExact(major, scale), fraction)
        } catch (_: ArithmeticException) {
            return AmountParseResult.Invalid(AmountInputError.OVERFLOW)
        }

        // 5. La cantidad debe ser positiva antes de cruzar la frontera del dominio.
        if (amountMinor <= 0) {
            return AmountParseResult.Invalid(AmountInputError.NON_POSITIVE)
        }
        return AmountParseResult.Valid(Money(amountMinor, currencyCode))
    }

    /**
     * Obtiene una potencia decimal comprobando el límite de [Long].
     *
     * Entrada: número de posiciones fraccionarias requerido por la moneda.
     * Salida: `10^fractionDigits` o `null` si no es representable.
     * Efectos: ninguno; solo realiza multiplicaciones exactas.
     */
    private fun decimalScale(fractionDigits: Int): Long? = try {
        // Multiplicar paso a paso permite detectar overflow con la API estándar.
        var scale = 1L
        repeat(fractionDigits) {
            scale = Math.multiplyExact(scale, 10L)
        }
        scale
    } catch (_: ArithmeticException) {
        null
    }
}
