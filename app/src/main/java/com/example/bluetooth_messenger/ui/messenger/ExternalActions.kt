package com.example.bluetooth_messenger.ui.messenger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.CancellationSignal
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.bluetooth_messenger.domain.model.AttachmentSource
import com.example.bluetooth_messenger.domain.model.ChatMessage
import com.example.bluetooth_messenger.domain.session.SessionState
import com.example.bluetooth_messenger.ui.global.AppViewModel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal class ExternalActions(
    val pick: (String, Boolean) -> Unit,
    val export: (ChatMessage) -> Unit,
    val location: (String) -> Unit,
)

internal val LocalExternalActions =
    staticCompositionLocalOf<ExternalActions> { error("External actions missing") }

@Composable
internal fun rememberExternalActions(vm: AppViewModel): ExternalActions {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var targetPhoto by rememberSaveable { mutableStateOf(false) }
    var exported by rememberSaveable { mutableStateOf<String?>(null) }
    var located by rememberSaveable { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val destination = target
            val photo = targetPhoto
            target = null
            if (uri != null && destination != null)
                vm.afterUnlock {
                    var name = "Вложение"
                    var size: Long? = null
                    resolver
                        .query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                            null,
                            null,
                            null,
                        )
                        ?.use {
                            if (it.moveToFirst()) {
                                name = it.getString(0)
                                if (!it.isNull(1)) size = it.getLong(1)
                            }
                        }
                    val source =
                        AttachmentSource(
                            name,
                            resolver.getType(uri) ?: "application/octet-stream",
                            size,
                        ) {
                            resolver.openInputStream(uri) ?: error("Файл недоступен")
                        }
                    vm.repository.sendAttachment(destination, source, photo)
                }
        }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            val uri = result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }
            val file = exported
            exported = null
            if (uri != null && file != null)
                vm.afterUnlock {
                    vm.repository.exportFile(file) {
                        resolver.openOutputStream(uri) ?: error("Файл недоступен")
                    }
                }
        }
    val location =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            val destination = located
            located = null
            if (
                destination != null &&
                    (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            )
                vm.afterUnlock {
                    val position = guardedLocation(context, vm)
                    if (vm.session.state.value == SessionState.Ready)
                        vm.repository.sendLocation(
                            destination,
                            position.latitude,
                            position.longitude,
                        )
                }
            else vm.reportError("Разрешите геолокацию для отправки координат")
        }
    return ExternalActions(
        { id, photo ->
            target = id
            targetPhoto = photo
            picker.launch(arrayOf(if (photo) "image/*" else "*/*"))
        },
        { message ->
            exported = message.fileId
            save.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(message.mimeType ?: "application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, message.fileName ?: "offgrid-file")
            )
        },
        { id ->
            located = id
            location.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        },
    )
}

private suspend fun guardedLocation(context: Context, vm: AppViewModel): android.location.Location =
    withTimeout(20_000) {
        coroutineScope {
            val request = async { currentLocation(context) }
            val lock = launch {
                vm.session.state.first { it != SessionState.Ready }
                request.cancel()
            }
            try {
                request.await()
            } finally {
                lock.cancel()
            }
        }
    }

@Suppress("MissingPermission")
private suspend fun currentLocation(context: Context): android.location.Location =
    suspendCancellableCoroutine { continuation ->
        val manager = context.getSystemService(LocationManager::class.java)
        val provider =
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull {
                manager.isProviderEnabled(it)
            }
        if (provider == null) {
            continuation.resumeWithException(
                IllegalStateException("Включите геолокацию устройства")
            )
            return@suspendCancellableCoroutine
        }
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        manager.getCurrentLocation(
            provider,
            cancellation,
            ContextCompat.getMainExecutor(context),
        ) { result ->
            if (continuation.isActive) {
                if (result == null)
                    continuation.resumeWithException(IllegalStateException("Координаты недоступны"))
                else continuation.resume(result)
            }
        }
    }
