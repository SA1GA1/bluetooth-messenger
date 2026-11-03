package com.example.bluetooth_messenger.ui.messenger

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
internal fun QrDialog(
    title: String,
    value: String?,
    dismiss: () -> Unit,
    scan: (() -> Unit)? = null,
) {
    val bitmap =
        remember(value) {
            value?.let { content ->
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 700, 700)
                Bitmap.createBitmap(700, 700, Bitmap.Config.RGB_565).apply {
                    setPixels(
                        IntArray(700 * 700) {
                            if (matrix[it % 700, it / 700]) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE
                        },
                        0,
                        700,
                        0,
                        0,
                        700,
                        700,
                    )
                }
            }
        }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column {
                if (bitmap != null)
                    Image(bitmap.asImageBitmap(), "QR код", Modifier.fillMaxWidth().aspectRatio(1f))
                else CircularProgressIndicator()
                Text(
                    if (scan == null) "Только для сверки ключей. Этот QR не добавляет контакт."
                    else
                        "QR содержит только публичные ключи. После сканирования сверьте код знакомства на обоих устройствах."
                )
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Закрыть") } },
        dismissButton = { if (scan != null) TextButton(onClick = scan) { Text("Сканировать QR") } },
    )
}
