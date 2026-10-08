package dev.okami.luma.ui.security

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.okami.luma.R
import dev.okami.luma.security.BackupRepository
import dev.okami.luma.security.BackupSummary
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SecurityCompletion { EXPORTED, RESTORED }
data class SecurityUiState(val busy: Boolean = false, val summary: BackupSummary? = null,
    val message: Int? = null, val completion: SecurityCompletion? = null)

/** Propietario de copias dentro de la sesión: la rotación no cancela operaciones ni guarda secretos en SavedState. */
class SecurityViewModel(private val repository: BackupRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SecurityUiState())
    val state = mutableState.asStateFlow()
    private var staged: File? = null
    private var password: CharArray? = null

    /**
     * Inicia exportación o verificación; entradas documento y secreto mutable, nunca se conserva un String.
     * Efectos: protege doble envío antes de lanzar IO; guarda contraseña solo mientras se espera confirmar importación.
     */
    fun process(document: Pair<Uri, Boolean>, secret: CharArray) {
        if (state.value.busy) { secret.fill('\u0000'); return }
        discardPreview()
        mutableState.value = SecurityUiState(busy = true)
        viewModelScope.launch {
            var retained = false
            try {
                // 1. Exportar consume el secreto; verificar conserva solo el array para la confirmación.
                if (document.second) {
                    repository.export(document.first, secret)
                    mutableState.update { it.copy(message = R.string.security_exported, completion = SecurityCompletion.EXPORTED) }
                } else {
                    val file = repository.stage(document.first)
                    staged = file
                    val summary = repository.inspect(file, secret)
                    password = secret
                    retained = true
                    mutableState.update { it.copy(summary = summary) }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                discardPreview()
                mutableState.update { it.copy(message = R.string.security_failed) }
            } finally {
                if (!retained) secret.fill('\u0000')
                if (!currentCoroutineContext().isActive) discardPreview()
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Restaura únicamente la copia verificada con su contraseña original; sin entradas financieras nuevas.
     * Efectos: sustitución transaccional, progreso retenido en rotación y evento de éxito consumible una vez.
     */
    fun confirmRestore() {
        if (state.value.busy || state.value.summary == null) return
        val file = staged ?: return
        val secret = password ?: return
        password = null
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                repository.restore(file, secret)
                staged = null
                mutableState.update { it.copy(summary = null, message = R.string.security_restored, completion = SecurityCompletion.RESTORED) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutableState.update { it.copy(summary = null, message = R.string.security_failed) }
            } finally {
                secret.fill('\u0000')
                discardPreview()
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Cancela la previsualización si no hay escritura; efectos: borra staging/contraseña sin tocar la base. */
    fun cancelPreview() {
        if (state.value.busy) return
        discardPreview()
        mutableState.update { it.copy(summary = null) }
    }

    /** Consume el resultado sin suspender; salida evento o null; evita repetir la promoción al rotar. */
    fun consumeCompletion(): SecurityCompletion? {
        val result = state.value.completion
        mutableState.update { it.copy(completion = null) }
        return result
    }

    /** Muestra un fallo de validación de contraseña; entrada recurso de texto; no registra contenido. */
    fun showMessage(message: Int) { mutableState.update { it.copy(message = message) } }

    /** Limpia referencias y staging cifrado; sin entradas; solo elimina el archivo propiedad de esta operación. */
    private fun discardPreview() {
        password?.fill('\u0000')
        password = null
        staged?.delete()
        staged = null
    }

    /** Al bloquear se cancela el scope y se destruyen secretos; la sesión no conserva la previsualización. */
    override fun onCleared() { discardPreview() }

    companion object {
        /** Construye el propietario con el repositorio de esta sesión; salida factory sin referencias globales. */
        fun factory(repository: BackupRepository) = object : ViewModelProvider.Factory {
            /** Crea exclusivamente el coordinador de copias; entrada clase solicitada; salida ViewModel de sesión. */
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SecurityViewModel::class.java))
                return SecurityViewModel(repository) as T
            }
        }
    }
}
