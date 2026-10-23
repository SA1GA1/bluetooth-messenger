package com.example.bluetooth_messenger.domain.model

import java.io.InputStream
import java.util.UUID

data class Contact(val id: String, val name: String, val verified: Boolean)
data class Conversation(val id: String, val name: String, val type: ConversationType, val lastText: String,
    val updatedAt: Long, val unreadCount: Int, val active: Boolean, val ownerId: String?)
data class ChatMessage(val id: String, val senderId: String, val kind: ContentType, val text: String,
    val timestamp: Long, val incoming: Boolean, val status: DeliveryStatus, val deliveredCount: Int,
    val targetCount: Int, val progress: Int, val fileName: String?, val fileSize: Long, val fileId: String?, val error: String?)
data class PairingPrompt(val peerId: String, val name: String, val code: String?, val confirmed: Boolean)
data class GroupMember(val id: String, val name: String, val active: Boolean)
data class PublicMessage(val id: String, val text: String, val timestamp: Long, val mine: Boolean)
data class AttachmentSource(val name: String, val mimeType: String, val size: Long?, val open: () -> InputStream)

fun directConversationId(first: String, second: String): String =
    UUID.nameUUIDFromBytes(listOf(first, second).sorted().joinToString(":").toByteArray(Charsets.UTF_8)).toString()

fun validatedName(value: String, limit: Int = 64): String = value.trim().also { name ->
    require(name.isNotEmpty() && name.length <= limit && name.none(Char::isISOControl)) { "Введите имя от 1 до $limit символов" }
}
