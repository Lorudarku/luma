package dev.okami.luma.ui.statistics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.okami.luma.domain.model.BalancePoint
import dev.okami.luma.domain.model.BalanceSeries
import dev.okami.luma.domain.model.StatisticsPeriod
import dev.okami.luma.ui.theme.LumaTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StatisticsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    /**
     * Comprueba etiquetas de ejes y navegación al periodo siguiente mediante gesto horizontal.
     */
    fun chartShowsAxesAndSwipeNavigatesToNextPeriod() {
        var nextCalls = 0
        val series = BalanceSeries(
            "EUR",
            StatisticsPeriod.MONTH,
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 3),
            listOf(
                BalancePoint(LocalDate.of(2026, 8, 1), 0),
                BalancePoint(LocalDate.of(2026, 8, 2), 500),
                BalancePoint(LocalDate.of(2026, 8, 3), -200),
            ),
        )
        composeRule.setContent {
            LumaTheme {
                StatisticsScreen(
                    state = StatisticsUiState(
                        currencies = listOf("EUR"),
                        selectedCurrency = "EUR",
                        anchorDate = LocalDate.of(2026, 8, 1),
                        series = series,
                        canNavigateNext = true,
                        isLoading = false,
                    ),
                    onPeriodSelected = {},
                    onCurrencySelected = {},
                    onPrevious = {},
                    onNext = { nextCalls += 1 },
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText("01/08").assertIsDisplayed()
        composeRule.onNodeWithText("03/08").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Balance de EUR desde 01/08/2026 hasta 03/08/2026"
        ).performTouchInput { swipeLeft() }

        assertEquals(1, nextCalls)
    }
}
