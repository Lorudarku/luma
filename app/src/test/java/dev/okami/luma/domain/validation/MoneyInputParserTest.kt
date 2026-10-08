package dev.okami.luma.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyInputParserTest {
    @Test
    /**
     * Comprueba que la coma decimal se convierte a unidades menores sin perder precisión.
     *
     * Preparación: analiza un importe ficticio con dos decimales y EUR.
     * Verificación: comprueba el valor entero almacenado y la moneda normalizada.
     */
    fun `parses comma decimal amount exactly`() {
        val result = MoneyInputParser.parse("120,50", "EUR")

        assertEquals(12050L, (result as AmountParseResult.Valid).money.amountMinor)
        assertEquals("EUR", result.money.currencyCode)
    }

    @Test
    /**
     * Comprueba los formatos entero y de un céntimo.
     *
     * Preparación: analiza un importe entero y otro con un decimal mínimo.
     * Verificación: valida sus cantidades expresadas en unidades menores.
     */
    fun `parses integer and one cent`() {
        assertEquals(
            12000L,
            (MoneyInputParser.parse("120", "EUR") as AmountParseResult.Valid).money.amountMinor,
        )
        assertEquals(
            1L,
            (MoneyInputParser.parse("0.01", "EUR") as AmountParseResult.Valid).money.amountMinor,
        )
    }

    @Test
    /**
     * Comprueba que el código de moneda se limpia y se convierte a mayúsculas.
     *
     * Preparación: proporciona espacios y minúsculas alrededor de EUR.
     * Verificación: espera el código ISO normalizado.
     */
    fun `normalizes currency code`() {
        val result = MoneyInputParser.parse("1", " eur ") as AmountParseResult.Valid

        assertEquals("EUR", result.money.currencyCode)
    }

    @Test
    /**
     * Comprueba el rechazo de entradas vacías, cero y negativas.
     *
     * Preparación: analiza las tres entradas inválidas.
     * Verificación: espera el error específico de cada caso.
     */
    fun `rejects empty zero and negative amounts`() {
        assertInvalid("", AmountInputError.EMPTY)
        assertInvalid("0", AmountInputError.NON_POSITIVE)
        assertInvalid("-1", AmountInputError.NON_POSITIVE)
    }

    @Test
    /**
     * Comprueba que separadores mezclados, texto y exceso de decimales no son ambiguos.
     *
     * Preparación: analiza varios formatos no permitidos.
     * Verificación: distingue formato inválido de demasiados decimales.
     */
    fun `rejects invalid separators characters and excess decimals`() {
        assertInvalid("1,2.3", AmountInputError.INVALID_FORMAT)
        assertInvalid("1 EUR", AmountInputError.INVALID_FORMAT)
        assertInvalid(".50", AmountInputError.INVALID_FORMAT)
        assertInvalid("1,234", AmountInputError.TOO_MANY_DECIMALS)
    }

    @Test
    /**
     * Comprueba que solo se aceptan códigos de moneda de tres letras.
     *
     * Preparación: analiza un código ficticio que no cumple la regla ISO básica.
     * Verificación: espera AmountInputError.INVALID_CURRENCY.
     */
    fun `rejects invalid currency`() {
        val result = MoneyInputParser.parse("1", "NOT")

        assertEquals(
            AmountInputError.INVALID_CURRENCY,
            (result as AmountParseResult.Invalid).error,
        )
    }

    @Test
    /**
     * Comprueba que el parser detecta overflow al convertir la parte entera a Long.
     *
     * Preparación: usa Long.MAX_VALUE como texto de unidades principales.
     * Verificación: espera el error de overflow tras aplicar la escala monetaria.
     */
    fun `detects long overflow`() {
        val result = MoneyInputParser.parse(Long.MAX_VALUE.toString(), "EUR")

        assertEquals(AmountInputError.OVERFLOW, (result as AmountParseResult.Invalid).error)
    }

    @Test
    /**
     * Comprueba el límite positivo máximo que cabe en unidades menores de Long.
     *
     * Preparación: analiza el máximo de céntimos representable en EUR.
     * Verificación: confirma que resulta válido y conserva Long.MAX_VALUE.
     */
    fun `accepts maximum representable euro amount`() {
        val result = MoneyInputParser.parse("92233720368547758.07", "EUR")

        assertTrue(result is AmountParseResult.Valid)
        assertEquals(Long.MAX_VALUE, (result as AmountParseResult.Valid).money.amountMinor)
    }

    /**
     * Reutiliza la aserción común para un resultado inválido del parser.
     *
     * Entrada: texto de importe y error esperado.
     * Verificación: analiza en EUR y compara la variante de error devuelta.
     */
    private fun assertInvalid(input: String, expected: AmountInputError) {
        val result = MoneyInputParser.parse(input, "EUR")
        assertEquals(expected, (result as AmountParseResult.Invalid).error)
    }
}
