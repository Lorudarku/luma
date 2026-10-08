package dev.okami.luma.domain.validation

import dev.okami.luma.domain.model.MAX_TRANSACTION_DESCRIPTION_LENGTH
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId

data class TransactionValidationErrors(
    val amount: AmountInputError? = null,
    val invalidDate: Boolean = false,
    val futureDate: Boolean = false,
    val descriptionTooLong: Boolean = false,
)

sealed interface TransactionValidationResult {
    data class Valid(val transaction: Transaction) : TransactionValidationResult

    data class Invalid(val errors: TransactionValidationErrors) : TransactionValidationResult
}

object TransactionValidator {
    /**
     * Valida los campos del formulario y construye una transacción inmutable si todos son válidos.
     *
     * Entradas: identificador, tipo, importe, moneda, fecha ISO, descripción y zona horaria.
     * Salida: resultado válido con [Transaction] o errores acumulados por campo.
     * Efectos: ninguno; no escribe en repositorios ni genera efectos Android.
     * Pasos: delega el importe, interpreta la fecha, normaliza la descripción, reúne errores y
     * construye el modelo con la fecha convertida a [java.time.Instant].
     */
    fun validate(
        id: String,
        type: TransactionType,
        amountText: String,
        currencyCode: String,
        dateText: String,
        descriptionText: String,
        zoneId: ZoneId,
        today: LocalDate = LocalDate.now(zoneId),
        categoryId: String? = null,
        recurringExpenseId: String? = null,
        recurrenceDate: LocalDate? = null,
    ): TransactionValidationResult {
        // 1. Delegar el importe al parser central mantiene una única política monetaria.
        val amountResult = MoneyInputParser.parse(amountText, currencyCode)
        val amountError = (amountResult as? AmountParseResult.Invalid)?.error
        // 2. Parsear la fecha de forma estricta evita guardar formatos ambiguos.
        val date = TransactionDateCodec.parse(dateText)
        val futureDate = date?.let { it > today } ?: false
        // 3. Normalizar texto vacío y medir el límite antes de crear el dominio.
        val normalizedDescription = descriptionText.trim().ifEmpty { null }
        val descriptionTooLong =
            normalizedDescription != null &&
                normalizedDescription.length > MAX_TRANSACTION_DESCRIPTION_LENGTH

        // 4. Devolver todos los errores juntos permite corregir el formulario de una vez.
        val errors = TransactionValidationErrors(
            amount = amountError,
            invalidDate = date == null,
            futureDate = futureDate,
            descriptionTooLong = descriptionTooLong,
        )
        if (amountError != null || date == null || futureDate || descriptionTooLong) {
            return TransactionValidationResult.Invalid(errors)
        }

        // 5. Solo tras pasar las validaciones se materializa la transacción persistible.
        val money = (amountResult as AmountParseResult.Valid).money
        return TransactionValidationResult.Valid(
            Transaction(
                id = id,
                type = type,
                money = money,
                occurredAt = date.atStartOfDay(zoneId).toInstant(),
                description = normalizedDescription,
                categoryId = categoryId,
                recurringExpenseId = recurringExpenseId,
                recurrenceDate = recurrenceDate,
            )
        )
    }
}
