package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@Composable
internal fun Nearby(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val peers by vm.discovery.peers.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val messages by vm.publicMessages.collectAsState()
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ToggleRow("Публичный режим", settings.publicMode, vm::publicMode)
        if(settings.publicMode) {
            Text("Открытый эфир без E2E и подписей. Сообщения удаляются через 24 часа. Все ответы видны всем рядом.", color = MaterialTheme.colorScheme.error)
            Text("Анонимных устройств рядом: ${peers.take(20).size}")
            LazyColumn(Modifier.weight(1f)) {
                items(peers.take(20).size) { Text("● Анонимное устройство", Modifier.padding(8.dp)) }
                items(messages, key = { it.id }) { ListItem(headlineContent = { Text(it.text) }, supportingContent = { Text("${if(it.mine) "Вы" else "Аноним"} · ${timeLabel(it.timestamp)}") }) }
            }
            Composer(text, { text = it; vm.session.interacted() }, { val value = text; vm.action { vm.repository.sendPublic(value); text = "" } })
        } else {
            val nearby = contacts.filter { contact -> contact.verified && peers.any { it.userId == contact.id } }
            if(nearby.isEmpty()) Text("Никого не нашли. Попробуйте подойти ближе", Modifier.padding(vertical = 24.dp))
            LazyColumn { items(nearby, key = { it.id }) { contact -> ListItem(headlineContent = { Text(contact.name) },
                supportingContent = { Text("● В сети · E2E") }, leadingContent = { ContactAvatar(contact.name,true) }, modifier = Modifier.clickable { vm.openContact(contact.id) }) } }
        }
    }
}

