package com.example.bluetooth_messenger.ui.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.bluetooth_messenger.ui.global.AppViewModel

@Composable
internal fun RadioPermissions(vm: AppViewModel) {
    val context = LocalContext.current
    val permissions = remember {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
    }
    var dismissed by remember { mutableStateOf(false) }
    var missing by remember {
        mutableStateOf(
            permissions.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        )
    }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            missing = permissions.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (!missing) vm.reconnect()
        }
    if (missing && !dismissed)
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Разрешения для связи рядом") },
            text = {
                Text(
                    "Для поиска устройств и передачи сообщений разрешите Bluetooth и устройства поблизости. " +
                        "На Android 12 для Wi-Fi Direct также требуется геолокация."
                )
            },
            confirmButton = {
                TextButton(onClick = { request.launch(permissions.toTypedArray()) }) {
                    Text("Разрешить")
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissed = true }) { Text("Пока без связи") }
            },
        )
}
