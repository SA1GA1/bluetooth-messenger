package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.ui.global.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Chats(vm: AppViewModel) {
    val conversations by vm.conversations.collectAsState()
    val peers by vm.discovery.peers.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val own by vm.repository.ownId.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                vm.action {
                    try {
                        vm.session.reconnect()
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (conversations.isEmpty())
                item {
                    Text("Начните с QR-обмена или поиска рядом", Modifier.padding(24.dp))
                }
            items(conversations, key = { it.id }) { conversation ->
                val online =
                    conversation.type == ConversationType.Direct &&
                        own != null &&
                        contacts.any { contact ->
                            directConversationId(own!!, contact.id) == conversation.id &&
                                peers.any { it.userId == contact.id }
                        }
                ListItem(
                    headlineContent = { Text(conversation.name) },
                    supportingContent = { Text(conversation.lastText, maxLines = 2) },
                    leadingContent = { ContactAvatar(conversation.name, online) },
                    trailingContent = {
                        Column {
                            Text(timeLabel(conversation.updatedAt))
                            if (conversation.unreadCount > 0)
                                Badge { Text("${conversation.unreadCount}") }
                            Text("🔒 E2E")
                        }
                    },
                    modifier = Modifier.clickable { vm.open(conversation.id) },
                )
            }
        }
    }
}
