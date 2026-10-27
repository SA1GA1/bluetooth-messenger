package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel
import com.example.bluetooth_messenger.domain.model.PairingPrompt

@Composable
internal fun PairingDialog(vm: AppViewModel, prompt: PairingPrompt) {
    var name by remember(prompt.peerId) { mutableStateOf(prompt.name) }
    var matches by remember(prompt.peerId, prompt.code) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = {}, title = { Text("Сверьте код знакомства") }, text = {
        Column { Text("Оба устройства должны показывать одинаковый шестизначный код.")
            Text(prompt.code ?: "Ожидание ответа…", style = MaterialTheme.typography.headlineLarge)
            OutlinedTextField(name, { name = it; vm.session.interacted() }, label = { Text("Имя контакта") }, singleLine = true)
            ToggleRow("Коды на устройствах совпадают", matches) { matches = it }
            if(prompt.confirmed) Text("Подтверждено вами. Ожидаем подтверждения второго устройства.")
        }
    }, confirmButton = { TextButton(onClick = { vm.action { vm.repository.confirmPairing(prompt.peerId, name) } },
        enabled = name.isNotBlank() && matches && prompt.code != null && !prompt.confirmed) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = { vm.action { vm.repository.rejectPairing(prompt.peerId) } }) { Text("Отклонить") } })
}

