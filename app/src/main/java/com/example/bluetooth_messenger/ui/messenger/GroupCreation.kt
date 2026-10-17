package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bluetooth_messenger.ui.global.AppViewModel

@Composable
internal fun CreateGroup(vm: AppViewModel, dismiss: () -> Unit) {
    val contacts by vm.contacts.collectAsState()
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Создать группу") },
        text = {
            Column {
                OutlinedTextField(
                    name,
                    {
                        name = it
                        vm.session.interacted()
                    },
                    label = { Text("Название группы") },
                )
                Text("До 8 участников вместе с вами")
                LazyColumn {
                    items(contacts.filter { it.verified }, key = { it.id }) { contact ->
                        Row {
                            Checkbox(
                                contact.id in selected,
                                { checked ->
                                    if (!checked) selected = selected - contact.id
                                    else if (selected.size < 7) selected = selected + contact.id
                                },
                            )
                            Text(contact.name, Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.createGroup(name, selected.toList(), dismiss) },
                enabled = name.isNotBlank() && selected.isNotEmpty(),
            ) {
                Text("Создать")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } },
    )
}
