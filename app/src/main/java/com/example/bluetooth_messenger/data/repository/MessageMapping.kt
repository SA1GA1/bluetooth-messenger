package com.example.bluetooth_messenger.data.repository

import com.example.bluetooth_messenger.data.local.database.MessageRow
import com.example.bluetooth_messenger.domain.model.*

internal fun MessageRow.toDomain(): ChatMessage {
    val status = when {
        message.status == "Error" -> DeliveryStatus.Error
        targetCount == 0 -> DeliveryStatus.valueOf(message.status)
        readCount == targetCount -> DeliveryStatus.Read
        deliveredCount == targetCount -> DeliveryStatus.Delivered
        errorCount > 0 -> DeliveryStatus.Error
        sentCount > 0 -> DeliveryStatus.Sent
        else -> DeliveryStatus.Pending
    }
    return ChatMessage(message.id, message.senderId, ContentType.valueOf(message.kind), message.text,
        message.timestamp, message.incoming, status, deliveredCount, targetCount, message.progress,
        message.fileName, message.fileSize, message.fileId, message.error, message.mimeType)
}
