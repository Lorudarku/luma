package dev.okami.luma.domain.model

import java.util.Currency
import java.util.Locale

data class Money(
    val amountMinor: Long,
    val currencyCode: String,
) {
    init {
        require(amountMinor > 0) { "Money must contain a positive amount." }
        require(currencyCode == currencyCode.uppercase(Locale.ROOT)) {
            "Currency code must be uppercase."
        }
        currencyScale(currencyCode)
    }
}

/** Valida un código ISO y devuelve su escala soportada (0..9); rechaza pseudo monedas sin escala. */
fun currencyScale(currencyCode: String): Int = Currency.getInstance(currencyCode).defaultFractionDigits.also {
    require(it in 0..9) { "Currency must have a supported fraction scale." }
}
