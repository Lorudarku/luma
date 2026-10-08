package dev.okami.luma.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatisticsPeriod {
    WEEK,
    MONTH,
    YEAR,
}

data class BalancePoint(
    val date: LocalDate,
    val balanceMinor: Long,
)

data class BalanceSeries(
    val currencyCode: String,
    val period: StatisticsPeriod,
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    val points: List<BalancePoint>,
)

/**
 * Calcula el saldo que queda inmediatamente después de cada movimiento.
 *
 * Entrada: transacciones de una o varias monedas.
 * Salida: mapa de identificador a saldo acumulado dentro de su propia moneda.
 * Errores: lanza [ArithmeticException] si una suma o resta excede [Long].
 * Pasos: ordena cronológicamente, mantiene un acumulador por moneda y registra cada resultado.
 */
fun calculateRunningBalances(transactions: List<Transaction>): Map<String, Long> {
    val balancesByCurrency = mutableMapOf<String, Long>()
    val balancesByTransaction = mutableMapOf<String, Long>()

    // 1. El identificador resuelve de forma determinista operaciones con el mismo instante.
    transactions.sortedWith(compareBy<Transaction> { it.occurredAt }.thenBy { it.id })
        .forEach { transaction ->
            // 2. Cada divisa mantiene su propia cuenta y nunca se convierte implícitamente.
            val current = balancesByCurrency[transaction.money.currencyCode] ?: 0L
            val updated = when (transaction.type) {
                TransactionType.INCOME -> Math.addExact(current, transaction.money.amountMinor)
                TransactionType.EXPENSE -> Math.subtractExact(current, transaction.money.amountMinor)
            }
            balancesByCurrency[transaction.money.currencyCode] = updated
            balancesByTransaction[transaction.id] = updated
        }
    return balancesByTransaction
}

/**
 * Construye una serie de saldo histórico para una semana, mes o año concreto.
 *
 * Entradas: transacciones, moneda, periodo, fecha de referencia y zona horaria.
 * Salida: puntos diarios para semana/mes o mensuales para año, incluyendo saldo previo acumulado.
 * Errores: lanza [ArithmeticException] ante overflow monetario.
 * Pasos: delimita el periodo, calcula el saldo anterior y aplica en orden cada operación vencida.
 */
fun calculateBalanceSeries(
    transactions: List<Transaction>,
    currencyCode: String,
    period: StatisticsPeriod,
    anchorDate: LocalDate,
    zoneId: ZoneId,
): BalanceSeries {
    val (rangeStart, rangeEnd) = periodRange(period, anchorDate)
    val datedTransactions = transactions
        .asSequence()
        .filter { it.money.currencyCode == currencyCode }
        .map { it to it.occurredAt.atZone(zoneId).toLocalDate() }
        .sortedWith(compareBy<Pair<Transaction, LocalDate>> { it.second }.thenBy { it.first.id })
        .toList()

    // 1. El saldo inicial del gráfico conserva toda la historia anterior al periodo visible.
    var balance = 0L
    datedTransactions.filter { (_, date) -> date < rangeStart }.forEach { (transaction, _) ->
        balance = applyTransaction(balance, transaction)
    }

    // 2. Los puntos de año representan cierres mensuales; los demás, cierres diarios.
    val pointDates = when (period) {
        StatisticsPeriod.WEEK,
        StatisticsPeriod.MONTH,
        -> generateSequence(rangeStart) { current ->
            current.plusDays(1).takeIf { it <= rangeEnd }
        }.toList()

        StatisticsPeriod.YEAR -> (1..12).map { month ->
            YearMonth.of(anchorDate.year, month).atEndOfMonth()
        }
    }

    var transactionIndex = datedTransactions.indexOfFirst { (_, date) -> date >= rangeStart }
        .let { if (it == -1) datedTransactions.size else it }
    val points = pointDates.map { pointDate ->
        // 3. Cada punto incorpora todas las operaciones ocurridas hasta su cierre.
        while (
            transactionIndex < datedTransactions.size &&
            datedTransactions[transactionIndex].second <= pointDate
        ) {
            val (transaction, date) = datedTransactions[transactionIndex]
            if (date <= rangeEnd) balance = applyTransaction(balance, transaction)
            transactionIndex += 1
        }
        BalancePoint(pointDate, balance)
    }

    return BalanceSeries(currencyCode, period, rangeStart, rangeEnd, points)
}

/**
 * Devuelve los límites naturales del periodo que contiene la fecha indicada.
 *
 * Entrada: granularidad y fecha de referencia.
 * Salida: par inclusivo inicio-fin; las semanas empiezan en lunes.
 */
fun periodRange(period: StatisticsPeriod, anchorDate: LocalDate): Pair<LocalDate, LocalDate> =
    when (period) {
        StatisticsPeriod.WEEK -> {
            val start = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            start to start.plusDays(6)
        }

        StatisticsPeriod.MONTH -> {
            val month = YearMonth.from(anchorDate)
            month.atDay(1) to month.atEndOfMonth()
        }

        StatisticsPeriod.YEAR ->
            LocalDate.of(anchorDate.year, 1, 1) to LocalDate.of(anchorDate.year, 12, 31)
    }

/**
 * Aplica el signo del movimiento a un saldo usando aritmética comprobada.
 *
 * Entradas: saldo anterior y transacción de la misma moneda.
 * Salida: saldo posterior exacto.
 */
private fun applyTransaction(balance: Long, transaction: Transaction): Long =
    when (transaction.type) {
        TransactionType.INCOME -> Math.addExact(balance, transaction.money.amountMinor)
        TransactionType.EXPENSE -> Math.subtractExact(balance, transaction.money.amountMinor)
    }
