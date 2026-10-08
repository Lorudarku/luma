package dev.okami.luma.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recurring_expenses",
    indices = [Index(value = ["categoryId"])],
)
data class RecurringExpenseEntity(
    @PrimaryKey val id: String,
    val amountMinor: Long,
    val currencyCode: String,
    val dayOfMonth: Int,
    val startDateEpochDay: Long,
    val description: String?,
    val categoryId: String?,
    val active: Boolean,
)
