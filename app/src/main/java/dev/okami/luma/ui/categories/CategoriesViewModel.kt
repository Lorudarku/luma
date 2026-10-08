package dev.okami.luma.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.CategorySpendingSummary
import dev.okami.luma.domain.model.MAX_CATEGORY_EMOJI_CODE_POINTS
import dev.okami.luma.domain.model.MAX_CATEGORY_NAME_LENGTH
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.calculateCategorySpending
import dev.okami.luma.domain.repository.CategoryRepository
import dev.okami.luma.domain.repository.TransactionRepository
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

data class CategoryFormState(
    val editingId: String? = null,
    val name: String = "",
    val emoji: String = "🍎",
    val invalidName: Boolean = false,
    val invalidEmoji: Boolean = false,
)

data class CategoriesUiState(
    val categories: List<Category> = emptyList(),
    val summaries: Map<String, CategorySpendingSummary> = emptyMap(),
    val selectedCategoryId: String? = null,
    val form: CategoryFormState = CategoryFormState(),
    val isFormVisible: Boolean = false,
    val pendingDeleteId: String? = null,
    val isLoading: Boolean = true,
    val error: Boolean = false,
)

class CategoriesViewModel(
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val paged: Boolean = false,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState.asStateFlow()
    private var observedTransactions: List<Transaction> = emptyList()

    init {
        observeCategories()
        if (!paged) observeSpending()
    }

    /**
     * Abre un formulario limpio para una categoría personalizada.
     *
     * Efectos: descarta valores anteriores y muestra el diálogo.
     */
    fun showAddForm() {
        _uiState.update { it.copy(form = CategoryFormState(), isFormVisible = true) }
    }

    /**
     * Abre el desglose de una categoría desde su tarjeta de resumen.
     *
     * Entrada: identificador visible en el catálogo actual.
     * Efectos: selecciona únicamente IDs todavía existentes.
     */
    fun openDetails(id: String) {
        if (_uiState.value.categories.none { it.id == id }) return
        _uiState.update { it.copy(selectedCategoryId = id) }
    }

    /**
     * Regresa del detalle al resumen general sin alterar filtros ni datos.
     */
    fun closeDetails() {
        _uiState.update { it.copy(selectedCategoryId = null) }
    }

    /**
     * Carga una categoría existente para editarla.
     *
     * Entrada: categoría seleccionada.
     * Efectos: publica sus campos en el formulario.
     */
    fun edit(category: Category) {
        _uiState.update {
            it.copy(
                form = CategoryFormState(category.id, category.name, category.emoji),
                isFormVisible = true,
            )
        }
    }

    /**
     * Cierra el formulario sin persistir cambios.
     *
     * Efectos: restablece el borrador.
     */
    fun dismissForm() {
        _uiState.update { it.copy(form = CategoryFormState(), isFormVisible = false) }
    }

    /**
     * Actualiza el nombre del borrador y limpia su error.
     *
     * Entrada: texto actual.
     */
    fun updateName(name: String) {
        _uiState.update { it.copy(form = it.form.copy(name = name, invalidName = false)) }
    }

    /**
     * Actualiza el emoji del borrador y limpia su error.
     *
     * Entrada: texto Unicode actual.
     */
    fun updateEmoji(emoji: String) {
        _uiState.update { it.copy(form = it.form.copy(emoji = emoji, invalidEmoji = false)) }
    }

    /**
     * Valida y persiste una categoría nueva o editada.
     *
     * Efectos: muestra errores locales o ejecuta la operación suspendida correspondiente.
     */
    fun save() {
        val form = _uiState.value.form
        val name = form.name.trim()
        val emoji = form.emoji.trim()
        val invalidName = name.isEmpty() || name.length > MAX_CATEGORY_NAME_LENGTH
        val invalidEmoji = emoji.isEmpty() ||
            emoji.codePointCount(0, emoji.length) > MAX_CATEGORY_EMOJI_CODE_POINTS
        if (invalidName || invalidEmoji) {
            _uiState.update {
                it.copy(form = form.copy(invalidName = invalidName, invalidEmoji = invalidEmoji))
            }
            return
        }

        val existing = _uiState.value.categories.firstOrNull { it.id == form.editingId }
        val category = Category(
            id = form.editingId ?: idFactory(),
            name = name,
            emoji = emoji,
            isDefault = existing?.isDefault ?: false,
        )
        viewModelScope.launch {
            try {
                // 1. El ID del formulario decide si se inserta o actualiza.
                if (form.editingId == null) categoryRepository.add(category)
                else categoryRepository.update(category)
                _uiState.update {
                    it.copy(form = CategoryFormState(), isFormVisible = false, error = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 2. El fallo incluye duplicados sin exponer detalles de SQLite.
                _uiState.update { it.copy(error = true) }
            }
        }
    }

    /**
     * Solicita confirmación para eliminar una categoría.
     *
     * Entrada: identificador seleccionado.
     */
    fun requestDelete(id: String) {
        _uiState.update { it.copy(pendingDeleteId = id) }
    }

    /**
     * Cancela la eliminación pendiente.
     *
     * Efectos: conserva categoría y movimientos.
     */
    fun dismissDelete() {
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    /**
     * Elimina la categoría confirmada y deja sus referencias sin categoría.
     *
     * Efectos: delega la operación atómica al repositorio.
     */
    fun confirmDelete() {
        val id = _uiState.value.pendingDeleteId ?: return
        viewModelScope.launch {
            try {
                categoryRepository.delete(id)
                _uiState.update {
                    it.copy(
                        pendingDeleteId = null,
                        selectedCategoryId = null,
                        error = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(pendingDeleteId = null, error = true) }
            }
        }
    }

    /**
     * Descarta el error visible después de que el usuario lo lea.
     *
     * Efectos: actualiza solo la bandera de error.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = false) }
    }

    /**
     * Mantiene el catálogo de UI sincronizado con Room.
     *
     * Efectos: publica categorías y finaliza el estado de carga inicial.
     */
    private fun observeCategories() {
        viewModelScope.launch {
            categoryRepository.observeCategories()
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    _uiState.update { it.copy(isLoading = false, error = true) }
                }
                .collect { categories ->
                    _uiState.update { it.copy(categories = categories, isLoading = false) }
                    if (!paged) recalculateSummaries()
                }
        }
    }

    /**
     * Cuenta cuántos movimientos usan cada categoría sin duplicar consultas específicas.
     *
     * Entrada: repositorio de movimientos ya existente.
     * Efectos: publica un mapa de uso reactivo.
     */
    private fun observeSpending() {
        viewModelScope.launch {
            transactionRepository.observeTransactions()
                .catch { cause ->
                    if (cause is CancellationException) throw cause
                    _uiState.update { it.copy(error = true) }
                }
                .collect { transactions ->
                    observedTransactions = transactions
                    recalculateSummaries()
                }
        }
    }

    /**
     * Recalcula resúmenes mensuales/anuales desde la instantánea observable compartida.
     *
     * Efectos: publica todos los resúmenes juntos o un error si algún total desborda [Long].
     */
    private fun recalculateSummaries() {
        try {
            // 1. El reloj inyectado fija los límites naturales del mes y año en tests y producto.
            val referenceDate = LocalDate.now(clock)
            val summaries = _uiState.value.categories.associate { category ->
                category.id to calculateCategorySpending(
                    transactions = observedTransactions,
                    categoryId = category.id,
                    referenceDate = referenceDate,
                    zoneId = clock.zone,
                )
            }
            _uiState.update { state ->
                val selected = state.selectedCategoryId?.takeIf { it in summaries }
                state.copy(summaries = summaries, selectedCategoryId = selected, error = false)
            }
        } catch (_: ArithmeticException) {
            // 2. Nunca se publica una cifra truncada si un acumulado no es representable.
            _uiState.update { it.copy(summaries = emptyMap(), error = true) }
        }
    }

    companion object {
        /**
         * Crea la factory de categorías para la composición manual de dependencias.
         *
         * Entradas: repositorios local de categorías y movimientos.
         */
        fun factory(
            categoryRepository: CategoryRepository,
            transactionRepository: TransactionRepository,
            paged: Boolean = false,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            /**
             * Construye el ViewModel compatible solicitado por Android.
             *
             * Entrada: clase de ViewModel.
             * Salida: instancia de [CategoriesViewModel].
             */
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CategoriesViewModel::class.java))
                return CategoriesViewModel(categoryRepository, transactionRepository, paged = paged) as T
            }
        }
    }
}
