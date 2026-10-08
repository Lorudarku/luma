package dev.okami.luma.ui.statistics

import dev.okami.luma.MainDispatcherRule
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.StatisticsPeriod
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC)

    @Test
    /**
     * Comprueba selección de divisas, cambio de periodo y bloqueo de navegación futura.
     *
     * Pasos: publica EUR/USD, selecciona USD, retrocede una semana y vuelve al periodo actual.
     */
    fun `keeps currencies separate and prevents future navigation`() = runTest {
        val repository = FakeRepository(
            listOf(
                transaction("eur", "EUR", 1_000),
                transaction("usd", "USD", 2_000),
            )
        )
        val viewModel = StatisticsViewModel(repository, clock)
        runCurrent()

        assertEquals(listOf("EUR", "USD"), viewModel.uiState.value.currencies)
        assertEquals("EUR", viewModel.uiState.value.series?.currencyCode)
        assertFalse(viewModel.uiState.value.canNavigateNext)

        viewModel.selectCurrency("USD")
        viewModel.selectPeriod(StatisticsPeriod.WEEK)
        assertEquals("USD", viewModel.uiState.value.series?.currencyCode)
        assertEquals(2_000L, viewModel.uiState.value.series?.points?.last()?.balanceMinor)

        viewModel.previousPeriod()
        assertTrue(viewModel.uiState.value.canNavigateNext)
        viewModel.nextPeriod()
        assertFalse(viewModel.uiState.value.canNavigateNext)
    }

    /**
     * Crea un ingreso ficticio fechado por el reloj estable del test.
     */
    private fun transaction(id: String, currency: String, amountMinor: Long) = Transaction(
        id = id,
        type = TransactionType.INCOME,
        money = Money(amountMinor, currency),
        occurredAt = clock.instant(),
        description = null,
    )

    private class FakeRepository(initial: List<Transaction>) : TransactionRepository {
        private val transactions = MutableStateFlow(initial)

        /** Expone la lista estable usada por el ViewModel. */
        override fun observeTransactions(): Flow<List<Transaction>> = transactions

        /** Operación no utilizada en esta prueba de solo lectura. */
        override suspend fun add(transaction: Transaction) = Unit

        /** Operación no utilizada en esta prueba de solo lectura. */
        override suspend fun update(transaction: Transaction) = Unit

        /** Operación no utilizada en esta prueba de solo lectura. */
        override suspend fun delete(id: String) = Unit
    }
}
