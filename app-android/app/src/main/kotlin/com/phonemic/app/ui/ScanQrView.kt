package com.phonemic.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phonemic.app.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.concurrent.Executors

@Composable
fun ScanQrView(
    onResult: (host: String, port: Int, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasHandled by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraExecutor = Executors.newSingleThreadExecutor()
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val scanner = BarcodeScanning.getClient()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(scanner, imageProxy) { rawValue ->
                            if (!hasHandled) {
                                val parsed = parseQrCode(rawValue)
                                if (parsed != null) {
                                    hasHandled = true
                                    ContextCompat.getMainExecutor(ctx).execute {
                                        onResult(parsed.first, parsed.second, parsed.third)
                                    }
                                }
                            }
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e("ScanQrView", "Binding failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // Overlay with scanning frame
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
            }

            Surface(
                modifier = Modifier
                    .size(260.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            ) {}

            Text(
                text = stringResource(R.string.scan_prompt),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onFound: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.valueType == Barcode.TYPE_TEXT || barcode.valueType == Barcode.TYPE_URL) {
                        barcode.rawValue?.let { onFound(it) }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

private fun parseQrCode(raw: String): Triple<String, Int, String>? {
    val trimmed = raw.trim()
    try {
        // Format 1: phonemic://192.168.1.138:9000
        if (trimmed.startsWith("phonemic://")) {
            val uri = Uri.parse(trimmed)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 9000
            val name = uri.getQueryParameter("name") ?: "PC ($host)"
            return Triple(host, port, name)
        }
        // Format 2: JSON {"ip": "...", "port": 9000, ...}
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val obj = JSONObject(trimmed)
            val ip = obj.optString("ip")
            if (ip.isNotBlank()) {
                val port = obj.optInt("port", 9000)
                val name = obj.optString("name", "PC ($ip)")
                return Triple(ip, port, name)
            }
        }
        // Format 3: simple ip:port
        if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            if (parts.size == 2) {
                val ip = parts[0].trim()
                val port = parts[1].trim().toIntOrNull() ?: 9000
                return Triple(ip, port, "PC ($ip)")
            }
        }
    } catch (e: Exception) {
        Log.w("parseQrCode", "Failed parsing QR: ${e.message}")
    }
    return null
}
