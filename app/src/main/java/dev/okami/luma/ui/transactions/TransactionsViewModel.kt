package dev.okami.luma.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import dev.okami.luma.domain.model.calculateRunningBalances
import dev.okami.luma.domain.model.calculateTransactionTotals
import dev.okami.luma.domain.repository.CategoryRepository
import dev.okami.luma.domain.repository.RecurringExpenseRepository
import dev.okami.luma.domain.repository.TransactionRepository
import dev.okami.luma.domain.validation.TransactionValidationResult
import dev.okami.luma.domain.validation.TransactionValidator
import java.time.Clock
import java.time.LocalDate
import dev.okami.luma.domain.validation.TransactionDateCodec
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TransactionsViewModel(
    private val repository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val paged: Boolean = false,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        TransactionsUiState(
            form = emptyForm(),
            today = LocalDate.now(clock),
        )
    )
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private var pendingTransaction: Transaction? = null
    private var pendingRecurrence: Transaction? = null
    private var pendingTransactionIsEdit = false
    private var pendingTransactionIsRecurringRule = false

    init {
        initializeData()
        observeCategories()
        observeRecurringRules()
    }

    /**
     * Abre un formulario vacío para crear una transacción nueva.
     *
     * Efectos: descarta borradores y publica el estado visible con la fecha actual del reloj.
     * Pasos: limpia la transacción pendiente, crea campos iniciales y oculta confirmaciones previas.
     */
    fun showAddForm() {
        // 1. Un alta nunca debe reutilizar una confirmación de otra transacción.
        pendingTransaction = null
        _uiState.update {
            it.copy(
                form = emptyForm(),
                isFormVisible = true,
                showSaveConfirmation = false,
            )
        }
    }

    /**
     * Carga una transacción existente en el formulario de edición.
     *
     * Entrada: transacción seleccionada desde la lista.
     * Efectos: convierte importe y fecha a texto editable y conserva su identificador.
     * Pasos: marca la edición, copia valores de dominio y muestra el formulario.
     */
    fun edit(transaction: Transaction) {
        // 1. El ID permite que la confirmación termine en update en lugar de add.
        pendingTransaction = null
        _uiState.update {
            it.copy(
                form = TransactionFormState(
                    editingId = transaction.id,
                    type = transaction.type,
                    amountText = formatMinorForInput(
                        transaction.money.amountMinor,
                        transaction.money.currencyCode,
                    ),
                    currencyCode = transaction.money.currencyCode,
                    dateText = TransactionDateCodec.format(
                        transaction.occurredAt.atZone(clock.zone).toLocalDate()
                    ),
                    descriptionText = transaction.description.orEmpty(),
                    categoryId = transaction.categoryId,
                    recurringExpenseId = transaction.recurringExpenseId,
                    recurrenceDate = transaction.recurrenceDate,
                ),
                isFormVisible = true,
                showSaveConfirmation = false,
            )
        }
    }

    /**
     * Cierra el formulario y descarta cualquier borrador sin persistir.
     *
     * Efectos: restablece campos, oculta el formulario y elimina confirmaciones pendientes.
     */
    fun dismissForm() {
        // Limpiar aquí evita que datos temporales reaparezcan al abrir el formulario.
        pendingTransaction = null
        _uiState.update {
            it.copy(
                form = emptyForm(),
                isFormVisible = false,
                showSaveConfirmation = false,
            )
        }
    }

    /**
     * Cambia el tipo de movimiento manteniendo el resto del formulario.
     *
     * Entrada: [TransactionType] elegido por el usuario.
     * Efectos: al pasar a ingreso elimina el vínculo de cuota; el repositorio conserva su mes tratado.
     */
    fun updateType(type: TransactionType) = updateForm {
        copy(
            type = type,
            repeatMonthly = repeatMonthly && type == TransactionType.EXPENSE,
            recurringExpenseId = recurringExpenseId.takeIf { type == TransactionType.EXPENSE },
            recurrenceDate = recurrenceDate.takeIf { type == TransactionType.EXPENSE },
        )
    }

    /**
     * Actualiza el texto de cantidad sin parsearlo ni persistirlo todavía.
     *
     * Entrada: texto actual del campo.
     * Efectos: guarda el texto y limpia el error anterior para permitir otra validación.
     */
    fun updateAmount(amount: String) = updateForm {
        copy(amountText = amount, amountError = null)
    }

    /**
     * Actualiza la fecha textual y limpia su error anterior.
     *
     * Entrada: texto introducido en formato ISO esperado.
     * Efectos: cambia solo el estado del formulario; valida al revisar.
     */
    fun updateDate(date: String) = updateForm {
        copy(dateText = date, invalidDate = false, futureDate = false)
    }

    /**
     * Actualiza la descripción del movimiento sin tocar persistencia.
     *
     * Entrada: texto actual de descripción.
     * Efectos: guarda el valor y permite recalcular su límite al revisar.
     */
    fun updateDescription(description: String) = updateForm {
        copy(descriptionText = description, descriptionTooLong = false)
    }

    /**
     * Selecciona o elimina la categoría opcional del borrador.
     *
     * Entrada: identificador de categoría o `null`.
     * Efectos: actualiza únicamente el formulario observable.
     */
    fun updateCategory(categoryId: String?) = updateForm {
        copy(categoryId = categoryId)
    }

    /**
     * Activa la creación de una regla mensual para un gasto nuevo.
     *
     * Entrada: valor del interruptor.
     * Efectos: solo permite recurrencia en altas de tipo gasto.
     */
    fun updateRepeatMonthly(enabled: Boolean) = updateForm {
        copy(repeatMonthly = enabled && editingId == null && type == TransactionType.EXPENSE)
    }

    /**
     * Valida el formulario y deja la transacción tipada pendiente de confirmación.
     *
     * Efectos: nunca escribe en Room; publica errores de campo o abre la confirmación.
     * Pasos: toma una instantánea, delega toda la validación, refleja errores o guarda el borrador
     * temporal junto con si la operación es un alta o una edición.
     */
    fun reviewTransaction() {
        // 1. Una instantánea evita mezclar cambios mientras se ejecuta la validación.
        val form = _uiState.value.form
        val validation = TransactionValidator.validate(
            id = form.editingId ?: idFactory(),
            type = form.type,
            amountText = form.amountText,
            currencyCode = form.currencyCode,
            dateText = form.dateText,
            descriptionText = form.descriptionText,
            zoneId = clock.zone,
            today = LocalDate.now(clock),
            categoryId = form.categoryId,
            recurringExpenseId = form.recurringExpenseId,
            recurrenceDate = form.recurrenceDate,
        )
        when (validation) {
            is TransactionValidationResult.Invalid -> {
                // 2. Todos los errores se publican juntos para que el usuario los corrija.
                pendingTransaction = null
                _uiState.update {
                    it.copy(
                        form = form.copy(
                            amountError = validation.errors.amount,
                            invalidDate = validation.errors.invalidDate,
                            futureDate = validation.errors.futureDate,
                            descriptionTooLong = validation.errors.descriptionTooLong,
                        ),
                        showSaveConfirmation = false,
                    )
                }
            }

            is TransactionValidationResult.Valid -> {
                // 3. El modelo validado permanece fuera de Room hasta la confirmación explícita.
                pendingTransaction = validation.transaction
                pendingTransactionIsEdit = form.editingId != null
                pendingTransactionIsRecurringRule = form.repeatMonthly
                _uiState.update { it.copy(showSaveConfirmation = true) }
            }
        }
    }
    /**
     * Cancela la confirmación de guardado sin modificar datos locales.
     *
     * Efectos: descarta el borrador pendiente y vuelve al formulario editable.
     */
    fun dismissSaveConfirmation() {
        pendingTransaction = null
        _uiState.update { it.copy(showSaveConfirmation = false) }
    }

    /**
     * Persiste el borrador revisado como alta o actualización.
     *
     * Efectos: ejecuta una operación suspendida, publica progreso y traduce fallos recuperables.
     * Pasos: comprueba el borrador, evita doble envío, llama al repositorio, limpia al tener éxito,
     * propaga cancelación y restaura el indicador en `finally`.
     */
    fun confirmSave() {
        if (_uiState.value.isSaving) return
        val transaction = pendingTransaction ?: return
        // 1. La protección se activa antes de lanzar la coroutine, también frente a dos clics inmediatos.
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                // 2. El estado de edición decide el método del repositorio.
                if (pendingTransactionIsRecurringRule) {
                    // 2. La operación atómica enlaza la primera fila antes de generar vencimientos.
                    recurringExpenseRepository.setMonthlyRecurrence(
                        transaction = transaction,
                        enabled = true,
                        throughDate = LocalDate.now(clock),
                        zoneId = clock.zone,
                    )
                } else if (pendingTransactionIsEdit) {
                    repository.update(transaction)
                } else {
                    repository.add(transaction)
                }
                pendingTransaction = null
                // 3. Un éxito cierra el diálogo y deja un formulario nuevo.
                _uiState.update {
                    it.copy(
                        form = emptyForm(),
                        isFormVisible = false,
                        showSaveConfirmation = false,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                // 4. La cancelación de ciclo de vida no es un error de negocio.
                throw error
            } catch (_: Exception) {
                // 5. El error no filtra datos sensibles y conserva el formulario para reintento.
                _uiState.update {
                    it.copy(
                        showSaveConfirmation = false,
                        error = TransactionsUiError.SAVE_FAILED,
                    )
                }
            } finally {
                // 6. El indicador siempre vuelve a false, incluso tras error o cancelación.
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /**
     * Prepara el cambio de periodicidad solicitado desde una fila de gasto.
     *
     * Entradas: transacción y nuevo estado de la casilla.
     * Efectos: abre una confirmación sin modificar todavía la base de datos.
     */
    fun requestRecurrenceChange(transaction: Transaction, enabled: Boolean) {
        if (transaction.type != TransactionType.EXPENSE || isRecurrenceActive(transaction) == enabled) {
            return
        }
        pendingRecurrence = transaction
        _uiState.update {
            it.copy(
                pendingRecurrenceTransactionId = transaction.id,
                pendingRecurrenceEnabled = enabled,
            )
        }
    }

    /**
     * Cancela el cambio mensual pendiente sin modificar reglas ni movimientos.
     */
    fun dismissRecurrenceConfirmation() {
        pendingRecurrence = null
        _uiState.update {
            it.copy(pendingRecurrenceTransactionId = null, pendingRecurrenceEnabled = null)
        }
    }

    /**
     * Confirma la activación o desactivación mensual de una fila de gasto.
     *
     * Efectos: delega la escritura atómica, conserva cancelación y publica un error recuperable.
     */
    fun confirmRecurrenceChange() {
        val state = _uiState.value
        val transaction = pendingRecurrence?.takeIf { it.id == state.pendingRecurrenceTransactionId } ?: return
        val enabled = state.pendingRecurrenceEnabled ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingRecurrence = true) }
            try {
                // 1. El repositorio enlaza la fila, regla y vencimientos en una sola transacción.
                recurringExpenseRepository.setMonthlyRecurrence(
                    transaction = transaction,
                    enabled = enabled,
                    throughDate = LocalDate.now(clock),
                    zoneId = clock.zone,
                )
                _uiState.update {
                    it.copy(
                        pendingRecurrenceTransactionId = null,
                        pendingRecurrenceEnabled = null,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 2. La fila permanece intacta y el usuario puede reintentar.
                _uiState.update {
                    it.copy(
                        pendingRecurrenceTransactionId = null,
                        pendingRecurrenceEnabled = null,
                        error = TransactionsUiError.RECURRENCE_FAILED,
                    )
                }
            } finally {
                _uiState.update { it.copy(isUpdatingRecurrence = false) }
            }
        }
    }

    /**
     * Indica si la regla vinculada a una transacción continúa activa.
     *
     * Entrada: movimiento mostrado.
     * Salida: `true` únicamente cuando existe una regla asociada y activa.
     */
    private fun isRecurrenceActive(transaction: Transaction): Boolean {
        val ruleId = transaction.recurringExpenseId ?: return false
        return _uiState.value.recurringRules.firstOrNull { it.id == ruleId }?.active == true
    }

    /**
     * Marca una transacción para que la UI solicite confirmación antes de eliminarla.
     *
     * Entrada: identificador de la fila seleccionada.
     * Efectos: abre el estado del diálogo, pero todavía no elimina nada.
     */
    fun requestDelete(id: String) {
        _uiState.update { it.copy(pendingDeleteId = id) }
    }

    /**
     * Cancela el diálogo de eliminación y conserva intacta la persistencia.
     *
     * Efectos: limpia el identificador pendiente del estado observable.
     */
    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    /**
     * Elimina el identificador que el usuario confirmó explícitamente.
     *
     * Efectos: llama al repositorio en una coroutine y publica éxito o un error recuperable.
     * Pasos: comprueba el ID, ejecuta la eliminación, limpia el diálogo y conserva cancelación.
     */
    fun confirmDelete() {
        val id = _uiState.value.pendingDeleteId ?: return
        viewModelScope.launch {
            try {
                // 1. Solo este punto, posterior a la confirmación, muta la base de datos.
                repository.delete(id)
                _uiState.update { it.copy(pendingDeleteId = null, error = null) }
            } catch (error: CancellationException) {
                // 2. Una coroutine cancelada no debe crear un error visible ni continuar trabajo.
                throw error
            } catch (_: Exception) {
                // 3. Informar el fallo sin escribir el ID ni datos financieros en logs.
                _uiState.update {
                    it.copy(
                        pendingDeleteId = null,
                        error = TransactionsUiError.DELETE_FAILED,
                    )
                }
            }
        }
    }

    /**
     * Oculta el error después de que la UI lo haya presentado.
     *
     * Efectos: modifica únicamente el campo de error del estado observable.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Suscribe el ViewModel a Room y calcula los totales derivados de cada emisión.
     *
     * Efectos: mantiene un `StateFlow` actualizado durante la vida del ViewModel.
     * Pasos: observa, conserva cancelación, traduce errores, calcula con aritmética exacta y
     * publica lista, totales y estado de carga juntos.
     */
    private fun initializeData() {
        viewModelScope.launch {
            // 1. Generar antes de observar hace que la primera lista ya incluya vencimientos.
            try {
                recurringExpenseRepository.generateDue(LocalDate.now(clock), clock.zone)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(error = TransactionsUiError.RECURRENCE_FAILED) }
            }

            if (paged) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            // 2. Flow elimina polling y mantiene lista y persistencia sincronizadas.
            repository.observeTransactions()
                .catch { error ->
                    if (error is CancellationException) throw error
                    // 2. Un fallo de lectura se comunica a la UI y no se silencia.
                    _uiState.update {
                        it.copy(isLoading = false, error = TransactionsUiError.LOAD_FAILED)
                    }
                }
                .collect { transactions ->
                    // 3. Totales y saldos se derivan juntos de la misma emisión.
                    val analytics = try {
                        calculateTransactionTotals(transactions) to
                            calculateRunningBalances(transactions)
                    } catch (_: ArithmeticException) {
                        // 4. Un overflow conserva filas, pero evita publicar un total falso.
                        _uiState.update {
                            it.copy(
                                transactions = transactions,
                                isLoading = false,
                                error = TransactionsUiError.TOTALS_OVERFLOW,
                            )
                        }
                        return@collect
                    }
                    // 5. Una única actualización evita estados parcialmente renderizados.
                    _uiState.update {
                        it.copy(
                            transactions = transactions,
                            totals = analytics.first,
                            runningBalances = analytics.second,
                            isLoading = false,
                            error = null,
                        )
                    }
                }
        }
    }

    /**
     * Observa el catálogo para mostrar nombres y emojis sin acoplar la UI a Room.
     *
     * Efectos: mantiene la lista de categorías del formulario y las filas actualizada.
     */
    private fun observeCategories() {
        viewModelScope.launch {
            categoryRepository.observeCategories()
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    _uiState.update { it.copy(error = TransactionsUiError.LOAD_FAILED) }
                }.collect { categories ->
                _uiState.update { it.copy(categories = categories) }
            }
        }
    }

    /**
     * Observa reglas activas e inactivas para que todas sus ocurrencias compartan casilla.
     *
     * Efectos: actualiza el estado sin realizar consultas desde los composables.
     */
    private fun observeRecurringRules() {
        viewModelScope.launch {
            recurringExpenseRepository.observeRules()
                .catch { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(error = TransactionsUiError.RECURRENCE_FAILED) }
                }
                .collect { rules ->
                    _uiState.update { it.copy(recurringRules = rules) }
                }
        }
    }

    /**
     * Aplica una transformación inmutable al formulario dentro del estado completo.
     *
     * Entrada: transformación sobre [TransactionFormState].
     * Efectos: publica atómicamente una nueva instancia de [TransactionsUiState].
     */
    private fun updateForm(transform: TransactionFormState.() -> TransactionFormState) {
        _uiState.update { it.copy(form = it.form.transform()) }
    }

    /**
     * Crea los campos iniciales del formulario usando el reloj inyectado.
     *
     * Salida: formulario nuevo con moneda EUR y fecha local determinista `dd/MM/aaaa`.
     */
    private fun emptyForm() = TransactionFormState(
        dateText = TransactionDateCodec.format(LocalDate.now(clock)),
    )

    companion object {
        /**
         * Crea una factory mínima para inyectar el repositorio desde la Activity.
         *
         * Entrada: contrato de repositorio de dominio.
         * Salida: factory que acepta únicamente [TransactionsViewModel].
         * Efectos: ninguno hasta que Android solicite la instancia.
         */
        fun factory(
            repository: TransactionRepository,
            categoryRepository: CategoryRepository,
            recurringExpenseRepository: RecurringExpenseRepository,
            paged: Boolean = false,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                /**
                 * Construye el ViewModel solicitado por el ciclo de vida de Android.
                 *
                 * Entrada: clase que pide la instancia.
                 * Salida: [TransactionsViewModel] con el repositorio inyectado.
                 * Errores: rechaza clases no compatibles para evitar una instancia incorrecta.
                 */
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(TransactionsViewModel::class.java))
                    return TransactionsViewModel(
                        repository = repository,
                        categoryRepository = categoryRepository,
                        recurringExpenseRepository = recurringExpenseRepository,
                        paged = paged,
                    ) as T
                }
            }
    }
}
