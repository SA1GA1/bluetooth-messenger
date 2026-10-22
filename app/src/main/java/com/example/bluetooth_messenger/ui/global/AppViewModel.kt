package com.example.bluetooth_messenger.ui.global

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth_messenger.domain.repository.SettingsRepository
import com.example.bluetooth_messenger.domain.session.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    val session: SessionController,
    val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableError = MutableStateFlow<String?>(null)
    val operationError = mutableError.asStateFlow()

    fun submitPin(pin: String, setup: Boolean) = launch {
        if (setup) session.setup(pin.toCharArray()) else session.unlock(pin.toCharArray())
    }
    fun reconnect() = launch { session.reconnect() }
    fun reset() = launch { session.reset() }
    fun disableBiometrics() = launch { session.disableBiometrics() }
    fun timeout(minutes: Int) = launch { settingsRepository.setTimeout(minutes) }
    fun receipts(enabled: Boolean) = launch { settingsRepository.setReadReceipts(enabled) }
    fun publicMode(enabled: Boolean) = launch { settingsRepository.setPublicMode(enabled) }
    fun reportError(description: String) { mutableError.value = description }
    fun dismissError() { mutableError.value = null; session.dismissError() }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { mutableError.value = failure.message ?: "Не удалось выполнить действие" }
    }
}
