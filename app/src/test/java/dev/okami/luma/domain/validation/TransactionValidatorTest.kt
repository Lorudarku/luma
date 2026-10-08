package dev.okami.luma.domain.validation

import dev.okami.luma.domain.model.MAX_TRANSACTION_DESCRIPTION_LENGTH
import dev.okami.luma.domain.model.TransactionType
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionValidatorTest {
    @Test
    /**
     * Comprueba que una entrada válida genera una transacción normalizada.
     *
     * Preparación: combina espacios, minúsculas, coma decimal y fecha ISO.
     * Verificación: valida importe, moneda, instante UTC y descripción recortada.
     */
    fun `creates normalized transaction from valid input`() {
        val result = TransactionValidator.validate(
            id = "transaction-1",
            type = TransactionType.EXPENSE,
            amountText = " 12,50 ",
            currencyCode = "eur",
            dateText = "30/08/2026",
            descriptionText = "  Compra ficticia  ",
            zoneId = ZoneOffset.UTC,
        )

        val transaction = (result as TransactionValidationResult.Valid).transaction
        assertEquals(1250L, transaction.money.amountMinor)
        assertEquals("EUR", transaction.money.currencyCode)
        assertEquals(Instant.parse("2026-08-30T00:00:00Z"), transaction.occurredAt)
        assertEquals("Compra ficticia", transaction.description)
    }

    @Test
    /**
     * Comprueba que una descripción formada solo por espacios se guarda como null.
     *
     * Preparación: reutiliza una entrada válida con descripción en blanco.
     * Verificación: confirma que no se conserva texto insignificante.
     */
    fun `normalizes blank description to null`() {
        val result = validTransaction(description = "   ")

        assertNull((result as TransactionValidationResult.Valid).transaction.description)
    }

    @Test
    /**
     * Comprueba que se devuelven a la vez los errores de importe, fecha y descripción.
     *
     * Preparación: usa importe cero, fecha no ISO y descripción demasiado larga.
     * Verificación: inspecciona cada campo del resultado inválido.
     */
    fun `reports amount date and description errors together`() {
        val result = TransactionValidator.validate(
            id = "transaction-1",
            type = TransactionType.INCOME,
            amountText = "0",
            currencyCode = "EUR",
            dateText = "30-08-2026",
            descriptionText = "x".repeat(MAX_TRANSACTION_DESCRIPTION_LENGTH + 1),
            zoneId = ZoneOffset.UTC,
        )

        val errors = (result as TransactionValidationResult.Invalid).errors
        assertEquals(AmountInputError.NON_POSITIVE, errors.amount)
        assertTrue(errors.invalidDate)
        assertTrue(errors.descriptionTooLong)
    }

    /**
     * Comprueba que una fecha sintácticamente válida pero futura se rechaza por separado.
     *
     * Verificación: distingue el error temporal del error de formato.
     */
    @Test
    fun `rejects future dates`() {
        val result = TransactionValidator.validate(
            id = "transaction-1",
            type = TransactionType.EXPENSE,
            amountText = "10",
            currencyCode = "EUR",
            dateText = "31/08/2026",
            descriptionText = "",
            zoneId = ZoneOffset.UTC,
            today = java.time.LocalDate.of(2026, 8, 30),
        )

        val errors = (result as TransactionValidationResult.Invalid).errors
        assertTrue(errors.futureDate)
        assertTrue(!errors.invalidDate)
    }

    /**
     * Construye una validación válida mínima para los tests de normalización.
     *
     * Entrada: descripción ficticia a comprobar.
     * Salida: resultado de validar una transacción de ingreso en EUR.
     */
    private fun validTransaction(description: String): TransactionValidationResult =
        TransactionValidator.validate(
            id = "transaction-1",
            type = TransactionType.INCOME,
            amountText = "10",
            currencyCode = "EUR",
            dateText = "30/08/2026",
            descriptionText = description,
            zoneId = ZoneOffset.UTC,
        )
}
