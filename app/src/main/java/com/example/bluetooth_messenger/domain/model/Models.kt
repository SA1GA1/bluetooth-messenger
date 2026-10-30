package com.example.bluetooth_messenger.domain.model

import java.util.UUID

object Limits {
    const val TEXT_BYTES = 16 * 1024
    const val PUBLIC_BYTES = 2 * 1024
    const val PHOTO_BYTES = 10L * 1024 * 1024
    const val FILE_BYTES = 50L * 1024 * 1024
    const val GROUP_MEMBERS = 8
    const val PACKET_BYTES = 64 * 1024
    const val PUBLIC_LIFETIME_MILLIS = 24L * 60 * 60 * 1000
}

data class PeerIdentity(val userId: String, val signingKey: ByteArray, val agreementKey: ByteArray)
data class DiscoveredPeer(val userId: String, val address: String, val lastSeenMillis: Long)
enum class DeliveryStatus { Pending, Sent, Delivered, Read, Error }
enum class ConversationType { Direct, Group }
enum class ContentType { Text, Photo, File, Location, System }

fun newId(): String = UUID.randomUUID().toString()

fun validateText(text: String, public: Boolean = false) {
    require(text.isNotBlank()) { "Сообщение пустое" }
    require(text.none { it.code < 0x20 && it !in "\t\n\r" }) { "Сообщение содержит недопустимые управляющие символы" }
    val limit = if (public) Limits.PUBLIC_BYTES else Limits.TEXT_BYTES
    require(text.toByteArray(Charsets.UTF_8).size <= limit) { "Сообщение слишком длинное" }
}
