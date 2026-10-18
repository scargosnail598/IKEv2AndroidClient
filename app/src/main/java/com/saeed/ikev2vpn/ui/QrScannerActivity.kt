package com.saeed.ikev2vpn.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.PlanarYUVLuminanceSource
import com.saeed.ikev2vpn.profile.QrCodeDecoder
import com.saeed.ikev2vpn.ui.theme.Ikev2VpnTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Private activity: QR contents are returned only to our Activity Result launcher. */
class QrScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Ikev2VpnTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QrScanner(
                        onResult = { text ->
                            setResult(Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_TEXT, text))
                            finish()
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(Unit) {
        if (!permitted) permission.launch(Manifest.permission.CAMERA)
    }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Scan configuration QR", style = MaterialTheme.typography.headlineSmall)
        Text("Point the camera at the QR from your VPN panel. You can review the profile before saving.")
        if (permitted) {
            CameraPreview(onResult, Modifier.weight(1f))
        } else {
            Text("Camera access is required for scanning. You can also paste the link or select a QR image on the setup screen.")
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = {
                val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                settings.data = Uri.fromParts("package", context.packageName, null)
                context.startActivity(settings)
            }) { Text("Open app settings") }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(controller, lifecycle) {
        val executor = Executors.newSingleThreadExecutor()
        val delivered = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(
                Size(1920, 1080),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ))
            .build()
        controller.setImageAnalysisAnalyzer(executor) { image ->
            try {
                if (!delivered.get()) {
                    val plane = image.planes[0]
                    val bytes = QrCodeDecoder.copyLuminance(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                    val source = PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false)
                    val text = QrCodeDecoder.decode(source)
                    if (text != null && delivered.compareAndSet(false, true)) {
                        mainExecutor.execute { if (!disposed.get()) onResult(text) }
                    }
                }
            } catch (_: Exception) {
                // Keep scanning if an individual camera frame cannot be decoded.
            } finally {
                image.close()
            }
        }
        val ready = controller.initializationFuture
        ready.addListener({
            if (!disposed.get()) {
                try {
                    ready.get()
                    controller.cameraSelector = when {
                        controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> throw IllegalStateException("No camera")
                    }
                    controller.bindToLifecycle(lifecycle)
                } catch (_: Exception) {
                    error = "The camera could not be opened. Use a link or QR image instead."
                }
            }
        }, mainExecutor)
        onDispose {
            disposed.set(true)
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            executor.shutdown()
        }
    }
    Column(modifier) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        AndroidView(
            factory = { PreviewView(it).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
