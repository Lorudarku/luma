package dev.okami.luma.domain.model

import java.util.Locale

const val MAX_CATEGORY_NAME_LENGTH = 40
const val MAX_CATEGORY_EMOJI_CODE_POINTS = 8

data class Category(
    val id: String,
    val name: String,
    val emoji: String,
    val isDefault: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Category id must not be blank." }
        require(name.isNotBlank() && name == name.trim()) {
            "Category name must be trimmed and non-blank."
        }
        require(name.length <= MAX_CATEGORY_NAME_LENGTH) {
            "Category name is too long."
        }
        require(emoji.isNotBlank() && emoji == emoji.trim()) {
            "Category emoji must be trimmed and non-blank."
        }
        require(emoji.codePointCount(0, emoji.length) <= MAX_CATEGORY_EMOJI_CODE_POINTS) {
            "Category emoji contains too many code points."
        }
    }
}

/**
 * Normaliza un nombre de categoría para comparar duplicados de forma estable.
 *
 * Entrada: nombre ya introducido por el usuario.
 * Salida: texto recortado y convertido a minúsculas independientes del locale.
 * Efectos: ninguno; no modifica el nombre visible.
 */
fun normalizeCategoryName(name: String): String = name.trim().lowercase(Locale.ROOT)
