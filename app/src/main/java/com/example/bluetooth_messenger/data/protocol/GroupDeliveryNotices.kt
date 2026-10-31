package com.example.bluetooth_messenger.data.protocol

import androidx.room.withTransaction
import com.example.bluetooth_messenger.data.local.database.*
import com.example.bluetooth_messenger.domain.model.newId
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.time.AppClock
import com.example.bluetooth_messenger.domain.transport.DiscoveryService
import javax.inject.Inject

class GroupDeliveryNotices @Inject constructor(
    private val database: DatabaseSession,
    private val identity: IdentityCrypto,
    private val discovery: DiscoveryService,
    private val clock: AppClock,
) {
    suspend fun deferred(conversation: String) {
        val own = identity.identity().userId
        val online = discovery.peers.value.map { it.userId }.toSet()
        database.use { db -> db.withTransaction {
            val chat = db.contacts().conversation(conversation) ?: return@withTransaction
            if (chat.type != "Group" || !chat.active) return@withTransaction
            for (member in db.protocol().members(chat.id, chat.epoch)) {
                if (member.memberId != own && member.memberId !in online) {
                    val name = db.contacts().find(member.memberId)?.name ?: "Участник"
                    insert(db, chat.id, own, "$name вне зоны. Доставка отложена.")
                }
            }
        } }
    }

    suspend fun delivered() {
        val own = identity.identity().userId
        database.use { db -> db.withTransaction {
            val pending = db.messages().unnotifiedGroupDeliveries()
            val grouped = pending.groupBy { delivery ->
                checkNotNull(db.messages().find(checkNotNull(delivery.messageId))).conversationId to delivery.targetId
            }
            for ((recipient, deliveries) in grouped) {
                val (conversation, target) = recipient
                val name = db.contacts().find(target)?.name ?: "Участник"
                val count = deliveries.size
                val noun = when {
                    count % 100 in 11..14 -> "сообщений"
                    count % 10 == 1 -> "сообщение"
                    count % 10 in 2..4 -> "сообщения"
                    else -> "сообщений"
                }
                insert(db, conversation, own, "$name получил $count $noun.")
                db.messages().markDeliveryNotified(deliveries.map { it.packetId }, target)
            }
        } }
    }

    private suspend fun insert(db: OffGridDatabase, conversation: String, own: String, text: String) {
        val now = clock.wallMillis()
        db.messages().enqueue(MessageEntity(newId(), conversation, own, "System", text, now, now,
            incoming = false, status = "Delivered"), emptyList())
    }
}
