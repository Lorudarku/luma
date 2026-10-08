package dev.okami.luma.ui.explorer

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.okami.luma.data.*
import dev.okami.luma.data.local.LumaDatabase
import dev.okami.luma.domain.model.*
import dev.okami.luma.domain.repository.CategoryRepository
import dev.okami.luma.security.EncryptedDatabase
import dev.okami.luma.ui.theme.LumaTheme
import dev.okami.luma.ui.transactions.TransactionsRoute
import dev.okami.luma.ui.transactions.TransactionsViewModel
import java.time.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExplorerScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "test-explorer-${java.util.UUID.randomUUID()}.db"
    private val store = ViewModelStore()
    private var room: LumaDatabase? = null

    /** La ruta usada por la app carga Paging/SQLCipher, expone edición y conserva el filtro al mostrar el diálogo. */
    @Test fun actualPagedRouteShowsMovementAndOpensEditor() {
        System.loadLibrary("sqlcipher")
        val database = EncryptedDatabase.build(context, name, ByteArray(32) { 7 })
        room = database
        database.openHelper.writableDatabase.execSQL("INSERT INTO transactions(id,type,amountMinor,currencyCode,occurredAtEpochMillis,description) VALUES ('fictional','EXPENSE',1234,'BHD',1788566400000,'Compra ficticia')")
        val clock = Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)
        lateinit var explorer: ExplorerViewModel
        lateinit var editor: TransactionsViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            explorer = ExplorerViewModel(ExplorerRepositoryImpl(database.explorerDao()), Categories(), clock)
            editor = TransactionsViewModel(TransactionRepositoryImpl(database.transactionDao()), Categories(),
                RecurringExpenseRepositoryImpl(database, database.recurringExpenseDao(), database.transactionDao()), clock, paged = true)
            store.put("explorer", explorer); store.put("editor", editor)
        }
        compose.setContent { LumaTheme { TransactionsRoute(editor, explorer = explorer) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Compra ficticia").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Compra ficticia").assertIsDisplayed()
        compose.onNodeWithText("Editar").performClick()
        compose.runOnIdle {
            assertEquals("fictional", editor.uiState.value.form.editingId)
            assertEquals("1,234", editor.uiState.value.form.amountText)
            assertTrue(editor.uiState.value.isFormVisible)
            assertEquals(LocalDate.of(2026, 9, 1), explorer.filter.value.start)
        }
    }

    /** Cancela estados de sesión antes de cerrar y borra únicamente los artefactos ficticios del test. */
    @After fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        room?.close()
        context.deleteDatabase(name)
    }

    private class Categories : CategoryRepository {
        /** Catálogo ficticio vacío para aislar Paging y el editor. */
        override fun observeCategories(): Flow<List<Category>> = flowOf(emptyList())
        /** No se usa en este escenario. */
        override suspend fun add(category: Category) = Unit
        /** No se usa en este escenario. */
        override suspend fun update(category: Category) = Unit
        /** No se usa en este escenario. */
        override suspend fun delete(id: String) = Unit
    }
}
