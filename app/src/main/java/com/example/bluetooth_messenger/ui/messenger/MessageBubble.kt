package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.unit.sp
import com.example.bluetooth_messenger.domain.model.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(message: ChatMessage, senderName: String?, onClick: () -> Unit, onLongClick: () -> Unit, onRetry: () -> Unit) {
    if(message.kind == ContentType.System) {
        Text(message.text,Modifier.fillMaxWidth().padding(8.dp),fontSize = 11.sp)
        return
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement = if(message.incoming) Arrangement.Start else Arrangement.End) {
        Surface(color = if(message.incoming) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 310.dp).combinedClickable(onClick = onClick,onLongClick = onLongClick)) {
            Column(Modifier.padding(12.dp)) {
                if(message.incoming && senderName != null) Text(senderName,fontSize = 11.sp)
                Text(message.text.ifBlank { message.fileName ?: if(message.kind == ContentType.Photo) "Фото" else "Вложение" },fontSize = 15.sp)
                if(message.fileSize > 0) Text("${message.fileSize / 1024} КиБ",fontSize = 11.sp)
                if(message.progress in 1..99) LinearProgressIndicator(progress = { message.progress / 100f },Modifier.fillMaxWidth())
                Text("${timeLabel(message.timestamp)} ${if(message.incoming) "" else statusLabel(message)}",fontSize = 11.sp)
                if(message.status == DeliveryStatus.Error) {
                    Text(message.error ?: "Ошибка отправки",color = MaterialTheme.colorScheme.error,fontSize = 11.sp)
                    TextButton(onClick = onRetry) { Text("Повторить") }
                }
            }
        }
    }
}

internal fun statusLabel(message: ChatMessage): String = deliveryLabel(message.status) + if(message.targetCount > 1) " · ${message.deliveredCount} из ${message.targetCount}" else ""
internal fun deliveryLabel(status: DeliveryStatus): String = when(status) {
    DeliveryStatus.Pending -> "⏳ Ожидание"; DeliveryStatus.Sent -> "✓ Отправлено"; DeliveryStatus.Delivered -> "✓✓ Доставлено"; DeliveryStatus.Read -> "◉ Прочитано"; DeliveryStatus.Error -> "× Ошибка"
}
