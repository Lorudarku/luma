package dev.okami.luma.ui.categories

import dev.okami.luma.MainDispatcherRule
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import dev.okami.luma.domain.repository.CategoryRepository
import dev.okami.luma.domain.repository.TransactionRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-08-31T10:00:00Z"), ZoneOffset.UTC)

    @Test
    /**
     * Comprueba resumen reactivo, selección de detalle y cierre tras eliminar la categoría.
     */
    fun `calculates summaries and keeps management inside details`() = runTest {
        val category = Category("food", "Alimentación", "🍎", false)
        val categoryRepository = FakeCategoryRepository(listOf(category))
        val transactionRepository = FakeTransactionRepository(
            listOf(transaction("august", 500, "2026-08-20T10:00:00Z"))
        )
        val viewModel = CategoriesViewModel(
            categoryRepository,
            transactionRepository,
            clock,
        )
        runCurrent()

        val summary = viewModel.uiState.value.summaries.getValue(category.id)
        assertEquals(500L, summary.totals.single().monthMinor)
        assertEquals(500L, summary.totals.single().yearMinor)

        viewModel.openDetails(category.id)
        assertEquals(category.id, viewModel.uiState.value.selectedCategoryId)
        viewModel.requestDelete(category.id)
        viewModel.confirmDelete()
        runCurrent()

        assertEquals(listOf(category.id), categoryRepository.deleted)
        assertNull(viewModel.uiState.value.selectedCategoryId)
    }

    /** Crea un gasto ficticio de la categoría observada. */
    private fun transaction(id: String, amountMinor: Long, instant: String) = Transaction(
        id = id,
        type = TransactionType.EXPENSE,
        money = Money(amountMinor, "EUR"),
        occurredAt = Instant.parse(instant),
        description = "Compra ficticia",
        categoryId = "food",
    )

    private class FakeCategoryRepository(initial: List<Category>) : CategoryRepository {
        private val categories = MutableStateFlow(initial)
        val deleted = mutableListOf<String>()

        /** Expone el catálogo mutable usado por el ViewModel. */
        override fun observeCategories(): Flow<List<Category>> = categories

        /** Añade una categoría y publica la lista resultante. */
        override suspend fun add(category: Category) {
            categories.value += category
        }

        /** Sustituye una categoría por identificador. */
        override suspend fun update(category: Category) {
            categories.value = categories.value.map { if (it.id == category.id) category else it }
        }

        /** Registra y elimina una categoría del catálogo simulado. */
        override suspend fun delete(id: String) {
            deleted += id
            categories.value = categories.value.filterNot { it.id == id }
        }
    }

    private class FakeTransactionRepository(initial: List<Transaction>) : TransactionRepository {
        private val transactions = MutableStateFlow(initial)

        /** Expone movimientos estables para el cálculo de gasto. */
        override fun observeTransactions(): Flow<List<Transaction>> = transactions

        /** Operación no utilizada en este test de lectura. */
        override suspend fun add(transaction: Transaction) = Unit

        /** Operación no utilizada en este test de lectura. */
        override suspend fun update(transaction: Transaction) = Unit

        /** Operación no utilizada en este test de lectura. */
        override suspend fun delete(id: String) = Unit
    }
}
