package com.example.bluetooth_messenger.ui.messenger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ContactAvatar(name: String, online: Boolean) {
    Box(
        Modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initials =
            name
                .trim()
                .split(Regex("\\s+"))
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
        Text(initials, style = MaterialTheme.typography.titleMedium)
        if (online)
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .size(11.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
    }
}
