package com.example.bluetooth_messenger.ui.global

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.bluetooth_messenger.core.paging.MessagePages
import com.example.bluetooth_messenger.core.security.BiometricGateway
import com.example.bluetooth_messenger.domain.model.*
import com.example.bluetooth_messenger.domain.repository.*
import com.example.bluetooth_messenger.domain.session.*
import com.example.bluetooth_messenger.domain.transport.DiscoveryService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppViewModel
@Inject
constructor(
    val session: SessionController,
    val settingsRepository: SettingsRepository,
    val repository: MessengerRepository,
    val discovery: DiscoveryService,
    val pages: MessagePages,
    val biometric: BiometricGateway,
) : ViewModel() {
    private val mutableError = MutableStateFlow<String?>(null)
    val operationError = mutableError.asStateFlow()
    val settings =
        settingsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppSettings(),
        )
    val contacts =
        repository.contacts.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )
    val conversations =
        repository.conversations.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )
    val publicMessages =
        repository.publicMessages.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )
    private val selected = MutableStateFlow<String?>(null)
    val selectedConversation = selected.asStateFlow()
    val messages =
        combine(selected, session.state) { id, state -> id.takeIf { state == SessionState.Ready } }
            .flatMapLatest {
                if (it == null) flowOf(PagingData.empty<ChatMessage>()) else pages.observe(it)
            }
            .cachedIn(viewModelScope)
    val members =
        combine(selected, session.state) { id, state -> id.takeIf { state == SessionState.Ready } }
            .flatMapLatest { if (it == null) flowOf(emptyList()) else repository.groupMembers(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var pendingOperation: (suspend () -> Unit)? = null

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                if (state == SessionState.Ready) {
                    val operation = pendingOperation
                    pendingOperation = null
                    operation?.let { runOperation(it) }
                }
            }
        }
    }

    fun open(conversation: String?) {
        selected.value = conversation
        if (conversation != null) action { repository.markRead(conversation) }
    }

    fun openContact(peer: String) = action { selected.value = repository.openContact(peer) }

    fun createGroup(name: String, members: List<String>, done: () -> Unit) = action {
        selected.value = repository.createGroup(name, members)
        done()
    }

    fun submitPin(pin: String, setup: Boolean) = action {
        if (setup) session.setup(pin.toCharArray()) else session.unlock(pin.toCharArray())
    }

    fun reconnect() = action { session.reconnect() }

    fun reset() = action {
        pendingOperation = null
        selected.value = null
        session.reset()
    }

    fun disableBiometrics() = action { session.disableBiometrics() }

    fun timeout(minutes: Int) = action { settingsRepository.setTimeout(minutes) }

    fun receipts(enabled: Boolean) = action { settingsRepository.setReadReceipts(enabled) }

    fun publicMode(enabled: Boolean) = action { settingsRepository.setPublicMode(enabled) }

    fun afterUnlock(operation: suspend () -> Unit) {
        pendingOperation = operation
        if (session.state.value == SessionState.Ready) {
            pendingOperation = null
            action(operation)
        }
    }

    fun reportError(description: String) {
        mutableError.value = description
    }

    fun dismissError() {
        mutableError.value = null
        session.dismissError()
        repository.dismissError()
    }

    fun action(block: suspend () -> Unit) = viewModelScope.launch { runOperation(block) }

    private suspend fun runOperation(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            mutableError.value = failure.message ?: "Не удалось выполнить действие"
        }
    }
}
