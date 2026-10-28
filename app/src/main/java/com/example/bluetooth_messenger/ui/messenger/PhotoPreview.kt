package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

@Composable
internal fun PhotoPreview(vm: AppViewModel, file: String, dismiss: () -> Unit) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) {
        try {
            val bytes = vm.repository.photoBytes(file)
            var decoded: Bitmap? = null
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    var sample = 1
                    while(bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
                    decoded = BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: error("Не удалось открыть фото")
                }
                bitmap = decoded
                decoded = null
            } finally {
                decoded?.recycle()
                bytes.fill(0)
            }
        } catch(failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch(failure: Exception) { vm.reportError(failure.message ?: "Не удалось открыть фото") }
    }
    val displayedBitmap = bitmap
    DisposableEffect(displayedBitmap) { onDispose { displayedBitmap?.recycle() } }
    AlertDialog(onDismissRequest = dismiss,title = { Text("Фото") },text = {
        bitmap?.let { Image(it.asImageBitmap(),"Фото",Modifier.fillMaxWidth()) } ?: CircularProgressIndicator()
    },confirmButton = { TextButton(onClick = dismiss) { Text("Закрыть") } })
}

