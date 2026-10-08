package dev.okami.luma.data

import androidx.room.withTransaction
import dev.okami.luma.data.local.CategoryDao
import dev.okami.luma.data.local.LumaDatabase
import dev.okami.luma.data.local.RecurringExpenseDao
import dev.okami.luma.data.local.TransactionDao
import dev.okami.luma.data.mapper.toDomain
import dev.okami.luma.data.mapper.toEntity
import dev.okami.luma.domain.model.Category
import dev.okami.luma.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryRepositoryImpl(
    private val database: LumaDatabase,
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val recurringExpenseDao: RecurringExpenseDao,
) : CategoryRepository {
    /**
     * Convierte el catálogo Room en modelos de dominio reactivos.
     *
     * Salida: flujo ordenado de categorías.
     */
    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { categories -> categories.map { it.toDomain() } }

    /**
     * Inserta una categoría con su clave de nombre normalizada.
     *
     * Entrada: categoría validada.
     * Efectos: escribe una fila local.
     */
    override suspend fun add(category: Category) {
        categoryDao.insert(category.toEntity())
    }

    /**
     * Actualiza una categoría y comprueba que siga existiendo.
     *
     * Entrada: categoría editada.
     * Efectos: modifica exactamente una fila.
     */
    override suspend fun update(category: Category) {
        check(categoryDao.update(category.toEntity()) == 1) {
            "Category '${category.id}' does not exist."
        }
    }

    /**
     * Elimina una categoría sin dejar referencias huérfanas.
     *
     * Entrada: identificador elegido por el usuario.
     * Efectos: limpia movimientos y reglas, y después borra la categoría atómicamente.
     */
    override suspend fun delete(id: String) {
        database.withTransaction {
            // 1. Resolver primero la entidad permite detectar una selección desactualizada.
            val category = checkNotNull(categoryDao.findById(id)) {
                "Category '$id' does not exist."
            }
            // 2. Las referencias se convierten en null antes de retirar el catálogo.
            transactionDao.clearCategory(id)
            recurringExpenseDao.clearCategory(id)
            check(categoryDao.delete(category) == 1)
        }
    }
}
