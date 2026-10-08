package dev.okami.luma.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Preferencia financiera persistida exclusivamente dentro de la base cifrada. */
@Entity(tableName = "saved_filters")
data class SavedFilterEntity(@PrimaryKey val name: String, val specification: String)
