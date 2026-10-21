package com.example.bluetooth_messenger.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.bluetooth_messenger.data.local.preferences.dataStore
import com.example.bluetooth_messenger.domain.repository.AppSettings
import com.example.bluetooth_messenger.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(@ApplicationContext context: Context) : SettingsRepository {
    private val store = context.dataStore
    override val settings = store.data.map { preferences ->
        AppSettings(preferences[TIMEOUT]?.takeIf { it in setOf(1, 3, 5, 15) } ?: 5,
            preferences[RECEIPTS] ?: false, preferences[PUBLIC] ?: false)
    }
    override suspend fun setTimeout(minutes: Int) {
        require(minutes in setOf(1, 3, 5, 15))
        store.edit { it[TIMEOUT] = minutes }
    }
    override suspend fun setReadReceipts(enabled: Boolean) { store.edit { it[RECEIPTS] = enabled } }
    override suspend fun setPublicMode(enabled: Boolean) { store.edit { it[PUBLIC] = enabled } }
    private companion object {
        val TIMEOUT = intPreferencesKey("timeout_minutes")
        val RECEIPTS = booleanPreferencesKey("read_receipts")
        val PUBLIC = booleanPreferencesKey("public_mode")
    }
}
