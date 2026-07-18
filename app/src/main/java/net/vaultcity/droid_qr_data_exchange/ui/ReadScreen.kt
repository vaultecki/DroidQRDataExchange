// Copyright 2026 ecki
// SPDX-License-Identifier: Apache-2.0

package net.vaultcity.droid_qr_data_exchange.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.vaultcity.droid_qr_data_exchange.viewmodel.ReadViewModel

@Composable
fun ReadScreen(viewModel: ReadViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var textFieldValue by remember { mutableStateOf("") }
    var cameraEnabled by remember { mutableStateOf(true) }

    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.onFilesPicked(context, uris)
    }
    val outputFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.decryptAndExtract(context, uri)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            label = { Text("Passwort") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                label = { Text("QR-Text einfügen") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.onTextSubmitted(textFieldValue)
                    textFieldValue = ""
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Hinzufügen")
            }
        }

        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { filesLauncher.launch(arrayOf("image/*", "text/plain")) }) {
                Text("Datei(en) auswählen")
            }
            OutlinedButton(onClick = { viewModel.clearParts() }) {
                Text("Liste leeren")
            }
        }

        Text(
            text = state.statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kamera-Scan", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { cameraEnabled = !cameraEnabled }) {
                Text(if (cameraEnabled) "Pausieren" else "Fortsetzen")
            }
        }
        if (cameraEnabled) {
            CameraQrScanner(
                onQrDetected = viewModel::onQrDetected,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 4.dp),
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            }
            Button(
                onClick = { outputFolderLauncher.launch(null) },
                enabled = state.isComplete && !state.busy,
            ) {
                Text("Entschlüsseln und speichern")
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

    if (state.extractedPaths != null) {
        val extracted = state.extractedPaths!!
        AlertDialog(
            onDismissRequest = viewModel::consumeExtractedResult,
            confirmButton = { TextButton(onClick = viewModel::consumeExtractedResult) { Text("OK") } },
            title = { Text("Fertig") },
            text = {
                val preview = extracted.take(10).joinToString("\n")
                val more = if (extracted.size > 10) "\n... und ${extracted.size - 10} weitere" else ""
                Text("${extracted.size} Datei(en) extrahiert.\n\n$preview$more")
            },
        )
    }
}
