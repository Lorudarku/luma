package dev.okami.luma.ui.common

import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.currencyScale
import dev.okami.luma.domain.validation.AmountParseResult
import dev.okami.luma.domain.validation.MoneyInputParser
import dev.okami.luma.domain.validation.TransactionDateCodec
import dev.okami.luma.ui.transactions.formatMinorForInput
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DisplayFormattersTest {
    /** Monedas 0/2/3 decimales y límites firmados: el texto se interpreta al mismo entero, sin redondeos. */
    @Test fun exactDisplayAcrossCurrenciesLocalesAndLongLimits() {
        for (locale in listOf(Locale.forLanguageTag("es-ES"), Locale.US, Locale.JAPAN)) {
            for (code in listOf("JPY", "EUR", "BHD")) {
                val scale = currencyScale(code)
                val parser = (NumberFormat.getCurrencyInstance(locale) as DecimalFormat).apply {
                    currency = Currency.getInstance(code)
                    isParseBigDecimal = true
                }
                for (amount in listOf(0L, 1234L, -1234L, Long.MAX_VALUE, Long.MIN_VALUE)) {
                    val formatted = formatMoneyDisplay(amount, code, locale)
                    val parsed = parser.parse(formatted) as BigDecimal
                    assertEquals(BigDecimal.valueOf(amount, scale), parsed.setScale(scale))
                }
            }
        }
    }

    /** La pseudo moneda XXX se rechaza coherentemente en dominio, parser y editor. */
    @Test fun unsupportedCurrencyCannotCrossDomainBoundary() {
        assertThrows(IllegalArgumentException::class.java) { Money(1, "XXX") }
        assertTrue(MoneyInputParser.parse("1", "XXX") is AmountParseResult.Invalid)
        assertThrows(IllegalArgumentException::class.java) { formatMinorForInput(1, "XXX") }
    }

    /** Las fechas anteriores a la política de importación se rechazan también en el formulario. */
    @Test fun dateLowerBoundMatchesBackupPolicy() {
        assertNull(TransactionDateCodec.parse("31/12/1899"))
        assertEquals(TransactionDateCodec.MIN_DATE, TransactionDateCodec.parse("01/01/1900"))
    }
}
