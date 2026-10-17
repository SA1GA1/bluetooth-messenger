package com.example.bluetooth_messenger.domain.repository

import kotlinx.coroutines.flow.Flow

data class AppSettings(val timeoutMinutes: Int = 5, val readReceipts: Boolean = false, val publicMode: Boolean = false)
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setTimeout(minutes: Int)
    suspend fun setReadReceipts(enabled: Boolean)
    suspend fun setPublicMode(enabled: Boolean)
}
