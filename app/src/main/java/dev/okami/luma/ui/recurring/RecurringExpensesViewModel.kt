package dev.okami.luma.ui.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.RecurringExpense
import dev.okami.luma.domain.model.TransactionType
import dev.okami.luma.domain.repository.CategoryRepository
import dev.okami.luma.domain.repository.RecurringExpenseRepository
import dev.okami.luma.domain.validation.AmountInputError
import dev.okami.luma.domain.validation.TransactionDateCodec
import dev.okami.luma.domain.validation.TransactionValidationResult
import dev.okami.luma.domain.validation.TransactionValidator
import dev.okami.luma.ui.transactions.DEFAULT_CURRENCY_CODE
import dev.okami.luma.ui.transactions.formatMinorForInput
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecurringExpenseFormState(
    val editingId: String? = null,
    val amountText: String = "",
    val currencyCode: String = DEFAULT_CURRENCY_CODE,
    val startDateText: String,
    val descriptionText: String = "",
    val categoryId: String? = null,
    val amountError: AmountInputError? = null,
    val invalidDate: Boolean = false,
    val futureDate: Boolean = false,
    val descriptionTooLong: Boolean = false,
)

data class RecurringExpensesUiState(
    val rules: List<RecurringExpense> = emptyList(),
    val categories: List<Category> = emptyList(),
    val form: RecurringExpenseFormState,
    val today: LocalDate = LocalDate.now(),
    val isLoading: Boolean = true,
    val isFormVisible: Boolean = false,
    val isSaving: Boolean = false,
    val pendingDeactivateId: String? = null,
    val error: Boolean = false,
)

class RecurringExpensesViewModel(
    private val repository: RecurringExpenseRepository,
    categoryRepository: CategoryRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        RecurringExpensesUiState(
            form = emptyForm(),
            today = LocalDate.now(clock),
        )
    )
    val uiState: StateFlow<RecurringExpensesUiState> = _uiState.asStateFlow()

    private var draftId: String? = null

    init {
        observeRules()
        observeCategories(categoryRepository)
    }

    /**
     * Abre un formulario vacío para una regla mensual.
     *
     * Efectos: usa hoy como primera fecha posible y descarta borradores anteriores.
     */
    fun showAddForm() {
        if (_uiState.value.isSaving) return
        draftId = null
        _uiState.update { it.copy(form = emptyForm(), isFormVisible = true) }
    }

    /**
     * Carga una regla existente sin modificar sus ocurrencias históricas.
     *
     * Entrada: regla seleccionada.
     */
    fun edit(rule: RecurringExpense) {
        if (_uiState.value.isSaving) return
        draftId = null
        _uiState.update {
            it.copy(
                form = RecurringExpenseFormState(
                    editingId = rule.id,
                    amountText = formatMinorForInput(
                        rule.money.amountMinor,
                        rule.money.currencyCode,
                    ),
                    currencyCode = rule.money.currencyCode,
                    startDateText = TransactionDateCodec.format(rule.startDate),
                    descriptionText = rule.description.orEmpty(),
                    categoryId = rule.categoryId,
                ),
                isFormVisible = true,
            )
        }
    }

    /**
     * Cierra el editor sin persistir cambios.
     *
     * Efectos: restablece el formulario recurrente.
     */
    fun dismissForm() {
        if (_uiState.value.isSaving) return
        draftId = null
        _uiState.update { it.copy(form = emptyForm(), isFormVisible = false) }
    }

    /**
     * Actualiza el importe textual y limpia su error.
     *
     * Entrada: valor del campo.
     */
    fun updateAmount(value: String) = updateForm {
        copy(amountText = value, amountError = null)
    }

    /**
     * Actualiza la fecha inicial seleccionada por el calendario.
     *
     * Entrada: fecha visible `dd/MM/aaaa`.
     */
    fun updateStartDate(value: String) = updateForm {
        copy(startDateText = value, invalidDate = false, futureDate = false)
    }

    /**
     * Actualiza la descripción opcional de futuras ocurrencias.
     *
     * Entrada: texto actual.
     */
    fun updateDescription(value: String) = updateForm {
        copy(descriptionText = value, descriptionTooLong = false)
    }

    /**
     * Asigna o limpia la categoría opcional de la regla.
     *
     * Entrada: ID de categoría o `null`.
     */
    fun updateCategory(categoryId: String?) = updateForm {
        copy(categoryId = categoryId)
    }

    /**
     * Valida, persiste y genera las ocurrencias ya vencidas de la regla.
     *
     * Efectos: crea o actualiza la regla y nunca materializa fechas futuras.
     */
    fun save() {
        if (_uiState.value.isSaving) return
        val form = _uiState.value.form
        val validation = TransactionValidator.validate(
            id = form.editingId ?: draftId ?: idFactory().also { draftId = it },
            type = TransactionType.EXPENSE,
            amountText = form.amountText,
            currencyCode = form.currencyCode,
            dateText = form.startDateText,
            descriptionText = form.descriptionText,
            zoneId = clock.zone,
            today = LocalDate.now(clock),
            categoryId = form.categoryId,
        )
        if (validation is TransactionValidationResult.Invalid) {
            _uiState.update {
                it.copy(
                    form = form.copy(
                        amountError = validation.errors.amount,
                        invalidDate = validation.errors.invalidDate,
                        futureDate = validation.errors.futureDate,
                        descriptionTooLong = validation.errors.descriptionTooLong,
                    )
                )
            }
            return
        }

        val transaction = (validation as TransactionValidationResult.Valid).transaction
        val startDate = transaction.occurredAt.atZone(clock.zone).toLocalDate()
        val existing = _uiState.value.rules.firstOrNull { it.id == form.editingId }
        val rule = RecurringExpense(
            id = transaction.id,
            money = transaction.money,
            dayOfMonth = startDate.dayOfMonth,
            startDate = startDate,
            description = transaction.description,
            categoryId = transaction.categoryId,
            active = existing?.active ?: true,
        )
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                // 1. La regla y sus cuotas se confirman juntas o se revierten juntas.
                repository.saveAndGenerate(rule, LocalDate.now(clock), clock.zone)
                draftId = null
                _uiState.update { it.copy(form = emptyForm(), isFormVisible = false, error = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(error = true) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /**
     * Solicita confirmación antes de detener futuras ocurrencias.
     *
     * Entrada: identificador de regla activa.
     */
    fun requestDeactivate(id: String) {
        _uiState.update { it.copy(pendingDeactivateId = id) }
    }

    /**
     * Cancela la desactivación pendiente.
     *
     * Efectos: mantiene la regla activa.
     */
    fun dismissDeactivate() {
        _uiState.update { it.copy(pendingDeactivateId = null) }
    }

    /**
     * Desactiva la regla confirmada conservando su histórico.
     *
     * Efectos: actualiza la regla mediante una coroutine cancelable.
     */
    fun confirmDeactivate() {
        val id = _uiState.value.pendingDeactivateId ?: return
        viewModelScope.launch {
            try {
                repository.deactivate(id)
                _uiState.update { it.copy(pendingDeactivateId = null, error = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(pendingDeactivateId = null, error = true) }
            }
        }
    }

    /**
     * Oculta el mensaje de error recuperable.
     *
     * Efectos: cambia solo el estado visible.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = false) }
    }

    /**
     * Mantiene las reglas sincronizadas con Room.
     *
     * Efectos: publica la lista y completa la carga inicial.
     */
    private fun observeRules() {
        viewModelScope.launch {
            repository.observeRules()
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    _uiState.update { it.copy(isLoading = false, error = true) }
                }
                .collect { rules ->
                    _uiState.update { it.copy(rules = rules, isLoading = false) }
                }
        }
    }

    /**
     * Mantiene disponibles las categorías opcionales de cada regla.
     *
     * Entrada: repositorio local de categorías.
     */
    private fun observeCategories(categoryRepository: CategoryRepository) {
        viewModelScope.launch {
            categoryRepository.observeCategories()
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    _uiState.update { it.copy(error = true) }
                }
                .collect { categories ->
                    _uiState.update { it.copy(categories = categories) }
                }
        }
    }

    /**
     * Aplica una transformación inmutable al formulario recurrente.
     *
     * Entrada: transformación de estado.
     */
    private fun updateForm(
        transform: RecurringExpenseFormState.() -> RecurringExpenseFormState,
    ) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(form = it.form.transform()) }
    }

    /**
     * Crea el formulario inicial con la fecha de hoy.
     *
     * Salida: borrador recurrente vacío y determinista mediante [clock].
     */
    private fun emptyForm() = RecurringExpenseFormState(
        startDateText = TransactionDateCodec.format(LocalDate.now(clock)),
    )

    companion object {
        /**
         * Construye la factory manual del gestor de recurrencias.
         *
         * Entradas: repositorios de reglas y categorías.
         */
        fun factory(
            repository: RecurringExpenseRepository,
            categoryRepository: CategoryRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            /**
             * Crea el ViewModel solicitado por el ciclo de vida Android.
             *
             * Entrada: clase solicitada.
             * Salida: [RecurringExpensesViewModel].
             */
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RecurringExpensesViewModel::class.java))
                return RecurringExpensesViewModel(repository, categoryRepository) as T
            }
        }
    }
}
