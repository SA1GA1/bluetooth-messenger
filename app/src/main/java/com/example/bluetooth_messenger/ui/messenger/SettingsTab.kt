package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel

@Composable
internal fun Settings(vm: AppViewModel, reset: () -> Unit, biometric: (Boolean) -> Unit) {
    val settings by vm.settings.collectAsState()
    val enabled by vm.session.biometricEnabled.collectAsState()
    Column(Modifier.padding(20.dp)) {
        Text("Блокировка при бездействии", style = MaterialTheme.typography.titleMedium)
        Row {
            listOf(1, 3, 5, 15).forEach { minutes ->
                FilterChip(
                    selected = settings.timeoutMinutes == minutes,
                    onClick = { vm.timeout(minutes) },
                    label = { Text("$minutes мин") },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        ToggleRow("Отчёты о прочтении", settings.readReceipts, vm::receipts)
        ToggleRow("Биометрия после сворачивания", enabled) {
            if (it) biometric(true) else vm.disableBiometrics()
        }
        Text(
            "Холодный запуск всегда требует PIN. При сворачивании приложение сразу блокируется.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = reset) {
            Text("Сбросить локальные данные", color = MaterialTheme.colorScheme.error)
        }
    }
}
