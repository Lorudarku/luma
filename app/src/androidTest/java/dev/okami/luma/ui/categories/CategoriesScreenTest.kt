package dev.okami.luma.ui.categories

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.CategoryCurrencySpending
import dev.okami.luma.domain.model.CategorySpendingSummary
import dev.okami.luma.ui.theme.LumaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CategoriesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val category = Category("food", "Alimentación", "🍎", false)
    private val summary = CategorySpendingSummary(
        category.id,
        listOf(CategoryCurrencySpending("EUR", 1_000, 5_000)),
        emptyList(),
    )

    @Test
    /**
     * Comprueba que la lista abre el detalle y no expone acciones de gestión directamente.
     */
    fun summaryCardIsClickableAndManagementIsHidden() {
        var opened: Category? = null
        setScreen(CategoriesUiState(
            categories = listOf(category),
            summaries = mapOf(category.id to summary),
            isLoading = false,
        ), onOpen = { opened = it })

        composeRule.onAllNodesWithText("Editar").assertCountEquals(0)
        composeRule.onNodeWithText("🍎 Alimentación").performClick()

        assertEquals(category, opened)
    }

    @Test
    /**
     * Comprueba que editar y eliminar aparecen exclusivamente dentro del detalle.
     */
    fun detailShowsManagementActions() {
        setScreen(CategoriesUiState(
            categories = listOf(category),
            summaries = mapOf(category.id to summary),
            selectedCategoryId = category.id,
            isLoading = false,
        ))

        composeRule.onNodeWithText("Editar").assertIsDisplayed()
        composeRule.onNodeWithText("Eliminar").assertIsDisplayed()
    }

    /** Monta la pantalla controlada con callbacks sin persistencia. */
    private fun setScreen(
        state: CategoriesUiState,
        onOpen: (Category) -> Unit = {},
    ) {
        composeRule.setContent {
            LumaTheme {
                CategoriesScreen(
                    state = state,
                    onAdd = {},
                    onOpenDetails = onOpen,
                    onCloseDetails = {},
                    onEdit = {},
                    onDelete = {},
                    onDismissForm = {},
                    onNameChange = {},
                    onEmojiChange = {},
                    onSave = {},
                    onDismissDelete = {},
                    onConfirmDelete = {},
                    onDismissError = {},
                )
            }
        }
    }
}
