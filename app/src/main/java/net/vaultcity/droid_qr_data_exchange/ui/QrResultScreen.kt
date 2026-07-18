// Copyright 2026 ecki
// SPDX-License-Identifier: Apache-2.0

package net.vaultcity.droid_qr_data_exchange.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.vaultcity.droid_qr_data_exchange.data.SafOutput
import net.vaultcity.droid_qr_data_exchange.viewmodel.GenerateResult

@Composable
fun QrResultScreen(result: GenerateResult, onClose: () -> Unit) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { result.images.size })
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val saveCurrentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            try {
                SafOutput.writeBitmapPng(context, uri, result.images[pagerState.currentPage])
                infoMessage = "QR-Code gespeichert."
            } catch (e: Exception) {
                errorMessage = e.message ?: "Speichern fehlgeschlagen."
            }
        }
    }
    val saveAllLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                SafOutput.saveAllQrCodes(context, uri, result.images, result.texts)
                infoMessage = "${result.images.size} QR-Codes gespeichert."
            } catch (e: Exception) {
                errorMessage = e.message ?: "Speichern fehlgeschlagen."
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "${result.images.size} QR-Code(s) erzeugt",
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = "Teil ${pagerState.currentPage + 1}/${result.images.size}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            Image(
                bitmap = result.images[page].asImageBitmap(),
                contentDescription = "QR-Code Teil ${page + 1}",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp),
            )
        }

        val currentText = result.texts[pagerState.currentPage]
        Text(
            text = if (currentText.length > 60) currentText.take(60) + "..." else currentText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                saveCurrentLauncher.launch("qr_part_${pagerState.currentPage + 1}_of_${result.images.size}.png")
            }) {
                Text("Aktuellen speichern")
            }
            OutlinedButton(onClick = { saveAllLauncher.launch(null) }) {
                Text("Alle speichern")
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = onClose) { Text("Schließen") }
        }
    }

    if (infoMessage != null) {
        AlertDialog(
            onDismissRequest = { infoMessage = null },
            confirmButton = { TextButton(onClick = { infoMessage = null }) { Text("OK") } },
            title = { Text("Gespeichert") },
            text = { Text(infoMessage!!) },
        )
    }
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
            title = { Text("Fehler") },
            text = { Text(errorMessage!!) },
        )
    }
}
