package dev.okami.luma

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.okami.luma.security.VaultSessionManager
import dev.okami.luma.security.VaultStatus
import dev.okami.luma.ui.LumaApp
import dev.okami.luma.ui.security.LockedScreen
import dev.okami.luma.ui.theme.LumaTheme
import dev.okami.luma.ui.transactions.TransactionsViewModel
import dev.okami.luma.ui.categories.CategoriesViewModel
import dev.okami.luma.ui.recurring.RecurringExpensesViewModel
import dev.okami.luma.ui.statistics.StatisticsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class MainActivity : FragmentActivity() {
    private val vault: VaultSessionManager by viewModels()
    private var authenticating = false
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vault.document = uri to true
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vault.document = uri to false
        else if (vault.recovering) vault.cancelRecovery()
    }
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authenticating = false
        if (result.resultCode == RESULT_OK && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) vault.authenticated()
    }

    /** Protege la ventana antes de montar Compose; crea los ViewModels solo tras desbloquear. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        setContent {
            // El bloqueo debe desmontar también los secretos de Compose cuando la Activity está parada.
            val status by vault.status.collectAsState()
            LumaTheme {
                val session = vault.session
                if (status == VaultStatus.OPEN && session != null) {
                    key(session) {
                        val container = session.container
                        val transactions = ViewModelProvider(session, TransactionsViewModel.factory(container.transactionRepository, container.categoryRepository, container.recurringExpenseRepository, paged = true))[TransactionsViewModel::class.java]
                        val categories = ViewModelProvider(session, CategoriesViewModel.factory(container.categoryRepository, container.transactionRepository, paged = true))[CategoriesViewModel::class.java]
                        val recurring = ViewModelProvider(session, RecurringExpensesViewModel.factory(container.recurringExpenseRepository, container.categoryRepository))[RecurringExpensesViewModel::class.java]
                        val explorer = ViewModelProvider(session, object : androidx.lifecycle.ViewModelProvider.Factory {
                            /** Construye el explorador dentro del propietario de sesión; no conserva datos tras bloquear. */
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                                dev.okami.luma.ui.explorer.ExplorerViewModel(container.explorerRepository, container.categoryRepository) as T
                        })[dev.okami.luma.ui.explorer.ExplorerViewModel::class.java]
                        val security = ViewModelProvider(session,
                            dev.okami.luma.ui.security.SecurityViewModel.factory(
                                dev.okami.luma.security.EncryptedBackupRepository(applicationContext, session.database)
                            ))[dev.okami.luma.ui.security.SecurityViewModel::class.java]
                        if (vault.recovering) {
                            androidx.activity.compose.BackHandler { vault.cancelRecovery() }
                            dev.okami.luma.ui.security.SecurityScreen(
                                security,
                                vault.document, onConsumed = { vault.document = null }, onExport = {},
                                onImport = { importDocument.launch(arrayOf("*/*")) }, onBack = vault::cancelRecovery,
                                onErase = {}, recoveryOnly = true, onRestored = vault::completeRecovery)
                        } else LumaApp(transactions, recurring, explorer, categories,
                            securityContent = { onBack ->
                                dev.okami.luma.ui.security.SecurityScreen(
                                    security,
                                    vault.document, onConsumed = { vault.document = null },
                                    onExport = { exportDocument.launch("luma.lumabackup") },
                                    onImport = { vault.beginRecovery(); importDocument.launch(arrayOf("application/octet-stream", "*/*")) },
                                    onBack = onBack, onErase = vault::erase)
                            }, showSecurityInitially = vault.document != null)
                    }
                } else LockedScreen(status, ::authenticate, onRecover = {
                    if (!vault.recovering) vault.beginRecovery()
                    importDocument.launch(arrayOf("*/*"))
                })
            }
        }
    }

    /** Prepara Keystore en IO y pide autenticación; no exige hardware ni abre anticipadamente Room. */
    private fun authenticate() {
        if (authenticating) return
        authenticating = true
        lifecycleScope.launch {
            try {
                // 1. Se exige una credencial configurada, no una gama de hardware determinada.
                withContext(Dispatchers.IO) { vault.keyManager.prepare() }
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    authenticating = false
                    return@launch
                }
                if (Build.VERSION.SDK_INT < 30) {
                    @Suppress("DEPRECATION")
                    val intent = getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(getString(R.string.vault_title), getString(R.string.vault_unlock))
                    credential.launch(requireNotNull(intent))
                } else {
                    // 2. Cada entrada exige un diálogo nuevo además de la restricción Keystore.
                    val prompt = BiometricPrompt(this@MainActivity, ContextCompat.getMainExecutor(this@MainActivity), object : BiometricPrompt.AuthenticationCallback() {
                        /** Autoriza una apertura después de la confirmación del sistema. */
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            authenticating = false
                            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) vault.authenticated()
                        }
                        /** Una cancelación conserva el bloqueo y los archivos actuales. */
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { authenticating = false }
                    })
                    prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                        .setTitle(getString(R.string.vault_title))
                        .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                        .build())
                }
            } catch (error: CancellationException) {
                authenticating = false
                throw error
            } catch (_: Exception) {
                authenticating = false
                vault.fail()
            }
        }
    }

    /** Bloquea al abandonar la aplicación; rotación no destruye la sesión vigente. */
    override fun onStop() {
        if (!isChangingConfigurations) vault.lock()
        super.onStop()
    }
}
