package com.example.bluetooth_messenger.ui.global

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth_messenger.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppViewModel(
    private val onboardingRepository: OnboardingRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<AppState>(AppState.Checking)
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    init {
        checkInitialState()
    }

    private fun checkInitialState() {
        viewModelScope.launch {
            _uiState.value = AppState.Checking

            val isCompleted = onboardingRepository.isOnboardingCompletedFlow.first()

            if (isCompleted) {
                _uiState.value = AppState.Lock(authMethods = AuthMethods(
                        pinAvailable = true,
                        biometricAvailable = false
                    )
                )
            }
            else {
                _uiState.value = AppState.Onboarding
            }
        }
    }

    fun onEvent(event: AppEvent) {
        when (event) {

            is AppEvent.UnlockedSuccess -> {
                _uiState.value = AppState.Main
            }


            is AppEvent.RequestInitialization -> {
                _uiState.value = AppState.Onboarding
            }

            is AppEvent.OnboardingCompleted -> {
                viewModelScope.launch { onboardingRepository.saveOnboardingStatus(true) }
                _uiState.value = AppState.Lock(authMethods = AuthMethods(
                    pinAvailable = true,
                    biometricAvailable = false)
                )
            }

            is AppEvent.UnlockedFailed, AppEvent.AppSendToBackground -> {
                _uiState.value = AppState.Lock(authMethods = AuthMethods(
                    pinAvailable = true,
                    biometricAvailable = false)
                )
            }
        }
    }
}