package com.example.bluetooth_messenger.domain.repository

import com.example.bluetooth_messenger.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MessengerRepository {
    val contacts: Flow<List<Contact>>
    val conversations: Flow<List<Conversation>>
    val pairings: StateFlow<List<PairingPrompt>>
    val ownQr: StateFlow<String?>
    val publicMessages: Flow<List<PublicMessage>>
    val errors: StateFlow<String?>
    fun messages(conversation: String): Flow<List<ChatMessage>>
    fun groupMembers(conversation: String): Flow<List<GroupMember>>
    suspend fun addFromQr(value: String, name: String)
    suspend fun confirmPairing(peer: String, name: String)
    suspend fun rejectPairing(peer: String)
    suspend fun openContact(peer: String): String
    suspend fun sendText(conversation: String, text: String)
    suspend fun sendPublic(text: String)
    suspend fun sendLocation(conversation: String, latitude: Double, longitude: Double)
    suspend fun sendAttachment(conversation: String, source: AttachmentSource, photo: Boolean)
    suspend fun createGroup(name: String, members: List<String>): String
    suspend fun addGroupMember(group: String, member: String)
    suspend fun removeGroupMember(group: String, member: String)
    suspend fun leaveGroup(group: String)
    suspend fun retry(message: String)
    suspend fun markRead(conversation: String)
    suspend fun deleteMessage(message: String)
    suspend fun clearHistory(conversation: String)
    suspend fun deleteConversation(conversation: String)
    fun dismissError()
}
