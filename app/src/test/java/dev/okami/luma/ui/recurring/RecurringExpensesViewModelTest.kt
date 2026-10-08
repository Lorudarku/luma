package dev.okami.luma.ui.recurring

import dev.okami.luma.MainDispatcherRule
import dev.okami.luma.domain.model.*
import dev.okami.luma.domain.repository.*
import java.time.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecurringExpensesViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)

    /** Un fallo atómico conserva el borrador y su ID; el reintento persiste una única identidad. */
    @Test fun failedSaveRetriesWithSameIdentity() = runTest {
        val repository = Rules()
        var ids = 0
        val model = RecurringExpensesViewModel(repository, Categories(), clock) { "draft-${++ids}" }
        model.showAddForm(); model.updateAmount("25,00")
        repository.fail = true
        model.save(); runCurrent()
        assertTrue(model.uiState.value.error)
        assertTrue(model.uiState.value.isFormVisible)
        assertFalse(model.uiState.value.isSaving)
        repository.fail = false
        model.save(); runCurrent()
        assertEquals(listOf("draft-1", "draft-1"), repository.attempts.map { it.id })
        assertEquals(1, ids)
        assertFalse(model.uiState.value.isFormVisible)
    }

    /** Dos envíos sin ejecutar la coroutine y un tercero mientras espera no producen llamadas duplicadas. */
    @Test fun doubleSubmissionAndEditsAreBlockedDuringSave() = runTest {
        val repository = Rules()
        repository.gate = CompletableDeferred()
        val model = RecurringExpensesViewModel(repository, Categories(), clock) { "stable" }
        model.showAddForm(); model.updateAmount("10")
        model.save(); model.save()
        assertTrue(model.uiState.value.isSaving)
        runCurrent()
        model.save(); model.updateAmount("99"); model.dismissForm()
        assertEquals(1, repository.attempts.size)
        assertEquals("10", model.uiState.value.form.amountText)
        assertTrue(model.uiState.value.isFormVisible)
        repository.gate!!.complete(Unit); runCurrent()
        assertFalse(model.uiState.value.isSaving)
        assertEquals(1000L, repository.attempts.single().money.amountMinor)
    }

    private class Rules : RecurringExpenseRepository {
        val attempts = mutableListOf<RecurringExpense>()
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        /** Emite reglas ficticias vacías para aislar el guardado. */
        override fun observeRules(): Flow<List<RecurringExpense>> = MutableStateFlow(emptyList())
        /** Registra una operación atómica y puede fallar o esperar para probar reintentos/doble envío. */
        override suspend fun saveAndGenerate(rule: RecurringExpense, throughDate: LocalDate, zoneId: ZoneId) {
            attempts += rule
            gate?.await()
            if (fail) error("injected storage failure")
        }
        /** Las operaciones separadas no deben utilizarse al guardar desde el ViewModel. */
        override suspend fun add(rule: RecurringExpense) { error("non atomic save") }
        /** Las operaciones separadas no deben utilizarse al editar desde el ViewModel. */
        override suspend fun update(rule: RecurringExpense) { error("non atomic update") }
        /** No interviene en los escenarios de guardado. */
        override suspend fun deactivate(id: String) = Unit
        /** No interviene en los escenarios de guardado. */
        override suspend fun generateDue(throughDate: LocalDate, zoneId: ZoneId) = Unit
        /** No interviene en los escenarios de guardado. */
        override suspend fun setMonthlyRecurrence(transaction: Transaction, enabled: Boolean, throughDate: LocalDate, zoneId: ZoneId) = Unit
    }

    private class Categories : CategoryRepository {
        /** Emite un catálogo ficticio vacío. */
        override fun observeCategories(): Flow<List<Category>> = flowOf(emptyList())
        /** No se usa en estas pruebas de reglas. */
        override suspend fun add(category: Category) = Unit
        /** No se usa en estas pruebas de reglas. */
        override suspend fun update(category: Category) = Unit
        /** No se usa en estas pruebas de reglas. */
        override suspend fun delete(id: String) = Unit
    }
}
