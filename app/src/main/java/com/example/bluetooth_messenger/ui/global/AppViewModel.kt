package com.example.bluetooth_messenger.ui.global

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth_messenger.domain.repository.IdentityKeyRepository
import com.example.bluetooth_messenger.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppViewModel(
    private val onboardingRepository: OnboardingRepository,
    private val identityKeyRepository: IdentityKeyRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<AppState>(AppState.Checking)
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    init {
        checkInitialState()
    }

    private fun checkInitialState() {
        viewModelScope.launch {
            try {
                _uiState.value = AppState.Checking

                val isOnboardingCompleted = onboardingRepository.isOnboardingCompletedFlow.first()

                if (isOnboardingCompleted) {

                    val hasIdentityKeys = identityKeyRepository.hasIdentityKeys()
                    if (hasIdentityKeys) {
                        _uiState.value = AppState.Lock(
                            authMethods = AuthMethods(
                                pinAvailable = true,
                                biometricAvailable = false
                            )
                        )
                    }
                    else {
                        _uiState.value = AppState.FatalError(FatalErrorReason.IdentityKeysMissing)
                    }
                } else {
                    _uiState.value = AppState.Onboarding
                }
            }
            catch (e: Exception) {
                _uiState.value = AppState.FatalError(FatalErrorReason.InitializationFailed)
                println(e)
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
                    viewModelScope.launch {
                        try {
                            onboardingRepository.saveOnboardingStatus(true)

                            _uiState.value = AppState.Lock(
                                authMethods = AuthMethods(
                                    pinAvailable = true,
                                    biometricAvailable = false
                                )
                            )

                        }
                        catch (e: Exception) {
                            _uiState.value = AppState.FatalError(FatalErrorReason.InitializationFailed)
                            println(e)
                        }
                    }
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