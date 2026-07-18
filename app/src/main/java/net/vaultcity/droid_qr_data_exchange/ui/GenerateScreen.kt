package net.vaultcity.droid_qr_data_exchange.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.vaultcity.droid_qr_data_exchange.viewmodel.GenerateViewModel
import net.vaultcity.droid_qr_data_exchange.viewmodel.InputSelection

@Composable
fun GenerateScreen(viewModel: GenerateViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    if (state.result != null) {
        QrResultScreen(result = state.result!!, onClose = { viewModel.consumeResult() })
        return
    }

    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val label = if (uris.size == 1) uris.first().lastPathSegment ?: "1 Datei" else "${uris.size} Dateien ausgewählt"
            viewModel.setFiles(uris, label)
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.setFolder(uri, uri.lastPathSegment ?: "Ordner ausgewählt")
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            label = { Text("Passwort") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { filesLauncher.launch(arrayOf("*/*")) }) {
                Text("Dateien wählen")
            }
            OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                Text("Ordner wählen")
            }
        }

        val selectionLabel = when (val selection = state.inputSelection) {
            InputSelection.None -> "Keine Auswahl"
            is InputSelection.Files -> selection.label
            is InputSelection.Folder -> selection.label
        }
        Text(
            text = selectionLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            }
            Button(onClick = { viewModel.generate(context) }, enabled = !state.busy) {
                Text("QR erzeugen")
            }
        }
    }

    if (state.error != null) {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } },
            title = { Text("Fehler") },
            text = { Text(state.error!!) },
        )
    }
}
