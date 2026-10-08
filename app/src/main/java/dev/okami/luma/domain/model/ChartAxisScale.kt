package dev.okami.luma.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

data class ChartAxisScale(
    val yValuesMinor: List<Long>,
    val xPoints: List<BalancePoint>,
)

/**
 * Deriva etiquetas de ejes compactas a partir de una serie monetaria exacta.
 *
 * Entrada: serie calculada para una moneda y periodo.
 * Salida: cinco valores Y descendentes y hasta tres puntos representativos del eje X.
 * Efectos: ninguno; [BigDecimal] evita overflow al interpolar extremos de [Long].
 */
fun calculateChartAxisScale(series: BalanceSeries): ChartAxisScale {
    if (series.points.isEmpty()) return ChartAxisScale(emptyList(), emptyList())

    // 1. Una serie plana conserva el valor real en todas las guías y evita división por cero.
    val minimum = series.points.minOf { it.balanceMinor }
    val maximum = series.points.maxOf { it.balanceMinor }
    val yValues = if (minimum == maximum) {
        List(5) { maximum }
    } else {
        val minimumDecimal = BigDecimal.valueOf(minimum)
        val span = BigDecimal.valueOf(maximum).subtract(minimumDecimal)
        (4 downTo 0).map { step ->
            minimumDecimal
                .add(span.multiply(BigDecimal.valueOf(step.toLong())).divide(FOUR, 0, RoundingMode.HALF_UP))
                .longValueExact()
        }
    }

    // 2. Inicio, centro y fin mantienen el eje legible incluso en meses con 31 puntos.
    val candidateIndices = listOf(0, series.points.lastIndex / 2, series.points.lastIndex).distinct()
    return ChartAxisScale(
        yValuesMinor = yValues,
        xPoints = candidateIndices.map(series.points::get),
    )
}

private val FOUR = BigDecimal.valueOf(4L)
