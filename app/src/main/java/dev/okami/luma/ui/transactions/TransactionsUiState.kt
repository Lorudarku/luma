package dev.okami.luma.ui.transactions

import dev.okami.luma.domain.model.CurrencyTotals
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import dev.okami.luma.domain.model.RecurringExpense
import dev.okami.luma.domain.validation.AmountInputError

data class TransactionFormState(
    val editingId: String? = null,
    val type: TransactionType = TransactionType.EXPENSE,
    val amountText: String = "",
    val currencyCode: String = DEFAULT_CURRENCY_CODE,
    val dateText: String,
    val descriptionText: String = "",
    val categoryId: String? = null,
    val repeatMonthly: Boolean = false,
    val recurringExpenseId: String? = null,
    val recurrenceDate: java.time.LocalDate? = null,
    val amountError: AmountInputError? = null,
    val invalidDate: Boolean = false,
    val futureDate: Boolean = false,
    val descriptionTooLong: Boolean = false,
)

enum class TransactionsUiError {
    LOAD_FAILED,
    SAVE_FAILED,
    DELETE_FAILED,
    TOTALS_OVERFLOW,
    RECURRENCE_FAILED,
}

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val totals: List<CurrencyTotals> = emptyList(),
    val runningBalances: Map<String, Long> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val recurringRules: List<RecurringExpense> = emptyList(),
    val form: TransactionFormState,
    val today: java.time.LocalDate = java.time.LocalDate.now(),
    val isLoading: Boolean = true,
    val isFormVisible: Boolean = false,
    val isSaving: Boolean = false,
    val showSaveConfirmation: Boolean = false,
    val pendingDeleteId: String? = null,
    val pendingRecurrenceTransactionId: String? = null,
    val pendingRecurrenceEnabled: Boolean? = null,
    val isUpdatingRecurrence: Boolean = false,
    val error: TransactionsUiError? = null,
)

const val DEFAULT_CURRENCY_CODE = "EUR"
