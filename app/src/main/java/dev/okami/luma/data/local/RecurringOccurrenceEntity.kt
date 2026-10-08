package dev.okami.luma.data.local

import androidx.room.Entity
import java.time.LocalDate

/** Meses contabilizados u omitidos: sobreviven a la eliminación o conversión de una cuota. */
@Entity(tableName = "recurring_occurrences", primaryKeys = ["ruleId", "monthEpoch"])
data class RecurringOccurrenceEntity(val ruleId: String, val monthEpoch: Long)

/** Convierte una fecha en el mes civil desde enero del año cero; salida independiente del día/zona. */
fun recurrenceMonth(date: LocalDate): Long = date.year.toLong() * 12 + date.monthValue - 1
