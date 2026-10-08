package dev.okami.luma.data.mapper

import dev.okami.luma.data.local.CategoryEntity
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.model.normalizeCategoryName

/**
 * Convierte una categoría de dominio al esquema persistible.
 *
 * Entrada: categoría ya validada.
 * Salida: entidad con nombre normalizado para el índice único.
 */
fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    normalizedName = normalizeCategoryName(name),
    emoji = emoji,
    isDefault = isDefault,
)

/**
 * Reconstruye una categoría de dominio desde Room.
 *
 * Entrada: fila local.
 * Salida: categoría protegida por las invariantes del dominio.
 */
fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    emoji = emoji,
    isDefault = isDefault,
)
