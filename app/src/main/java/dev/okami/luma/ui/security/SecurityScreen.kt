package dev.okami.luma.ui.security

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.okami.luma.R
import dev.okami.luma.security.BackupSummary
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler

/** Ajustes privados: pide la contraseña después del selector y no la guarda en SavedState. */
@Composable
fun SecurityScreen(viewModel: SecurityViewModel, document: Pair<Uri, Boolean>?, onConsumed: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onBack: () -> Unit, onErase: () -> Unit,
    recoveryOnly: Boolean = false, onRestored: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy = state.busy
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var erase by remember { mutableStateOf(false) }
    BackHandler { if (!busy) { viewModel.cancelPreview(); onBack() } }
    LaunchedEffect(state.completion) {
        when (viewModel.consumeCompletion()) {
            SecurityCompletion.EXPORTED -> onConsumed()
            SecurityCompletion.RESTORED -> { onConsumed(); onRestored() }
            null -> Unit
        }
    }
    DisposableEffect(Unit) { onDispose { password = ""; confirmation = "" } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = { viewModel.cancelPreview(); onBack() }, enabled = !busy) { Text(stringResource(R.string.back_to_categories)) }
        Text(stringResource(R.string.explore_security), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.security_description))
        Text(stringResource(R.string.security_limits), style = MaterialTheme.typography.bodySmall)
        if (!recoveryOnly) Button(onClick = onExport, enabled = !busy && document == null) { Text(stringResource(R.string.security_export)) }
        OutlinedButton(onClick = onImport, enabled = !busy && document == null) { Text(stringResource(R.string.security_import)) }
        Text(stringResource(R.string.security_backup_warning))
        if (document != null) {
            Text(stringResource(if (document.second) R.string.security_password_new else R.string.security_password_existing))
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.security_password)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            if (document.second) OutlinedTextField(confirmation, { confirmation = it }, label = { Text(stringResource(R.string.security_confirm_password)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            Button(enabled = !busy && password.isNotEmpty(), onClick = {
                if (document.second && (password.length < 20 || password != confirmation)) {
                    viewModel.showMessage(R.string.security_password_error)
                } else {
                    val secret = password.toCharArray()
                    password = ""; confirmation = ""
                    viewModel.process(document, secret)
                }
            }) { Text(stringResource(if (document.second) R.string.security_export else R.string.security_verify)) }
            TextButton(onClick = { viewModel.cancelPreview(); password = ""; confirmation = ""; onConsumed() }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        }
        if (busy) CircularProgressIndicator()
        state.message?.let { Text(stringResource(it)) }
        HorizontalDivider()
        Text(stringResource(R.string.security_privacy_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.security_privacy))
        if (!recoveryOnly) TextButton(onClick = { erase = true }, enabled = !busy) { Text(stringResource(R.string.security_erase), color = MaterialTheme.colorScheme.error) }
    }
    state.summary?.let { checked -> AlertDialog(onDismissRequest = viewModel::cancelPreview,
        title = { Text(stringResource(R.string.security_replace)) },
        text = { Text(stringResource(R.string.security_replace_counts, checked.transactions, checked.categories, checked.rules)) },
        confirmButton = { TextButton(enabled = !busy, onClick = viewModel::confirmRestore) { Text(stringResource(R.string.security_replace)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = viewModel::cancelPreview) { Text(stringResource(R.string.cancel)) } }) }
    if (erase) AlertDialog(onDismissRequest = { erase = false }, title = { Text(stringResource(R.string.security_erase)) },
        text = { Text(stringResource(R.string.security_erase_warning)) },
        confirmButton = { TextButton(onClick = { erase = false; onErase() }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { erase = false }) { Text(stringResource(R.string.cancel)) } })
}
