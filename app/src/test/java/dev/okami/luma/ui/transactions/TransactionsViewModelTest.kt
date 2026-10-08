package dev.okami.luma.ui.transactions

import dev.okami.luma.MainDispatcherRule
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.RecurringExpense
import dev.okami.luma.domain.repository.CategoryRepository
import dev.okami.luma.domain.repository.RecurringExpenseRepository
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import dev.okami.luma.domain.repository.TransactionRepository
import dev.okami.luma.domain.validation.AmountInputError
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(
        Instant.parse("2026-08-30T10:00:00Z"),
        ZoneOffset.UTC,
    )

    @Test
    /**
     * Comprueba que el ViewModel deja de cargar y usa la fecha del reloj inyectado.
     *
     * Pasos: crea repositorio vacío, deja ejecutar la colección y verifica el estado resultante.
     */
    fun `loads empty state and current date`() = runTest {
        val viewModel = viewModel(FakeRepository())
        runCurrent()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.transactions.isEmpty())
        assertEquals("30/08/2026", viewModel.uiState.value.form.dateText)
    }

    @Test
    /**
     * Comprueba que los errores de formulario impiden cualquier escritura.
     *
     * Pasos: introduce importe/fecha inválidos, revisa el formulario y comprueba errores y ausencia
     * de llamadas al repositorio.
     */
    fun `invalid form exposes errors and never persists`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.showAddForm()
        viewModel.updateAmount("0")
        viewModel.updateDate("30-08-2026")

        viewModel.reviewTransaction()

        assertEquals(AmountInputError.NON_POSITIVE, viewModel.uiState.value.form.amountError)
        assertTrue(viewModel.uiState.value.form.invalidDate)
        assertFalse(viewModel.uiState.value.showSaveConfirmation)
        assertTrue(repository.added.isEmpty())
    }

    @Test
    /**
     * Comprueba la separación entre revisar y confirmar un guardado válido.
     *
     * Pasos: completa el formulario, verifica que revisar no persiste, confirma una vez y comprueba
     * la transacción exacta y el reinicio del formulario.
     */
    fun `valid form requires confirmation then saves once and resets`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.showAddForm()
        viewModel.updateAmount("12,50")
        viewModel.updateDescription(" Compra ficticia ")

        viewModel.reviewTransaction()
        assertTrue(viewModel.uiState.value.showSaveConfirmation)
        assertTrue(repository.added.isEmpty())

        viewModel.confirmSave()
        runCurrent()

        assertEquals(1, repository.added.size)
        assertEquals(1250L, repository.added.single().money.amountMinor)
        assertEquals("Compra ficticia", repository.added.single().description)
        assertFalse(viewModel.uiState.value.isFormVisible)
        assertEquals("", viewModel.uiState.value.form.amountText)
    }

    @Test
    /**
     * Comprueba que un gasto mensual crea una regla y genera únicamente ocurrencias vencidas.
     *
     * Pasos: activa recurrencia, confirma el gasto y verifica regla, fecha límite y ausencia de
     * inserción manual duplicada en el repositorio de movimientos.
     */
    fun `monthly expense saves a rule and generates due occurrences`() = runTest {
        val repository = FakeRepository()
        val recurringRepository = FakeRecurringExpenseRepository()
        val viewModel = TransactionsViewModel(
            repository = repository,
            categoryRepository = FakeCategoryRepository(),
            recurringExpenseRepository = recurringRepository,
            clock = clock,
            idFactory = { "generated-id" },
        )
        viewModel.showAddForm()
        viewModel.updateAmount("20")
        viewModel.updateRepeatMonthly(true)
        viewModel.reviewTransaction()
        viewModel.confirmSave()
        runCurrent()

        assertTrue(repository.added.isEmpty())
        assertEquals("generated-id", recurringRepository.recurrenceChanges.single().first.id)
        assertTrue(recurringRepository.recurrenceChanges.single().second)
        assertEquals(2, recurringRepository.generatedThrough.size)
        assertEquals(LocalDate.of(2026, 8, 30), recurringRepository.generatedThrough.last())
    }

    @Test
    /**
     * Comprueba que una fila histórica solicita confirmación antes de activar su regla mensual.
     */
    fun `historical expense recurrence is confirmed before repository call`() = runTest {
        val expense = transaction("historical", TransactionType.EXPENSE, 2_000)
        val repository = FakeRepository(listOf(expense))
        val recurringRepository = FakeRecurringExpenseRepository()
        val viewModel = TransactionsViewModel(
            repository,
            FakeCategoryRepository(),
            recurringRepository,
            clock,
        )
        runCurrent()

        viewModel.requestRecurrenceChange(expense, true)
        assertEquals(expense.id, viewModel.uiState.value.pendingRecurrenceTransactionId)
        assertTrue(recurringRepository.recurrenceChanges.isEmpty())

        viewModel.confirmRecurrenceChange()
        runCurrent()

        assertEquals(expense to true, recurringRepository.recurrenceChanges.single())
        assertNull(viewModel.uiState.value.pendingRecurrenceTransactionId)
    }

    @Test
    /**
     * Comprueba que un error de repositorio es visible sin perder el formulario de reintento.
     *
     * Pasos: inyecta un fallo de alta, confirma el guardado y verifica error, formulario e indicador.
     */
    fun `repository save error is visible and keeps the form`() = runTest {
        val repository = FakeRepository(failOnAdd = IllegalStateException("failure"))
        val viewModel = viewModel(repository)
        viewModel.showAddForm()
        viewModel.updateAmount("10")
        viewModel.reviewTransaction()

        viewModel.confirmSave()
        runCurrent()

        assertEquals(TransactionsUiError.SAVE_FAILED, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.isFormVisible)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    /**
     * Comprueba que una cancelación de coroutine no se transforma en error de negocio.
     *
     * Pasos: inyecta [CancellationException], ejecuta la confirmación y verifica estado limpio.
     */
    fun `cancellation is not converted into a user error`() = runTest {
        val repository = FakeRepository(failOnAdd = CancellationException("cancelled"))
        val viewModel = viewModel(repository)
        viewModel.showAddForm()
        viewModel.updateAmount("10")
        viewModel.reviewTransaction()

        viewModel.confirmSave()
        runCurrent()

        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    /**
     * Comprueba edición, eliminación y balance negativo usando un repositorio falso observable.
     *
     * Pasos: carga un gasto, edita el importe, confirma, solicita eliminación y verifica llamadas.
     */
    fun `edit delete and totals remain deterministic`() = runTest {
        val existing = transaction("expense", TransactionType.EXPENSE, 500)
        val repository = FakeRepository(listOf(existing))
        val viewModel = viewModel(repository)
        runCurrent()

        assertEquals(-500L, viewModel.uiState.value.totals.single().balanceMinor)

        viewModel.edit(existing)
        viewModel.updateAmount("7,50")
        viewModel.reviewTransaction()
        viewModel.confirmSave()
        runCurrent()
        assertEquals(750L, repository.updated.single().money.amountMinor)

        viewModel.requestDelete(existing.id)
        viewModel.confirmDelete()
        runCurrent()
        assertEquals(listOf(existing.id), repository.deleted)
    }

    /**
     * Construye el ViewModel con reloj e identificador deterministas para el caso de prueba.
     *
     * Entradas: repositorio falso.
     * Salida: ViewModel aislado sin servicios Android reales.
     */
    private fun viewModel(repository: FakeRepository) = TransactionsViewModel(
        repository = repository,
        categoryRepository = FakeCategoryRepository(),
        recurringExpenseRepository = FakeRecurringExpenseRepository(),
        clock = clock,
        idFactory = { "generated-id" },
    )

    /**
     * Crea una transacción ficticia compacta para escenarios de estado.
     *
     * Entradas: ID, tipo y cantidad en unidades mínimas.
     * Salida: modelo válido con fecha fija.
     */
    private fun transaction(id: String, type: TransactionType, amountMinor: Long) = Transaction(
        id = id,
        type = type,
        money = Money(amountMinor, "EUR"),
        occurredAt = clock.instant(),
        description = null,
    )

    private class FakeRepository(
        initialTransactions: List<Transaction> = emptyList(),
        private val failOnAdd: Throwable? = null,
    ) : TransactionRepository {
        private val transactions = MutableStateFlow(initialTransactions)
        val added = mutableListOf<Transaction>()
        val updated = mutableListOf<Transaction>()
        val deleted = mutableListOf<String>()

        /**
         * Expone el flujo mutable que simula las emisiones de Room.
         *
         * Salida: Flow de transacciones actuales del fake.
         */
        override fun observeTransactions(): Flow<List<Transaction>> = transactions

        /**
         * Simula un alta, registra la llamada y publica la nueva lista.
         *
         * Entrada: transacción de dominio.
         * Efectos: opcionalmente lanza el fallo configurado y actualiza el Flow.
         */
        override suspend fun add(transaction: Transaction) {
            // 1. Permitir errores inyectados prueba la ruta recuperable del ViewModel.
            failOnAdd?.let { throw it }
            added += transaction
            transactions.value = transactions.value + transaction
        }

        /**
         * Simula actualización por ID y emite el estado reemplazado.
         *
         * Entrada: transacción nueva con el mismo identificador lógico.
         * Efectos: registra la llamada y actualiza la lista simulada.
         */
        override suspend fun update(transaction: Transaction) {
            updated += transaction
            transactions.value = transactions.value.map {
                if (it.id == transaction.id) transaction else it
            }
        }

        /**
         * Simula eliminación filtrando el ID y publicando la lista restante.
         *
         * Entrada: identificador lógico.
         * Efectos: registra la llamada y actualiza el Flow.
         */
        override suspend fun delete(id: String) {
            deleted += id
            transactions.value = transactions.value.filterNot { it.id == id }
        }
    }

    /** Editar una cuota a ingreso elimina metadatos y permite revisar el valor exacto. */
    @Test fun `changing recurring expense to income detaches recurrence`() = runTest {
        val model = viewModel(FakeRepository())
        val expense = Transaction("linked", TransactionType.EXPENSE, Money(1000, "EUR"),
            Instant.parse("2026-08-01T00:00:00Z"), null, recurringExpenseId = "rule",
            recurrenceDate = LocalDate.of(2026, 8, 1))
        model.edit(expense); model.updateType(TransactionType.INCOME)
        assertNull(model.uiState.value.form.recurringExpenseId)
        assertNull(model.uiState.value.form.recurrenceDate)
        model.reviewTransaction()
        assertTrue(model.uiState.value.showSaveConfirmation)
    }

    private class FakeCategoryRepository : CategoryRepository {
        /** Expone un catálogo vacío para aislar los tests de movimientos. */
        override fun observeCategories(): Flow<List<Category>> = MutableStateFlow(emptyList())

        /** No se usa en estos tests. */
        override suspend fun add(category: Category) = Unit

        /** No se usa en estos tests. */
        override suspend fun update(category: Category) = Unit

        /** No se usa en estos tests. */
        override suspend fun delete(id: String) = Unit
    }

    private class FakeRecurringExpenseRepository : RecurringExpenseRepository {
        /** Simula el nuevo contrato atómico con datos ficticios. */
        override suspend fun saveAndGenerate(rule: RecurringExpense, throughDate: LocalDate, zoneId: java.time.ZoneId) {
            added += rule
            generatedThrough += throughDate
        }
        val added = mutableListOf<RecurringExpense>()
        val generatedThrough = mutableListOf<LocalDate>()
        val recurrenceChanges = mutableListOf<Pair<Transaction, Boolean>>()

        /** Expone reglas vacías para aislar la carga inicial. */
        override fun observeRules(): Flow<List<RecurringExpense>> = MutableStateFlow(emptyList())

        /** No se usa en los escenarios manuales actuales. */
        override suspend fun add(rule: RecurringExpense) {
            added += rule
        }

        /** No se usa en los escenarios manuales actuales. */
        override suspend fun update(rule: RecurringExpense) = Unit

        /** No se usa en los escenarios manuales actuales. */
        override suspend fun deactivate(id: String) = Unit

        /** Simula una generación inicial sin efectos. */
        override suspend fun generateDue(throughDate: LocalDate, zoneId: java.time.ZoneId) {
            generatedThrough += throughDate
        }

        /** Registra activaciones y desactivaciones solicitadas desde movimientos. */
        override suspend fun setMonthlyRecurrence(
            transaction: Transaction,
            enabled: Boolean,
            throughDate: LocalDate,
            zoneId: java.time.ZoneId,
        ) {
            recurrenceChanges += transaction to enabled
            generatedThrough += throughDate
        }
    }
}
