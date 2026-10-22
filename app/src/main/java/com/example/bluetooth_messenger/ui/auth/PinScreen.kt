package com.example.bluetooth_messenger.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun PinScreen(setup: Boolean, onSubmit: (String) -> Unit, onReset: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("OffGrid", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(if (setup) "Общайтесь рядом без интернета" else "Введите PIN для входа")
        if (setup) {
            Spacer(Modifier.height(8.dp))
            Text("Создайте PIN из 6 цифр. Восстановить забытый PIN нельзя.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        PinField(pin, { pin = it }, "PIN")
        if (setup) {
            Spacer(Modifier.height(12.dp))
            PinField(confirmation, { confirmation = it }, "Повторите PIN")
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { val submitted = pin; pin = ""; confirmation = ""; onSubmit(submitted) },
            enabled = pin.length == 6 && (!setup || confirmation == pin), modifier = Modifier.fillMaxWidth()) {
            Text(if (setup) "Создать защищённый профиль" else "Войти")
        }
        if (!setup) TextButton(onClick = onReset) { Text("Забыли PIN? Сбросить данные") }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, { onChange(it.filter { char -> char in '0'..'9' }.take(6)) },
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
}
