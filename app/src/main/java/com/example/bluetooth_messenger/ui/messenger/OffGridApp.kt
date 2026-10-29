package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.navigation.compose.*
import com.example.bluetooth_messenger.domain.session.*
import com.example.bluetooth_messenger.ui.auth.PinScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffGridApp(vm: AppViewModel, onClose: () -> Unit, onBiometric: (Boolean) -> Unit) {
    val session by vm.session.state.collectAsState()
    val error by vm.operationError.collectAsState()
    val sessionError by vm.session.error.collectAsState()
    val repoError by vm.repository.errors.collectAsState()
    var reset by remember { mutableStateOf(false) }
    BackHandler { if (vm.selectedConversation.value != null) vm.open(null) else onClose() }
    val external = rememberExternalActions(vm)
    CompositionLocalProvider(LocalExternalActions provides external) {
    Surface(Modifier.fillMaxSize()) {
        when (val current = session) {
            SessionState.Checking -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.padding(32.dp)) }
            SessionState.Onboarding -> PinScreen(true, { vm.submitPin(it, true) }, { reset = true })
            is SessionState.Locked -> {
                LaunchedEffect(current) { if (current.method == UnlockMethod.Biometric) onBiometric(false) }
                PinScreen(false, { vm.submitPin(it, false) }, { reset = true })
            }
            SessionState.Ready -> MainContent(vm, { reset = true }, onBiometric)
            is SessionState.Fatal -> Column(Modifier.padding(28.dp)) {
                Text("Защищённый профиль недоступен", style = MaterialTheme.typography.headlineSmall)
                Text(current.description)
                TextButton(onClick = { reset = true }) { Text("Сбросить локальные данные") }
            }
        }
    }
    }
    val displayedError = error ?: sessionError ?: repoError
    if (displayedError != null) AlertDialog(onDismissRequest = vm::dismissError,
        title = { Text("Не удалось выполнить действие") }, text = { Text(displayedError) },
        confirmButton = { TextButton(onClick = vm::dismissError) { Text("Понятно") } })
    if (reset) Confirm("Сбросить все данные?", "Контакты, сообщения, файлы и ключи будут удалены с этого устройства. Восстановить их нельзя.",
        { reset = false }, { reset = false; vm.reset() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(vm: AppViewModel, reset: () -> Unit, biometric: (Boolean) -> Unit) {
    RadioPermissions(vm)
    val nav = rememberNavController()
    val route by nav.currentBackStackEntryAsState()
    val selected by vm.selectedConversation.collectAsState()
    var sheet by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<String?>(null) }
    val pairings by vm.repository.pairings.collectAsState()
    if (selected != null) {
        ChatScreen(vm, selected!!)
    } else Scaffold(topBar = { TopAppBar(title = { Text("OffGrid") }) }, bottomBar = {
        NavigationBar { listOf("chats" to "Чаты", "nearby" to "Рядом", "settings" to "Настройки").forEach { (path, label) ->
            NavigationBarItem(selected = route?.destination?.route == path, onClick = {
                nav.navigate(path) { popUpTo("chats") { saveState = true }; launchSingleTop = true; restoreState = true }
            }, icon = { Text(when(path) { "chats" -> "◉"; "nearby" -> "◎"; else -> "⚙" }) }, label = { Text(label) })
        } }
    }, floatingActionButton = { FloatingActionButton(onClick = { sheet = true }) { Text("+", style = MaterialTheme.typography.headlineMedium) } }) { padding ->
        Column(Modifier.padding(padding)) {
        NetworkBanner(vm)
        NavHost(nav, "chats", Modifier.weight(1f), enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
            exitTransition = { slideOutHorizontally { -it / 4 } + fadeOut() }) {
            composable("chats") { Chats(vm) }
            composable("nearby") { Nearby(vm) }
            composable("settings") { Settings(vm, reset, biometric) }
        }
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        listOf("qr" to "Мой QR", "scan" to "Сканировать QR", "nearby" to "Найти рядом", "group" to "Создать группу").forEach { (key, label) ->
            TextButton(onClick = { sheet = false; if(key == "nearby") nav.navigate("nearby") else overlay = key }, Modifier.fillMaxWidth()) { Text(label) }
        }
    }
    when(overlay) {
        "qr" -> { val qr by vm.repository.ownQr.collectAsState(); QrDialog("Мой QR", qr, { overlay = null }, { overlay = "scan" }) }
        "scan" -> ScanContact(vm) { overlay = null }
        "group" -> CreateGroup(vm) { overlay = null }
    }
    pairings.firstOrNull()?.let { prompt -> PairingDialog(vm, prompt) }
}

