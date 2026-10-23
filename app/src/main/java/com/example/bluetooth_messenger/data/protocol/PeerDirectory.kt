package com.example.bluetooth_messenger.data.protocol

import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.domain.model.PeerIdentity
import com.example.bluetooth_messenger.domain.model.directConversationId
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import javax.inject.Inject

class PeerDirectory @Inject constructor(private val database: DatabaseSession, private val identity: IdentityCrypto) {
    suspend fun contact(peer: String): PeerIdentity = database.use {
        val contact = checkNotNull(it.contacts().find(peer)) { "Контакт не подтверждён" }
        check(contact.verified)
        PeerIdentity(contact.id, contact.signingKey, contact.agreementKey)
    }
    suspend fun conversation(peer: String, conversation: String, epoch: Int): PeerIdentity {
        val own = identity.identity().userId
        val chat = database.use { it.contacts().conversation(conversation) }
        if (chat == null || chat.type == "Direct") {
            require((chat == null || chat.peerId == peer) && conversation == directConversationId(own, peer))
            return contact(peer)
        }
        val members = database.use { it.protocol().members(conversation, epoch) }
        require(members.any { it.memberId == own })
        return IdentityCodec().decode(members.first { it.memberId == peer }.identity)
    }
}
