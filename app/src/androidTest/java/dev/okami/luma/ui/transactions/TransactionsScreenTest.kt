package dev.okami.luma.ui.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import dev.okami.luma.domain.validation.AmountInputError
import dev.okami.luma.ui.theme.LumaTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TransactionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    /**
     * Verifica que el estado vacío se anuncia y que la acción de alta es alcanzable.
     *
     * Pasos: monta estado sin transacciones, comprueba textos visibles, pulsa alta y revisa callback.
     */
    fun emptyStateIsVisibleAndAddActionIsReachable() {
        val callbacks = Callbacks()
        setScreen(emptyState(), callbacks)

        composeRule.onNodeWithText("Aún no hay movimientos").assertIsDisplayed()
        composeRule.onNodeWithText("Añadir movimiento").performClick()

        assertTrue(callbacks.addClicked)
    }

    @Test
    /**
     * Verifica que la pantalla muestra errores de importe, fecha y descripción a la vez.
     *
     * Pasos: monta el formulario con errores, comprueba mensajes y pulsa revisar para comprobar
     * que la acción se expone al ViewModel.
     */
    fun formShowsAllValidationErrorsAndReviewAction() {
        val callbacks = Callbacks()
        setScreen(
            emptyState().copy(
                isFormVisible = true,
                form = TransactionFormState(
                    dateText = "fecha inválida",
                    amountError = AmountInputError.EMPTY,
                    invalidDate = true,
                    descriptionTooLong = true,
                ),
            ),
            callbacks,
        )

        composeRule.onNodeWithText("Introduce una cantidad.").assertIsDisplayed()
        composeRule.onNodeWithText("Selecciona una fecha válida.").assertIsDisplayed()
        composeRule.onNodeWithText("La descripción puede tener como máximo 120 caracteres.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Revisar").performClick()

        assertTrue(callbacks.reviewClicked)
    }

    @Test
    /**
     * Verifica que una fila expone edición y eliminación con la transacción correcta.
     *
     * Pasos: monta una fila ficticia, pulsa ambas acciones y compara los objetos recibidos.
     */
    fun transactionExposesEditAndDeleteActions() {
        val callbacks = Callbacks()
        val transaction = transaction()
        setScreen(
            emptyState().copy(transactions = listOf(transaction)),
            callbacks,
        )

        composeRule.onNodeWithText("Compra ficticia").assertIsDisplayed()
        composeRule.onNodeWithText("Editar").performClick()
        composeRule.onNodeWithText("Eliminar").performClick()

        assertEquals(transaction, callbacks.edited)
        assertEquals(transaction, callbacks.deleted)
    }

    @Test
    /**
     * Verifica que eliminar requiere un segundo consentimiento explícito.
     *
     * Pasos: monta el estado de diálogo pendiente, comprueba su mensaje y confirma la acción.
     */
    fun deleteRequiresExplicitConfirmation() {
        val callbacks = Callbacks()
        setScreen(
            emptyState().copy(pendingDeleteId = "transaction-1"),
            callbacks,
        )

        composeRule.onNodeWithText("Eliminar movimiento").assertIsDisplayed()
        composeRule.onNodeWithText("Eliminar").performClick()

        assertTrue(callbacks.deleteConfirmed)
    }

    @Test
    /**
     * Verifica que solo un gasto ofrece la casilla mensual y delega el cambio solicitado.
     */
    fun expenseShowsMonthlyCheckbox() {
        val callbacks = Callbacks()
        val expense = transaction()
        setScreen(emptyState().copy(transactions = listOf(expense)), callbacks)

        composeRule.onNodeWithContentDescription("Gasto mensual").performClick()

        assertEquals(expense, callbacks.recurrenceTransaction)
        assertTrue(callbacks.recurrenceEnabled == true)
    }

    @Test
    /**
     * Verifica que los ingresos no pueden convertirse en gastos mensuales.
     */
    fun incomeDoesNotShowMonthlyCheckbox() {
        val callbacks = Callbacks()
        setScreen(
            emptyState().copy(
                transactions = listOf(transaction().copy(type = TransactionType.INCOME))
            ),
            callbacks,
        )

        composeRule.onAllNodesWithContentDescription("Gasto mensual").assertCountEquals(0)
    }

    /**
     * Monta la pantalla pura con callbacks de prueba y un tema estable.
     *
     * Entradas: estado a renderizar y recolector de callbacks.
     * Efectos: reemplaza el contenido de la regla Compose para el caso actual.
     * Pasos: aplica el tema, conecta cada interacción y deja la pantalla lista para assertions.
     */
    private fun setScreen(state: TransactionsUiState, callbacks: Callbacks) {
        // 1. Las lambdas registran interacciones sin introducir un repositorio real.
        composeRule.setContent {
            LumaTheme {
                TransactionsScreen(
                    state = state,
                    onAdd = { callbacks.addClicked = true },
                    onEdit = { callbacks.edited = it },
                    onDelete = { callbacks.deleted = it },
                    onDismissForm = {},
                    onTypeChange = {},
                    onAmountChange = {},
                    onDateChange = {},
                    onDescriptionChange = {},
                    onCategoryChange = {},
                    onRepeatMonthlyChange = {},
                    onReview = { callbacks.reviewClicked = true },
                    onDismissSave = {},
                    onConfirmSave = {},
                    onDismissDelete = {},
                    onConfirmDelete = { callbacks.deleteConfirmed = true },
                    onRecurrenceChange = { transaction, enabled ->
                        callbacks.recurrenceTransaction = transaction
                        callbacks.recurrenceEnabled = enabled
                    },
                    onDismissRecurrence = {},
                    onConfirmRecurrence = {},
                    onDismissError = {},
                )
            }
        }
    }

    /**
     * Construye el estado base de una pantalla sin movimientos y sin carga pendiente.
     *
     * Salida: estado mínimo reutilizable por los tests de UI.
     */
    private fun emptyState() = TransactionsUiState(
        form = TransactionFormState(dateText = "30/08/2026"),
        isLoading = false,
    )

    /**
     * Crea una transacción ficticia estable para verificar el contenido de una fila.
     *
     * Salida: modelo de dominio sin datos reales ni efectos externos.
     */
    private fun transaction() = Transaction(
        id = "transaction-1",
        type = TransactionType.EXPENSE,
        money = Money(1_250, "EUR"),
        occurredAt = Instant.parse("2026-08-30T10:00:00Z"),
        description = "Compra ficticia",
    )

    private class Callbacks {
        var addClicked = false
        var reviewClicked = false
        var deleteConfirmed = false
        var edited: Transaction? = null
        var deleted: Transaction? = null
        var recurrenceTransaction: Transaction? = null
        var recurrenceEnabled: Boolean? = null
    }
}
