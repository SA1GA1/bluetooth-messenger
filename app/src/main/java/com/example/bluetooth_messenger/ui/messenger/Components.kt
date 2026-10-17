package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel

@Composable
internal fun ToggleRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, change)
    }
}

@Composable
internal fun Confirm(title: String, text: String, dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = accept) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } },
    )
}

@Composable
internal fun Composer(text: String, change: (String) -> Unit, send: () -> Unit) {
    Row(Modifier.fillMaxWidth().imePadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            text,
            change,
            Modifier.weight(1f),
            placeholder = { Text("Сообщение") },
            maxLines = 5,
        )
        TextButton(onClick = send, enabled = text.isNotBlank()) { Text("Отправить") }
    }
}

internal fun timeLabel(time: Long): String =
    if (time <= 0) ""
    else
        java.text
            .SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(time))

@Composable
internal fun NetworkBanner(vm: AppViewModel) {
    val error by vm.session.networkError.collectAsState()
    error?.let { description ->
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(description)
                TextButton(onClick = { vm.reconnect() }) { Text("Повторить подключение") }
            }
        }
    }
}
