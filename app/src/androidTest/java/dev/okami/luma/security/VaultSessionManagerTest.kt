package dev.okami.luma.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.okami.luma.LumaApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Comprueba el propietario real con claves ficticias; no solicita ni modifica credenciales del dispositivo. */
class VaultSessionManagerTest {
    private val application = ApplicationProvider.getApplicationContext<LumaApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Abrir y bloquear limpia el store financiero, cierra Room y borra el buffer propio de clave. */
    @Test fun lockingAnOpenSessionClearsItsViewModelsAndClosesStorage() = runBlocking {
        System.loadLibrary("sqlcipher")
        val name = "test-session-${java.util.UUID.randomUUID()}.db"
        val key = ByteArray(32) { 7 }
        val database = EncryptedDatabase.build(application, name, key.copyOf())
        database.openHelper.writableDatabase
        val owner = ViewModelStore()
        val model = VaultSessionManager(application, { key }, { _, _, _ -> database })
        val child = ClearedModel()
        try {
            instrumentation.runOnMainSync { owner.put("vault", model); model.authenticated() }
            withTimeout(5000) { model.status.first { it == VaultStatus.OPEN } }
            assertTrue(key.all { it == 0.toByte() })
            instrumentation.runOnMainSync {
                requireNotNull(model.session).viewModelStore.put("financial", child)
                model.lock()
                assertNull(model.session)
                assertEquals(VaultStatus.LOCKED, model.status.value)
                assertTrue(child.cleared)
            }
            withTimeout(5000) { while (database.isOpen) delay(10) }
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            database.close(); application.deleteDatabase(name)
        }
    }

    /** Destruir el propietario durante una apertura invalida su ticket y cierra su resultado tardío. */
    @Test fun clearingOwnerDuringOpeningNeverPublishesALateSession() = runBlocking {
        System.loadLibrary("sqlcipher")
        val name = "test-late-session-${java.util.UUID.randomUUID()}.db"
        val key = ByteArray(32) { 9 }
        val database = EncryptedDatabase.build(application, name, key.copyOf())
        database.openHelper.writableDatabase
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = ViewModelStore()
        val model = VaultSessionManager(application, { key }, { _, _, _ ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            database
        })
        try {
            instrumentation.runOnMainSync { owner.put("vault", model); model.authenticated() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { owner.clear() }
            release.countDown()
            withTimeout(5000) { while (database.isOpen) delay(10) }
            instrumentation.runOnMainSync {
                assertNull(model.session)
                assertEquals(VaultStatus.LOCKED, model.status.value)
            }
            assertTrue(key.all { it == 0.toByte() })
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { owner.clear() }
            database.close(); application.deleteDatabase(name)
        }
    }

    private class ClearedModel : ViewModel() {
        var cleared = false
        /** Hace observable la destrucción del estado financiero ficticio de la sesión. */
        override fun onCleared() { cleared = true }
    }
}
