package dev.okami.luma.domain.model

import java.time.Instant
import java.time.LocalDate

const val MAX_TRANSACTION_DESCRIPTION_LENGTH = 120

enum class TransactionType {
    INCOME,
    EXPENSE,
}

data class Transaction(
    val id: String,
    val type: TransactionType,
    val money: Money,
    val occurredAt: Instant,
    val description: String?,
    val categoryId: String? = null,
    val recurringExpenseId: String? = null,
    val recurrenceDate: LocalDate? = null,
) {
    init {
        require(id.isNotBlank()) { "Transaction id must not be blank." }
        require(
            description == null ||
                (description.isNotBlank() &&
                    description == description.trim() &&
                    description.length <= MAX_TRANSACTION_DESCRIPTION_LENGTH)
        ) {
            "Description must be trimmed, non-blank, and within the length limit."
        }
        require(categoryId == null || categoryId.isNotBlank()) {
            "Category id must be null or non-blank."
        }
        require((recurringExpenseId == null) == (recurrenceDate == null)) {
            "Recurring expense id and recurrence date must both be present or absent."
        }
        require(recurringExpenseId == null || (recurringExpenseId.isNotBlank() && type == TransactionType.EXPENSE)) {
            "Only expenses can represent a recurring occurrence."
        }
    }
}
