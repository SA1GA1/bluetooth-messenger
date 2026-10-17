package com.example.bluetooth_messenger.data.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.example.bluetooth_messenger.data.local.preferences.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject

class LocalDataResetter @Inject constructor(@ApplicationContext private val context: Context, private val vault: PinVault) {
    suspend fun reset() = withContext(Dispatchers.IO) {
        val path = context.getDatabasePath("offgrid.db")
        if (path.exists()) check(context.deleteDatabase("offgrid.db")) { "Не удалось удалить базу" }
        val files = File(context.filesDir, "attachments")
        if (files.exists()) check(files.deleteRecursively()) { "Не удалось удалить вложения" }
        context.dataStore.edit { it.clear() }
        check(context.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().clear().commit())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf("identity_signing_key", "identity_agreement_key").forEach { store.deleteEntry(it) }
        vault.resetSecrets()
    }
}
