// Copyright 2026 ecki
// SPDX-License-Identifier: Apache-2.0

package net.vaultcity.droid_qr_data_exchange.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.vaultcity.droid_qr_data_exchange.data.SafInput
import net.vaultcity.droid_qr_data_exchange.data.SafOutput
import net.vaultcity.droid_qr_data_exchange.format.DecryptionError
import net.vaultcity.droid_qr_data_exchange.format.QrMultiPart
import net.vaultcity.droid_qr_data_exchange.service.QrService

data class ReadUiState(
    val password: String = "",
    val partsByNumber: Map<Int, String> = emptyMap(),
    val totalParts: Int? = null,
    val busy: Boolean = false,
    val statusMessage: String = "0 Teile geladen",
    val error: String? = null,
    val extractedPaths: List<String>? = null,
    val extractedTo: String? = null,
) {
    val isComplete: Boolean
        get() = totalParts != null && partsByNumber.keys == (1..totalParts).toSet()
}

/** Mirrors `extra_windows.ReadTab`'s accept/reject/duplicate part logic and decrypt flow. */
class ReadViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ReadUiState())
    val uiState: StateFlow<ReadUiState> = _uiState.asStateFlow()

    private val mutex = Mutex()

    // De-dupes raw strings already attempted so a camera held on the same code doesn't re-run
    // Argon2i every frame.
    private val alreadySeenRawText = HashSet<String>()

    fun setPassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun clearParts() {
        alreadySeenRawText.clear()
        _uiState.value = _uiState.value.copy(
            partsByNumber = emptyMap(),
            totalParts = null,
            statusMessage = "0 Teile geladen",
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun consumeExtractedResult() {
        _uiState.value = _uiState.value.copy(extractedPaths = null, extractedTo = null)
    }

    /** Live camera frames. */
    fun onQrDetected(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || !alreadySeenRawText.add(text)) return

        val password = _uiState.value.password
        if (password.isEmpty()) return

        viewModelScope.launch {
            val error = withContext(Dispatchers.Default) { tryAddPart(text, password) }
            if (error != null) {
                _uiState.value = _uiState.value.copy(error = error)
            }
            updateStatus()
        }
    }

    /** Pasted/typed text; splits concatenated base64 blobs ending in "==" like the desktop app. */
    fun onTextSubmitted(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val password = _uiState.value.password
        if (password.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Bitte ein Passwort eingeben.")
            return
        }

        val candidates = if (trimmed.length > 2953 && trimmed.contains("==")) {
            trimmed.split("==").map { it.trim() }.filter { it.isNotEmpty() }.map { "$it==" }
        } else {
            listOf(trimmed)
        }

        _uiState.value = _uiState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val errors = withContext(Dispatchers.Default) {
                candidates.mapNotNull { tryAddPart(it, password) }
            }
            _uiState.value = _uiState.value.copy(busy = false, error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"))
            updateStatus()
        }
    }

    /** Picked `.png`/`.jpg`/`.txt` files. */
    fun onFilesPicked(context: Context, uris: List<Uri>) {
        val password = _uiState.value.password
        if (password.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Bitte ein Passwort eingeben.")
            return
        }

        _uiState.value = _uiState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            var loaded = 0
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        val text = readQrTextFromUri(context, uri)
                        val error = tryAddPart(text, password)
                        if (error != null) throw IllegalStateException(error)
                        loaded++
                    } catch (e: Exception) {
                        errors.add("$uri: ${e.message}")
                    }
                }
            }
            val summary = if (errors.isNotEmpty()) {
                "Erfolgreich geladen: $loaded/${uris.size} Datei(en).\n\n" + errors.take(5).joinToString("\n")
            } else {
                null
            }
            _uiState.value = _uiState.value.copy(busy = false, error = summary)
            updateStatus()
        }
    }

    private fun readQrTextFromUri(context: Context, uri: Uri): String {
        val name = uri.lastPathSegment ?: ""
        return if (name.endsWith(".txt", ignoreCase = true)) {
            SafInput.readTextFile(context, uri)
        } else {
            QrService.readQrFromImage(context, uri)
        }
    }

    /**
     * Attempts to decrypt and record a single QR part. Returns null on success (including a
     * harmless re-add of an already-loaded part), or an error message describing the rejection.
     */
    private suspend fun tryAddPart(qrText: String, password: String): String? {
        val text = qrText.trim()
        if (text.isEmpty()) return "Leerer Text"

        val part = try {
            QrMultiPart.decryptPart(text, password)
        } catch (e: DecryptionError) {
            return "Kann nicht entschlüsseln: ${e.message}"
        } catch (e: Exception) {
            return "Kein gültiger QR-Code für diese App: ${e.message}"
        }

        return mutex.withLock {
            val state = _uiState.value
            val existing = state.partsByNumber[part.partNumber]
            when {
                existing == text -> null // duplicate re-add, harmless
                existing != null -> "Teil ${part.partNumber} wurde bereits mit anderem Inhalt geladen -- " +
                    "das sieht nach einer anderen Übertragung aus. Zuerst \"Liste leeren\" klicken."
                state.totalParts != null && part.totalParts != state.totalParts ->
                    "Dieser Teil gehört zu ${part.totalParts} Teilen insgesamt, bereits geladene Teile " +
                        "gehören zu ${state.totalParts} -- vermutlich eine andere Übertragung."
                else -> {
                    _uiState.value = state.copy(
                        partsByNumber = state.partsByNumber + (part.partNumber to text),
                        totalParts = part.totalParts,
                    )
                    null
                }
            }
        }
    }

    private fun updateStatus() {
        val state = _uiState.value
        val message = if (state.totalParts == null) {
            "0 Teile geladen"
        } else {
            "${state.partsByNumber.size}/${state.totalParts} Teile geladen"
        }
        _uiState.value = _uiState.value.copy(statusMessage = message)
    }

    fun decryptAndExtract(context: Context, outputTreeUri: Uri) {
        val state = _uiState.value
        if (state.partsByNumber.isEmpty()) return

        _uiState.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    val tarBytes =
                        QrMultiPart.deserializeToBytes(state.partsByNumber.values.toList(), state.password)
                    val entries = QrMultiPart.extractTarEntries(tarBytes)
                    SafOutput.writeTarEntries(context, outputTreeUri, entries)
                }
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    extractedPaths = extracted,
                    extractedTo = outputTreeUri.toString(),
                )
            } catch (e: DecryptionError) {
                _uiState.value = _uiState.value.copy(busy = false, error = "Kann nicht entschlüsseln: ${e.message}")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(busy = false, error = "Unerwarteter Fehler: ${e.message}")
            }
        }
    }
}
