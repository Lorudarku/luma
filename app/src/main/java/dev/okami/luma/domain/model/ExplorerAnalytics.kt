package dev.okami.luma.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class AggregateRow(val day: String, val currencyCode: String, val type: String,
    val categoryId: String?, val amountMinor: Long, val count: Long)
data class FlowPoint(val date: LocalDate, val income: Long, val expense: Long)
data class ExplorerDashboard(val currency: String, val income: Long, val expense: Long,
    val count: Long, val points: List<BalancePoint>, val realPoints: List<BalancePoint>,
    val flow: List<FlowPoint>, val categoryExpenses: Map<String?, Long>, val previousNet: Long,
    val difference: Long, val percentage: BigDecimal?,
    val categoryMonthExpenses: Map<String?, Long> = emptyMap(),
    val categoryYearExpenses: Map<String?, Long> = emptyMap(),
    val categoryPercentages: Map<String?, BigDecimal> = emptyMap())

/** Calcula un periodo anterior comparable sin incluir días equivalentes todavía no transcurridos. */
fun previousFilter(filter: TransactionFilter, today: LocalDate): TransactionFilter {
    val end = filter.end ?: today
    val start = filter.start ?: end.withDayOfMonth(1)
    val days = ChronoUnit.DAYS.between(start, end)
    val previousStart = shiftedRange(filter, -1, today).first
    val previousEnd = previousStart.plusDays(days).coerceAtMost(start.minusDays(1))
    return filter.copy(start = previousStart, end = previousEnd)
}

/** Desplaza periodos naturales o rangos personalizados; el consumidor aplica la restricción de futuro. */
fun shiftedRange(filter: TransactionFilter, direction: Int, today: LocalDate): Pair<LocalDate, LocalDate> {
    require(direction == -1 || direction == 1)
    val end = filter.end ?: today
    val start = filter.start ?: end.withDayOfMonth(1)
    val anchor = when (filter.period) {
        StatisticsPeriod.WEEK -> start.plusWeeks(direction.toLong())
        StatisticsPeriod.MONTH -> start.plusMonths(direction.toLong())
        StatisticsPeriod.YEAR -> start.plusYears(direction.toLong())
        null -> start.plusDays((ChronoUnit.DAYS.between(start, end) + 1) * direction)
    }
    return filter.period?.let { periodRange(it, anchor) }
        ?: (anchor to anchor.plusDays(ChronoUnit.DAYS.between(start, end)))
}

/** Deriva cifras exactas y series acotadas desde agregados; no mezcla monedas ni dibuja el futuro. */
fun calculateExplorerDashboard(rows: List<AggregateRow>, history: List<AggregateRow>, previous: List<AggregateRow>,
    filter: TransactionFilter, currency: String, today: LocalDate): ExplorerDashboard {
    val selected = rows.filter { it.currencyCode == currency }
    val real = history.filter { it.currencyCode == currency }
    val prior = previous.filter { it.currencyCode == currency }
    val end = (filter.end ?: today).coerceAtMost(today)
    val start = filter.start ?: real.minOfOrNull { LocalDate.parse(it.day) } ?: end.withDayOfMonth(1)
    // 1. Días en rangos cortos y cierres mensuales en largos; nunca se proyecta el saldo al futuro.
    val days = ChronoUnit.DAYS.between(start, end).coerceAtLeast(0) + 1
    val dates = if (days <= 90) generateSequence(start) { it.plusDays(1).takeIf { next -> next <= end } }.toList()
    else {
        val first = java.time.YearMonth.from(start)
        val months = ChronoUnit.MONTHS.between(first, java.time.YearMonth.from(end)) + 1
        val step = ((months + 119) / 120).coerceAtLeast(1)
        generateSequence(first.plusMonths(step - 1).atEndOfMonth()) { it.plusDays(1).plusMonths(step - 1).let { d -> java.time.YearMonth.from(d).atEndOfMonth() }.takeIf { next -> next <= end } }
            .takeWhile { it <= end }.toList().plus(end).distinct()
    }
    val income = sumAmounts(selected.filter { it.type == TransactionType.INCOME.name })
    val expense = sumAmounts(selected.filter { it.type == TransactionType.EXPENSE.name })
    val net = Math.subtractExact(income, expense)
    val previousNet = netAmounts(prior)
    val difference = Math.subtractExact(net, previousNet)
    val categoryExpenses = selected.filter { it.type == TransactionType.EXPENSE.name }
        .groupBy { it.categoryId }.mapValues { sumAmounts(it.value) }
    // 2. El saldo real usa el histórico completo; la selección comienza en cero.
    val pointDates = listOf(start.minusDays(1)) + dates
    val points = cumulativePoints(selected, pointDates)
    val realPoints = cumulativePoints(real, pointDates)
    val byDay = selected.groupBy { LocalDate.parse(it.day) }.toSortedMap().entries.iterator()
    var entry = if (byDay.hasNext()) byDay.next() else null
    var bucketStart = start
    val flow = dates.map { date ->
        val bucket = mutableListOf<AggregateRow>()
        while (entry != null && requireNotNull(entry).key <= date) {
            if (requireNotNull(entry).key >= bucketStart) bucket.addAll(requireNotNull(entry).value)
            entry = if (byDay.hasNext()) byDay.next() else null
        }
        bucketStart = date.plusDays(1)
        FlowPoint(date, sumAmounts(bucket.filter { it.type == "INCOME" }), sumAmounts(bucket.filter { it.type == "EXPENSE" }))
    }
    val percentage = if (previousNet == 0L) null else BigDecimal.valueOf(difference).multiply(BigDecimal(100))
        .divide(BigDecimal.valueOf(previousNet).abs(), 2, RoundingMode.HALF_UP)
    return ExplorerDashboard(currency, income, expense, selected.fold(0L) { total, row -> Math.addExact(total, row.count) },
        points, realPoints, flow, categoryExpenses, previousNet, difference, percentage,
        categoryAmounts(selected.filter { LocalDate.parse(it.day) >= today.withDayOfMonth(1) }),
        categoryAmounts(selected.filter { LocalDate.parse(it.day) >= today.withDayOfYear(1) }),
        categoryExpenses.mapValues { (_, amount) -> if (expense == 0L) BigDecimal.ZERO else BigDecimal.valueOf(amount)
            .multiply(BigDecimal(100)).divide(BigDecimal.valueOf(expense), 1, RoundingMode.HALF_UP) })
}

/** Agrupa únicamente gastos del subconjunto recibido; las sumas detectan overflow. */
private fun categoryAmounts(rows: List<AggregateRow>): Map<String?, Long> = rows
    .filter { it.type == TransactionType.EXPENSE.name }.groupBy { it.categoryId }.mapValues { sumAmounts(it.value) }

/** Recorre agregados ordenados una vez para evitar recalcular el histórico por cada punto. */
private fun cumulativePoints(rows: List<AggregateRow>, dates: List<LocalDate>): List<BalancePoint> {
    val sorted = rows.map { LocalDate.parse(it.day) to it }.sortedBy { it.first }
    var index = 0
    var balance = 0L
    return dates.map { date ->
        while (index < sorted.size && sorted[index].first <= date) {
            val row = sorted[index++].second
            balance = if (row.type == "INCOME") Math.addExact(balance, row.amountMinor) else Math.subtractExact(balance, row.amountMinor)
        }
        BalancePoint(date, balance)
    }
}

/** Suma enteros comprobados; propaga overflow para impedir cifras truncadas. */
private fun sumAmounts(rows: List<AggregateRow>): Long = rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }

/** Aplica ingresos y gastos sin recurrir a aritmética flotante. */
private fun netAmounts(rows: List<AggregateRow>): Long = rows.fold(0L) { sum, row ->
    if (row.type == "INCOME") Math.addExact(sum, row.amountMinor) else Math.subtractExact(sum, row.amountMinor)
}
