package dev.okami.luma.domain.model

import java.time.LocalDate
import dev.okami.luma.domain.validation.TransactionDateCodec

enum class RecurrenceFilter { ALL, NONE, LINKED, ACTIVE, INACTIVE }
enum class DescriptionFilter { ALL, PRESENT, EMPTY }
enum class TransactionOrder { NEWEST, OLDEST, LARGEST, SMALLEST }

/** Criterio común inmutable; los límites monetarios siempre pertenecen a una única moneda. */
data class TransactionFilter(
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val types: Set<TransactionType> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
    val uncategorized: Boolean = false,
    val currencies: Set<String> = emptySet(),
    val minimum: Long? = null,
    val maximum: Long? = null,
    val text: String = "",
    val recurrence: RecurrenceFilter = RecurrenceFilter.ALL,
    val description: DescriptionFilter = DescriptionFilter.ALL,
    val order: TransactionOrder = TransactionOrder.NEWEST,
    val period: StatisticsPeriod? = null,
) {
    init {
        require(start == null || start in TransactionDateCodec.MIN_DATE..TransactionDateCodec.MAX_DATE)
        require(end == null || end in TransactionDateCodec.MIN_DATE..TransactionDateCodec.MAX_DATE)
        require(categoryIds.size <= 1000 && categoryIds.all { it.isNotBlank() && it.length <= 200 })
        require(currencies.size <= 1000)
        currencies.forEach { currencyScale(it) }
        require(start == null || end == null || start <= end)
        require(minimum == null || minimum >= 0)
        require(maximum == null || maximum >= 0)
        require(minimum == null || maximum == null || minimum <= maximum)
        require((minimum == null && maximum == null && order !in setOf(TransactionOrder.LARGEST, TransactionOrder.SMALLEST)) || currencies.size == 1)
        require(text.length <= 200)
    }

    val isSelection: Boolean get() = types.isNotEmpty() || categoryIds.isNotEmpty() || uncategorized ||
        minimum != null || maximum != null || text.isNotEmpty() || recurrence != RecurrenceFilter.ALL || description != DescriptionFilter.ALL
}
