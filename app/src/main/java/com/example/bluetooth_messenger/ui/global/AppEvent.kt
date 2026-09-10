package com.example.bluetooth_messenger.ui.global

sealed interface AppEvent {
    data object OnboardingCompleted : AppEvent
    data object UnlockedSuccess : AppEvent
    data object AppSendToBackground : AppEvent
    data object RequestInitialization : AppEvent
    data class UnlockedFailed(val failsCount: Int = 0) : AppEvent
}