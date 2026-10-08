package dev.okami.luma

import android.app.Application
import dev.okami.luma.data.CategoryRepositoryImpl
import dev.okami.luma.data.RecurringExpenseRepositoryImpl
import dev.okami.luma.data.TransactionRepositoryImpl
import dev.okami.luma.data.ExplorerRepositoryImpl
import dev.okami.luma.domain.repository.ExplorerRepository
import dev.okami.luma.data.local.LumaDatabase
import dev.okami.luma.domain.repository.CategoryRepository
import dev.okami.luma.domain.repository.RecurringExpenseRepository
import dev.okami.luma.domain.repository.TransactionRepository

class LumaApplication : Application() {
    /**
     * Conecta dependencias a una base previamente desbloqueada.
     *
     * Entrada: Room cifrado de la sesión. Salida: contenedor limitado a esa sesión.
     * Efectos: no abre archivos ni retiene referencias globales.
     */
    fun createContainer(database: LumaDatabase): AppContainer = DefaultAppContainer(database)
}

interface AppContainer {
    val explorerRepository: ExplorerRepository
    val transactionRepository: TransactionRepository
    val categoryRepository: CategoryRepository
    val recurringExpenseRepository: RecurringExpenseRepository
}

private class DefaultAppContainer(private val database: LumaDatabase) : AppContainer {
    override val explorerRepository: ExplorerRepository by lazy { ExplorerRepositoryImpl(database.explorerDao()) }
    /**
     * Conecta el DAO generado con la implementación del contrato de dominio.
     *
     * Salida: repositorio local compartido por los ViewModels de la aplicación.
     * Efectos: crea la implementación solo al primer acceso y no realiza red.
     */
    override val transactionRepository: TransactionRepository by lazy {
        TransactionRepositoryImpl(database.transactionDao())
    }

    /**
     * Conecta el catálogo, las referencias financieras y Room para operaciones atómicas.
     *
     * Salida: repositorio local de categorías.
     */
    override val categoryRepository: CategoryRepository by lazy {
        CategoryRepositoryImpl(
            database = database,
            categoryDao = database.categoryDao(),
            transactionDao = database.transactionDao(),
            recurringExpenseDao = database.recurringExpenseDao(),
        )
    }

    /**
     * Conecta reglas mensuales y movimientos bajo la misma base de datos.
     *
     * Salida: repositorio que genera gastos vencidos de forma transaccional.
     */
    override val recurringExpenseRepository: RecurringExpenseRepository by lazy {
        RecurringExpenseRepositoryImpl(
            database = database,
            recurringExpenseDao = database.recurringExpenseDao(),
            transactionDao = database.transactionDao(),
        )
    }
}
