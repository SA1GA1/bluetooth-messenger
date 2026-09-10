package com.example.bluetooth_messenger.ui.global

sealed interface AppState {
    object Checking: AppState
    object Onboarding: AppState
    object Main: AppState
    data class Lock(val authMethods: AuthMethods): AppState
    data class FatalError(val reason: FatalErrorReason): AppState
}