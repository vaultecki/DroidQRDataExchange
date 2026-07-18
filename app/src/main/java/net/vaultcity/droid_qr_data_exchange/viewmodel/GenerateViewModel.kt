package net.vaultcity.droid_qr_data_exchange.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vaultcity.droid_qr_data_exchange.data.SafInput
import net.vaultcity.droid_qr_data_exchange.service.QrService

/** Default max size (base64 chars) per QR code, same as the desktop app's `MAX_QR_CODE_BYTES`. */
const val MAX_QR_CODE_BYTES = 2953

sealed class InputSelection {
    data object None : InputSelection()
    data class Files(val uris: List<Uri>, val label: String) : InputSelection()
    data class Folder(val uri: Uri, val label: String) : InputSelection()
}

data class GenerateResult(val images: List<Bitmap>, val texts: List<String>)

data class GenerateUiState(
    val password: String = "",
    val inputSelection: InputSelection = InputSelection.None,
    val busy: Boolean = false,
    val result: GenerateResult? = null,
    val error: String? = null,
)

/** Mirrors `controller.QrExchangeController` + `extra_windows.GenerateTab`'s generate flow. */
class GenerateViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(GenerateUiState())
    val uiState: StateFlow<GenerateUiState> = _uiState.asStateFlow()

    fun setPassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun setFiles(uris: List<Uri>, label: String) {
        _uiState.value = _uiState.value.copy(inputSelection = InputSelection.Files(uris, label))
    }

    fun setFolder(uri: Uri, label: String) {
        _uiState.value = _uiState.value.copy(inputSelection = InputSelection.Folder(uri, label))
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun consumeResult() {
        _uiState.value = _uiState.value.copy(result = null)
    }

    fun generate(context: Context) {
        val state = _uiState.value
        if (state.password.isEmpty()) {
            _uiState.value = state.copy(error = "Bitte ein Passwort eingeben.")
            return
        }
        if (state.inputSelection is InputSelection.None) {
            _uiState.value = state.copy(error = "Bitte Datei(en) oder einen Ordner auswählen.")
            return
        }

        _uiState.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val (images, texts) = withContext(Dispatchers.IO) {
                    val entries = when (val selection = state.inputSelection) {
                        is InputSelection.Files -> SafInput.fromDocuments(context, selection.uris)
                        is InputSelection.Folder -> SafInput.fromTree(context, selection.uri)
                        InputSelection.None -> emptyList()
                    }
                    QrService.generateQrImages(entries, state.password, MAX_QR_CODE_BYTES)
                }
                _uiState.value = _uiState.value.copy(busy = false, result = GenerateResult(images, texts))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(busy = false, error = e.message ?: "Unbekannter Fehler")
            }
        }
    }
}
