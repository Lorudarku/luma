package dev.okami.luma.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAxisScaleTest {
    @Test
    /**
     * Comprueba cinco guías exactas y selección compacta de fechas para una serie variable.
     */
    fun `builds exact axis labels for changing series`() {
        val series = series(listOf(-1_000L, 0L, 1_000L, 2_000L, 3_000L))

        val scale = calculateChartAxisScale(series)

        assertEquals(listOf(3_000L, 2_000L, 1_000L, 0L, -1_000L), scale.yValuesMinor)
        assertEquals(listOf(1, 3, 5), scale.xPoints.map { it.date.dayOfMonth })
    }

    @Test
    /**
     * Comprueba que una serie plana conserva valores y no intenta dividir entre cero.
     */
    fun `keeps flat and very large series stable`() {
        val scale = calculateChartAxisScale(series(List(3) { Long.MAX_VALUE }))

        assertEquals(List(5) { Long.MAX_VALUE }, scale.yValuesMinor)
        assertEquals(3, scale.xPoints.size)
    }

    /**
     * Construye una serie mensual compacta a partir de saldos de prueba.
     */
    private fun series(values: List<Long>) = BalanceSeries(
        currencyCode = "EUR",
        period = StatisticsPeriod.MONTH,
        rangeStart = LocalDate.of(2026, 8, 1),
        rangeEnd = LocalDate.of(2026, 8, values.size),
        points = values.mapIndexed { index, value ->
            BalancePoint(LocalDate.of(2026, 8, index + 1), value)
        },
    )
}
