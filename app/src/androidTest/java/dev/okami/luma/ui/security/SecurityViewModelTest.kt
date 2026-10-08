package dev.okami.luma.ui.security

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.okami.luma.security.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class SecurityViewModelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Verificar no restaura; dos clics se bloquean, confirmar usa el secreto original y limpia staging/arrays. */
    @Test fun verificationRequiresConfirmationAndBlocksRepeatedActions() = runBlocking {
        val repository = Backups()
        val model = SecurityViewModel(repository)
        val secret = "fictional password for test".toCharArray()
        val duplicate = "another fictional password".toCharArray()
        instrumentation.runOnMainSync {
            model.process(Uri.EMPTY to false, secret)
            model.process(Uri.EMPTY to false, duplicate)
        }
        assertTrue(duplicate.all { it == '\u0000' })
        repository.stageGate.complete(Unit)
        withTimeout(5000) { model.state.first { it.summary != null && !it.busy } }
        assertEquals(0, repository.restores)
        instrumentation.runOnMainSync { model.confirmRestore(); model.confirmRestore() }
        withTimeout(5000) { model.state.first { it.completion == SecurityCompletion.RESTORED && !it.busy } }
        assertEquals(1, repository.restores)
        assertTrue(secret.all { it == '\u0000' })
        assertFalse(repository.file.exists())
        instrumentation.runOnMainSync {
            assertEquals(SecurityCompletion.RESTORED, model.consumeCompletion())
            assertNull(model.consumeCompletion())
        }
    }

    /** Limpiar el store de sesión destruye contraseña y staging de una previsualización ya verificada. */
    @Test fun lockingSessionClearsPreviewAndPassword() = runBlocking {
        val repository = Backups()
        repository.stageGate.complete(Unit)
        val model = SecurityViewModel(repository)
        val store = ViewModelStore()
        val secret = "fictional password for test".toCharArray()
        instrumentation.runOnMainSync { store.put("security", model); model.process(Uri.EMPTY to false, secret) }
        withTimeout(5000) { model.state.first { it.summary != null && !it.busy } }
        instrumentation.runOnMainSync { store.clear() }
        assertTrue(secret.all { it == '\u0000' })
        assertFalse(repository.file.exists())
    }

    private class Backups : BackupRepository {
        val file = File.createTempFile("security-test-", ".lumabackup",
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir)
        val stageGate = CompletableDeferred<Unit>()
        var restores = 0
        /** No se utiliza en estos casos de importación. */
        override suspend fun export(uri: Uri, password: CharArray) = Unit
        /** Espera una señal para probar el bloqueo de envíos repetidos; salida archivo cifrado ficticio. */
        override suspend fun stage(uri: Uri): File { stageGate.await(); return file }
        /** Devuelve un resumen ficticio sin cambiar datos. */
        override suspend fun inspect(file: File, password: CharArray) = BackupSummary(1, 0, 0)
        /** Registra sustitución y limpia entradas, simulando el contrato del repositorio real. */
        override suspend fun restore(file: File, password: CharArray) { restores++; password.fill('\u0000'); file.delete() }
    }
}
