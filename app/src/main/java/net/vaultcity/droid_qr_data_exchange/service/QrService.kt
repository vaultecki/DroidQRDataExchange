// Copyright 2026 ecki
// SPDX-License-Identifier: Apache-2.0

package net.vaultcity.droid_qr_data_exchange.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.vaultcity.droid_qr_data_exchange.format.QrMultiPart
import net.vaultcity.droid_qr_data_exchange.format.TarEntryInput
import java.io.IOException

/** Mirrors `service.QRCodeNotFoundError`. */
class QRCodeNotFoundError(message: String) : Exception(message)

/**
 * QR image generation (ZXing) and static-image decoding (ML Kit, on-device/offline). Ported from
 * `app/service.py`. Live camera-frame decoding uses the same ML Kit scanner from
 * `ui/CameraQrScanner.kt`.
 */
object QrService {
    private const val QR_RENDER_SIZE = 800

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }

    /**
     * Packs one or more files/directories, encrypts them and returns QR code image(s).
     * Returns (images, texts) -- always paired lists, length 1 if everything fits in one QR code.
     */
    fun generateQrImages(
        entries: List<TarEntryInput>,
        password: String,
        maxBytes: Int,
    ): Pair<List<Bitmap>, List<String>> {
        val qrStrings = QrMultiPart.serializeToParts(entries, password, maxBytes)
        val images = qrStrings.map { renderQrBitmap(it) }
        return images to qrStrings
    }

    private fun renderQrBitmap(text: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, // matches qrcode.make(..., error_correction=1)
            EncodeHintType.MARGIN to 1,
        )
        val matrix: BitMatrix =
            MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, QR_RENDER_SIZE, QR_RENDER_SIZE, hints)
        return matrixToBitmap(matrix)
    }

    private fun matrixToBitmap(matrix: BitMatrix): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /** Reads a QR code image (content Uri) and returns the contained text. */
    fun readQrFromImage(context: Context, uri: Uri): String {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: IOException) {
            throw QRCodeNotFoundError("Image could not be read.")
        }

        val barcodes = try {
            Tasks.await(scanner.process(image))
        } catch (e: Exception) {
            throw QRCodeNotFoundError("No QR code found in image.")
        }

        return barcodes.firstOrNull()?.rawValue ?: throw QRCodeNotFoundError("No QR code found in image.")
    }

    /** Structural check only (no password needed): does this look like one of our QR codes? */
    fun isValidQrPart(qrText: String): Boolean = QrMultiPart.isValidQrPart(qrText)
}
