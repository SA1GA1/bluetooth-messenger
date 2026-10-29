package com.example.bluetooth_messenger.ui.messenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.ui.global.AppViewModel
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatScreen(vm: AppViewModel, id: String) {
    val context = LocalContext.current
    val conversations by vm.conversations.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val peers by vm.discovery.peers.collectAsState()
    val own by vm.repository.ownId.collectAsState()
    val members by vm.members.collectAsState()
    val busy by vm.repository.transferBusy.collectAsState()
    val conversation = conversations.firstOrNull { it.id == id }
    val messages = vm.messages.collectAsLazyPagingItems()
    var text by remember(id) { mutableStateOf("") }
    var details by remember(id) { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var info by remember { mutableStateOf<ChatMessage?>(null) }
    var export by remember { mutableStateOf<ChatMessage?>(null) }
    var photoId by remember { mutableStateOf<String?>(null) }
    val external = LocalExternalActions.current
    BackHandler { vm.open(null) }
    LaunchedEffect(messages.itemSnapshotList.items.map { it.id to it.status }) { vm.action { vm.repository.markRead(id) } }
    Scaffold(topBar = { TopAppBar(title = {
        Column { Text(conversation?.name ?: "Чат", fontSize = 17.sp)
            val status = if(conversation?.type == ConversationType.Group) "${members.count { it.active && (it.id == own || peers.any { peer -> peer.userId == it.id }) }} из ${members.count { it.active }} в сети"
                else if(contacts.any { own != null && directConversationId(own!!,it.id) == id && peers.any { peer -> peer.userId == it.id } }) "В сети · E2E" else "Вне зоны · Ожидание"
            Text(status,fontSize = 11.sp) }
    }, navigationIcon = { TextButton(onClick = { vm.open(null) }) { Text("Назад") } }, actions = { TextButton(onClick = { details = true }) { Text("ⓘ") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 12.dp).fillMaxSize()) {
            NetworkBanner(vm)
            if(conversation?.type == ConversationType.Group) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                members.filter { it.active }.forEach { member ->
                    val online = member.id == own || peers.any { it.userId == member.id }
                    SuggestionChip(onClick = { details = true },label = {
                        Text("${if(online) "●" else "○"} ${if(member.id == own) "Вы" else contacts.firstOrNull { it.id == member.id }?.name ?: "Участник"}",color = if(online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    })
                }
            }
            LazyColumn(Modifier.weight(1f),reverseLayout = true,verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages.itemCount,key = messages.itemKey { it.id }) { index -> messages[index]?.let { message ->
                    MessageBubble(message,
                        senderName = if(conversation?.type == ConversationType.Group) contacts.firstOrNull { it.id == message.senderId }?.name ?: "Участник" else null,
                        onClick = {
                            when(message.kind) { ContentType.Photo -> photoId = message.fileId; ContentType.File -> export = message; else -> if(message.targetCount > 1) info = message }
                        },
                        onLongClick = { actionMessage = message },
                        onRetry = { vm.action { vm.repository.retry(message.id) } })
                } }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(conversation?.active != false) {
                TextButton(onClick = { attachments = true }) { Text("+ Вложение") }
                Composer(text,{ text = it; vm.session.interacted() },{ val value = text; vm.action { vm.repository.sendText(id,value); text = "" } })
            } else Text("Этот чат закрыт",Modifier.padding(16.dp))
        }
    }
    if(details && conversation != null) Details(vm,conversation) { details = false }
    if(attachments) ModalBottomSheet(onDismissRequest = { attachments = false }) {
        listOf("Камера","Фото из галереи","Файл","Геолокация").forEachIndexed { index,label -> TextButton(onClick = {
            attachments = false
            when(index) { 0 -> camera = true; 1 -> external.pick(id,true); 2 -> external.pick(id,false); 3 -> external.location(id) }
        },Modifier.fillMaxWidth()) { Text(label) } }
    }
    if(camera) CameraDialog(false,{ camera = false },{}, { bytes ->
        camera = false; vm.action { try { vm.repository.sendAttachment(id,AttachmentSource("Фото.jpg","image/jpeg",bytes.size.toLong()) { ByteArrayInputStream(bytes) },true) } finally { bytes.fill(0) } }
    })
    actionMessage?.let { message -> AlertDialog(onDismissRequest = { actionMessage = null },title = { Text("Сообщение") },text = { Column {
        TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OffGrid",message.text)); actionMessage = null }) { Text("Копировать текст") }
        TextButton(onClick = { vm.action { vm.repository.deleteMessage(message.id) }; actionMessage = null }) { Text("Удалить у себя") }
        TextButton(onClick = { info = message; actionMessage = null }) { Text("Информация") }
        if(message.fileId != null) TextButton(onClick = { export = message; actionMessage = null }) { Text("Экспортировать файл") }
    } },confirmButton = { TextButton(onClick = { actionMessage = null }) { Text("Закрыть") } }) }
    info?.let { message ->
        val recipients by remember(message.id) { vm.repository.recipients(message.id) }.collectAsState(initial = emptyList())
        AlertDialog(onDismissRequest = { info = null },title = { Text("Информация о сообщении") },text = {
            Column {
                Text("${java.util.Date(message.timestamp)}\n${statusLabel(message)}\n${message.fileSize} байт${message.error?.let { "\n$it" } ?: ""}")
                recipients.forEach { recipient -> Text("${recipient.name.ifBlank { "Участник" }} · ${deliveryLabel(recipient.status)}") }
            }
        },confirmButton = { TextButton(onClick = { info = null }) { Text("Закрыть") } })
    }
    export?.let { message -> AlertDialog(onDismissRequest = { export = null },title = { Text("Экспортировать файл?") },text = { Text("Экспортированная копия находится вне защиты OffGrid. После выбора места снова разблокируйте приложение.") },
        confirmButton = { TextButton(onClick = { export = null; external.export(message) }) { Text("Выбрать место") } },dismissButton = { TextButton(onClick = { export = null }) { Text("Отмена") } }) }
    photoId?.let { file -> PhotoPreview(vm,file) { photoId = null } }
}
