package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.bluetooth_messenger.domain.model.*

@Composable
internal fun Details(vm: AppViewModel, conversation: Conversation, dismiss: () -> Unit) {
    val members by vm.members.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val own by vm.repository.ownId.collectAsState()
    val peers by vm.discovery.peers.collectAsState()
    var details by remember(conversation.id) { mutableStateOf<ContactDetails?>(null) }
    var qr by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val contact = contacts.firstOrNull { own != null && directConversationId(own!!, it.id) == conversation.id }
    LaunchedEffect(contact?.id) { contact?.let { vm.action { details = vm.repository.contactDetails(it.id) } } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(conversation.name) }, text = {
        Column {
            if(conversation.type == ConversationType.Direct) {
                Text("Отпечаток ключей", style = MaterialTheme.typography.titleSmall)
                Text(details?.fingerprint ?: "Загрузка…")
                TextButton(onClick = { qr = true }, enabled = details != null) { Text("QR для проверки") }
            } else {
                Text("${members.count { it.active && (it.id == own || peers.any { peer -> peer.userId == it.id }) }} из ${members.count { it.active }} в сети")
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(members.filter { it.active }, key = { it.id }) { member ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(if(member.id == own) "Вы" else contacts.firstOrNull { it.id == member.id }?.name ?: "Участник", Modifier.weight(1f))
                            if(conversation.active && conversation.ownerId == own && member.id != own) TextButton(onClick = { vm.action { vm.repository.removeGroupMember(conversation.id,member.id) } }) { Text("Исключить") }
                        }
                    }
                    if(conversation.active && conversation.ownerId == own && members.count { it.active } < 8) items(contacts.filter { contact -> contact.verified && members.none { it.id == contact.id && it.active } }, key = { "add-${it.id}" }) { candidate ->
                        TextButton(onClick = { vm.action { vm.repository.addGroupMember(conversation.id,candidate.id) } }) { Text("Добавить ${candidate.name}") }
                    }
                }
                if(conversation.active) TextButton(onClick = { confirm = "leave" }) { Text(if(conversation.ownerId == own) "Закрыть группу" else "Выйти из группы") }
            }
            TextButton(onClick = { confirm = "clear" }) { Text("Очистить историю") }
            if(conversation.type == ConversationType.Direct) TextButton(onClick = { confirm = "delete" }) { Text("Удалить чат") }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Готово") } })
    if(qr && details != null) QrDialog("Проверка ключей", details!!.verificationQr, { qr = false })
    if(confirm != null) Confirm(if(confirm == "leave") "Покинуть группу?" else "Удалить локальные данные?",
        if(confirm == "leave" && conversation.ownerId == own) "Группа будет закрыта для всех участников." else "История удаляется только на вашем устройстве. Действие нельзя отменить.",
        { confirm = null }, {
            val action = confirm
            confirm = null
            vm.action {
                when(action) {
                    "clear" -> vm.repository.clearHistory(conversation.id)
                    "delete" -> {
                        vm.repository.deleteConversation(conversation.id)
                        vm.open(null)
                        dismiss()
                    }
                    "leave" -> {
                        vm.repository.leaveGroup(conversation.id)
                        vm.open(null)
                        dismiss()
                    }
                }
            }
        })
}
