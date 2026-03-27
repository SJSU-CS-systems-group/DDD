package net.discdd.components

import android.Manifest
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QRScannerScreen(
    onQRCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (cameraPermissionState.status.isGranted) {
            CameraPreviewWithScanner(onQRCodeScanned = onQRCodeScanned, onDismiss = onDismiss)
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Camera permission is required to scan QR codes.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                FilledTonalButton(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Grant Camera Permission")
                }
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@ExperimentalGetImage
private class QRImageAnalyzer(
    private val onScanned: (String) -> Unit,
    private val scannedFlag: () -> Boolean,
    private val setScanned: () -> Unit,
) : ImageAnalysis.Analyzer {
    private val barcodeScanner = BarcodeScanning.getClient()

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !scannedFlag()) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        if (barcode.valueType == Barcode.TYPE_URL ||
                            barcode.valueType == Barcode.TYPE_TEXT
                        ) {
                            val value = barcode.rawValue
                            if (value != null && !scannedFlag()) {
                                setScanned()
                                onScanned(value)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("QRScanner", "Barcode scan failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreviewWithScanner(
    onQRCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanned by remember { mutableStateOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val provider = cameraProvider
        if (provider != null) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(executor, QRImageAnalyzer(
                        onScanned = onQRCodeScanned,
                        scannedFlag = { scanned },
                        setScanned = { scanned = true },
                    ))

                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis,
                        )
                    } catch (e: Exception) {
                        Log.e("QRScanner", "Camera bind failed", e)
                    }

                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        FilledTonalButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .fillMaxWidth(),
        ) {
            Text("Cancel")
        }
    }
}
