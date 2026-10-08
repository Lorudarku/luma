package dev.okami.luma.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.okami.luma.R
import dev.okami.luma.security.VaultStatus

/** Presenta el estado y la etapa pública del fallo; nunca muestra excepciones ni datos de la base cerrada. */
@Composable
fun LockedScreen(status: VaultStatus, onUnlock: () -> Unit, onRecover: () -> Unit = {}) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(when (status) {
                VaultStatus.KEY_ERROR -> R.string.vault_key_error
                VaultStatus.DATABASE_ERROR -> R.string.vault_database_error
                VaultStatus.ERROR -> R.string.vault_error
                else -> R.string.vault_private
            }))
            Spacer(Modifier.height(24.dp))
            if (status == VaultStatus.OPENING) CircularProgressIndicator()
            else Button(onClick = onUnlock) { Text(stringResource(R.string.vault_unlock)) }
            if (status != VaultStatus.OPENING) TextButton(onClick = onRecover) { Text(stringResource(R.string.security_import)) }
        }
    }
}
