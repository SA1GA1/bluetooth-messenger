package com.example.bluetooth_messenger.domain.session

import kotlinx.coroutines.flow.StateFlow

sealed interface SessionState {
    data object Checking : SessionState
    data object Onboarding : SessionState
    data class Locked(val method: UnlockMethod) : SessionState
    data object Ready : SessionState
    data class Fatal(val description: String) : SessionState
}

interface SessionController {
    val state: StateFlow<SessionState>
    val error: StateFlow<String?>
    val biometricEnabled: StateFlow<Boolean>
    val networkError: StateFlow<String?>
    suspend fun setup(pin: CharArray)
    suspend fun unlock(pin: CharArray)
    suspend fun reset()
    suspend fun reconnect()
    suspend fun disableBiometrics()
    fun foreground()
    fun background()
    fun closeTask()
    fun interacted()
    fun usePin()
    fun biometricFailed(enrollment: Boolean)
    fun dismissError()
}
