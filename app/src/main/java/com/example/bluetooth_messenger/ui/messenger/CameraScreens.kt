package com.example.bluetooth_messenger.ui.messenger

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bluetooth_messenger.ui.global.AppViewModel
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable
internal fun ScanContact(vm: AppViewModel, dismiss: () -> Unit) {
    var scanned by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    if (scanned == null) CameraDialog(true, dismiss, { value -> scanned = value }, {})
    else
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Введите имя контакта") },
            text = {
                OutlinedTextField(
                    name,
                    {
                        name = it
                        vm.session.interacted()
                    },
                    label = { Text("Имя контакта") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.action {
                            vm.repository.addFromQr(scanned!!, name)
                            dismiss()
                        }
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text("Запросить знакомство")
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } },
        )
}

@Composable
internal fun CameraDialog(
    scan: Boolean,
    dismiss: () -> Unit,
    result: (String) -> Unit,
    photo: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
        }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val active = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val capture = remember { ImageCapture.Builder().setJpegQuality(85).build() }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            active.set(false)
            provider?.unbindAll()
            executor.shutdownNow()
        }
    }
    Dialog(onDismissRequest = dismiss) {
        Surface {
            Column {
                if (permission)
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).also { view ->
                                val future = ProcessCameraProvider.getInstance(ctx)
                                future.addListener(
                                    {
                                        if (!active.get()) return@addListener
                                        try {
                                            val camera = future.get()
                                            provider = camera
                                            val preview =
                                                Preview.Builder().build().apply {
                                                    surfaceProvider = view.surfaceProvider
                                                }
                                            val analysis =
                                                ImageAnalysis.Builder()
                                                    .setBackpressureStrategy(
                                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                                    )
                                                    .build()
                                            var handled = false
                                            analysis.setAnalyzer(executor) { image ->
                                                try {
                                                    if (!handled) {
                                                        val decodedText = readQrCode(image)
                                                        handled = true
                                                        ContextCompat.getMainExecutor(ctx).execute {
                                                            if (active.get()) result(decodedText)
                                                        }
                                                    }
                                                } catch (_: Exception) {} finally {
                                                    image.close()
                                                }
                                            }
                                            camera.bindToLifecycle(
                                                lifecycle,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                if (scan) analysis else capture,
                                            )
                                        } catch (failure: Exception) {
                                            cameraError = failure.message ?: "Камера недоступна"
                                        }
                                    },
                                    ContextCompat.getMainExecutor(ctx),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.8f),
                    )
                else {
                    Text("Для камеры необходимо разрешение")
                    TextButton(onClick = { request.launch(Manifest.permission.CAMERA) }) {
                        Text("Разрешить камеру")
                    }
                }
                cameraError?.let { Text(it) }
                if (!scan && permission)
                    Button(
                        onClick = {
                            capture.takePicture(
                                executor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        try {
                                            val buffer = image.planes[0].buffer
                                            if (buffer.remaining() > 10 * 1024 * 1024) {
                                                ContextCompat.getMainExecutor(context).execute {
                                                    cameraError = "Фото больше 10 МиБ"
                                                }
                                                return
                                            }
                                            val bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            ContextCompat.getMainExecutor(context).execute {
                                                if (active.get()) photo(bytes) else bytes.fill(0)
                                            }
                                        } finally {
                                            image.close()
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        ContextCompat.getMainExecutor(context).execute {
                                            cameraError = exception.message
                                        }
                                    }
                                },
                            )
                        }
                    ) {
                        Text("Снять фото")
                    }
                TextButton(onClick = dismiss) { Text("Закрыть") }
            }
        }
    }
}

private fun readQrCode(image: ImageProxy): String {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val pixels = ByteArray(image.width * image.height)

    for (row in 0 until image.height) {
        for (column in 0 until image.width) {
            val sourceOffset = row * plane.rowStride + column * plane.pixelStride
            val targetOffset = row * image.width + column
            pixels[targetOffset] = buffer.get(sourceOffset)
        }
    }

    val source =
        PlanarYUVLuminanceSource(
            pixels,
            image.width,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    return MultiFormatReader().decode(bitmap, hints).text
}
