package dev.okami.luma.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["occurredAtEpochMillis"]),
        Index(value = ["categoryId"]),
        Index(value = ["currencyCode", "occurredAtEpochMillis"]),
        Index(value = ["categoryId", "occurredAtEpochMillis"]),
        Index(
            value = ["recurringExpenseId", "recurrenceDateEpochDay"],
            unique = true,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val amountMinor: Long,
    val currencyCode: String,
    val occurredAtEpochMillis: Long,
    val description: String?,
    val categoryId: String? = null,
    val recurringExpenseId: String? = null,
    val recurrenceDateEpochDay: Long? = null,
)
