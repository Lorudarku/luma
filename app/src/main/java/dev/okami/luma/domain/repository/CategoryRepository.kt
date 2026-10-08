package dev.okami.luma.domain.repository

import dev.okami.luma.domain.model.Category
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    /**
     * Observa el catálogo local de categorías.
     *
     * Salida: flujo ordenado de categorías predeterminadas y personalizadas.
     */
    fun observeCategories(): Flow<List<Category>>

    /**
     * Persiste una categoría nueva.
     *
     * Entrada: categoría validada.
     * Efectos: añade una fila o informa de nombre duplicado.
     */
    suspend fun add(category: Category)

    /**
     * Actualiza una categoría existente.
     *
     * Entrada: categoría con ID persistido.
     * Efectos: reemplaza sus datos visibles.
     */
    suspend fun update(category: Category)

    /**
     * Elimina una categoría y desvincula movimientos y reglas que la usaban.
     *
     * Entrada: identificador estable.
     * Efectos: operación local atómica que conserva el histórico financiero.
     */
    suspend fun delete(id: String)
}
