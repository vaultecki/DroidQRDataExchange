// Copyright 2026 ecki
// SPDX-License-Identifier: Apache-2.0

package net.vaultcity.droid_qr_data_exchange.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Continuous live QR scan: CameraX preview + ZXing on-device barcode analysis (fully
 * on-device/offline, no proprietary dependencies -- unlike ML Kit, this keeps the app
 * free-software/F-Droid-eligible). Detected raw text is reported via [onQrDetected] for every
 * analyzed frame that contains a QR code -- the caller (`ReadViewModel.onQrDetected`) is
 * responsible for de-duping repeats of the same code.
 */
@Composable
fun CameraQrScanner(onQrDetected: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onQrDetectedState = rememberUpdatedState(onQrDetected)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Kamera-Berechtigung erforderlich, um QR-Codes zu scannen.")
        }
        return
    }

    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }
    // Frame decoding is synchronous/CPU-bound -- runs on its own executor so it never blocks the
    // main thread (unlike ML Kit's async Task API, ZXing's MultiFormatReader.decode is blocking).
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    // Tracks the actually-bound provider so it can be released when this composable leaves
    // composition (e.g. the "Pausieren" toggle) -- otherwise the camera keeps running in the
    // background since binding only happens once, asynchronously, inside AndroidView's factory.
    val boundCameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            boundCameraProvider.value?.unbindAll()
            analyzerExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                        try {
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val packed = extractYPlane(imageProxy)
                            val rotated = rotateYPlane(packed, imageProxy.width, imageProxy.height, rotationDegrees)

                            val source = PlanarYUVLuminanceSource(
                                rotated.data, rotated.width, rotated.height,
                                0, 0, rotated.width, rotated.height, false,
                            )
                            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                            onQrDetectedState.value(result.text)
                        } catch (e: NotFoundException) {
                            // No QR code in this frame -- expected most of the time, ignore.
                        } catch (e: Exception) {
                            // Transient decode error -- ignore, next frame will retry.
                        } finally {
                            imageProxy.close()
                        }
                    }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                        boundCameraProvider.value = cameraProvider
                    } catch (e: Exception) {
                        // Camera unavailable -- preview stays blank; text/file entry points still work.
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

/**
 * Copies the Y (luminance) plane out of a camera frame into a tightly-packed `width*height`
 * array, respecting row/pixel stride (the plane's buffer may have padding beyond each row).
 */
private fun extractYPlane(imageProxy: ImageProxy): ByteArray {
    val plane = imageProxy.planes[0]
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val buffer = plane.buffer
    val width = imageProxy.width
    val height = imageProxy.height

    if (rowStride == width && pixelStride == 1) {
        val packed = ByteArray(width * height)
        buffer.get(packed)
        return packed
    }

    val packed = ByteArray(width * height)
    val rowBytes = ByteArray(rowStride)
    var outputOffset = 0
    for (row in 0 until height) {
        buffer.position(row * rowStride)
        buffer.get(rowBytes, 0, minOf(rowStride, buffer.remaining()))
        for (col in 0 until width) {
            packed[outputOffset++] = rowBytes[col * pixelStride]
        }
    }
    return packed
}

private class RotatedYPlane(val data: ByteArray, val width: Int, val height: Int)

/** Rotates a camera frame's Y (luminance) plane so ZXing sees it upright. */
private fun rotateYPlane(data: ByteArray, width: Int, height: Int, rotationDegrees: Int): RotatedYPlane {
    return when (rotationDegrees) {
        90 -> {
            val rotated = ByteArray(data.size)
            var i = 0
            for (x in 0 until width) {
                for (y in height - 1 downTo 0) {
                    rotated[i++] = data[y * width + x]
                }
            }
            RotatedYPlane(rotated, height, width)
        }
        180 -> {
            val rotated = ByteArray(data.size)
            val n = width * height
            for (i in 0 until n) {
                rotated[n - 1 - i] = data[i]
            }
            RotatedYPlane(rotated, width, height)
        }
        270 -> {
            val rotated = ByteArray(data.size)
            var i = 0
            for (x in width - 1 downTo 0) {
                for (y in 0 until height) {
                    rotated[i++] = data[y * width + x]
                }
            }
            RotatedYPlane(rotated, height, width)
        }
        else -> RotatedYPlane(data, width, height)
    }
}
