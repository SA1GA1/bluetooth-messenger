package com.example.bluetooth_messenger.data.session

import android.content.Context
import com.example.bluetooth_messenger.core.coroutines.ApplicationScope
import com.example.bluetooth_messenger.core.coroutines.SessionTasks
import com.example.bluetooth_messenger.domain.time.AppClock
import com.example.bluetooth_messenger.core.security.BiometricGateway
import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.repository.IdentityKeyRepository
import com.example.bluetooth_messenger.data.security.LocalDataResetter
import com.example.bluetooth_messenger.data.security.PinVault
import com.example.bluetooth_messenger.domain.repository.AppSettings
import com.example.bluetooth_messenger.domain.repository.SettingsRepository
import com.example.bluetooth_messenger.domain.session.*
import com.example.bluetooth_messenger.domain.transport.DiscoveryService
import com.example.bluetooth_messenger.domain.transport.PeerTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: PinVault,
    private val identity: IdentityCrypto,
    private val identityKeys: IdentityKeyRepository,
    private val database: DatabaseSession,
    private val discovery: DiscoveryService,
    private val transport: PeerTransport,
    private val resetter: LocalDataResetter,
    settings: SettingsRepository,
    private val clock: AppClock,
    private val tasks: SessionTasks,
    @ApplicationScope private val scope: CoroutineScope,
) : SessionController, BiometricGateway {
    private val mutex = Mutex()
    private val policy = SessionPolicy()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Checking)
    override val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    override val error = mutableError.asStateFlow()
    private val mutableNetworkError = MutableStateFlow<String?>(null)
    override val networkError = mutableNetworkError.asStateFlow()
    private val mutableBiometrics = MutableStateFlow(vault.biometricsEnabled)
    override val biometricEnabled = mutableBiometrics.asStateFlow()
    private val preferences = settings.settings.catch {
        mutableError.value = "Настройки недоступны; применён безопасный режим"
        emit(AppSettings(timeoutMinutes = 1, readReceipts = false))
    }.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    private var radioJob: Job? = null
    private var foreground = true
    private var backgroundEpoch = 0L
    private var biometricEpoch = -1L
    private var biometricUnlockToken = -1L

    init {
        scope.launch { initialize() }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (foreground && state.value == SessionState.Ready) {
                    policy.checkIdle(clock.elapsedMillis(), preferences.value.timeoutMinutes)
                    if (!policy.unlocked) block(forcePin = true)
                }
            }
        }
    }

    private suspend fun initialize() = mutex.withLock {
        mutableState.value = try {
            withContext(Dispatchers.IO) {
                if (vault.configured) {
                    identity.identity()
                    SessionState.Locked(UnlockMethod.Pin)
                } else if (context.getDatabasePath("offgrid.db").exists()) {
                    SessionState.Fatal("Защита базы утрачена. Автоматический сброс запрещён.")
                } else SessionState.Onboarding
            }
        } catch (failure: Exception) { SessionState.Fatal(failure.message ?: "Ключи идентичности недоступны") }
    }

    override suspend fun setup(pin: CharArray) = authenticate(pin, setup = true)
    override suspend fun unlock(pin: CharArray) = authenticate(pin, setup = false)

    private suspend fun authenticate(pin: CharArray, setup: Boolean) {
        try {
            mutex.withLock {
                if (!foreground || if (setup) state.value != SessionState.Onboarding else state.value !is SessionState.Locked) return@withLock
                val token = policy.beginUnlock()
                mutableState.value = SessionState.Checking
                try {
                    if (setup) identityKeys.generateIdentityKeys()
                    if (!policy.canCompleteUnlock(token)) return@withLock
                    val key = withContext(Dispatchers.IO) { if (setup) vault.setup(pin) else vault.openWithPin(pin) }
                    try { finishUnlock(key, token) } finally { key.fill(0) }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    cleanup()
                    if (foreground) {
                        mutableError.value = failure.message ?: "Не удалось открыть приложение"
                        mutableState.value = when {
                            !vault.configured -> SessionState.Onboarding
                            failure is IllegalArgumentException || withContext(Dispatchers.IO) { vault.remainingCooldown() > 0 } -> SessionState.Locked(UnlockMethod.Pin)
                            else -> SessionState.Fatal("Данные не открыты: ${failure.message ?: "ошибка защиты"}")
                        }
                    }
                }
            }
        } finally { pin.fill('\u0000') }
    }

    private suspend fun finishUnlock(key: ByteArray, token: Long) {
        if (!policy.canCompleteUnlock(token)) {
            cleanup()
            if (vault.configured) mutableState.value = SessionState.Locked(UnlockMethod.Pin)
            return
        }
        val ownIdentity = identity.identity()
        database.open(key)
        if (!policy.canCompleteUnlock(token)) {
            cleanup()
            if (vault.configured) mutableState.value = SessionState.Locked(UnlockMethod.Pin)
            return
        }
        policy.interacted(clock.elapsedMillis())
        policy.unlock()
        tasks.start()
        mutableState.value = SessionState.Ready
        mutableError.value = null
        mutableBiometrics.value = vault.biometricsEnabled
        startRadio(ownIdentity.userId)
    }

    private fun startRadio(id: String) {
        radioJob?.cancel()
        radioJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { discovery.start(id) }
                mutableNetworkError.value = null
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { mutableNetworkError.value = failure.message ?: "Связь недоступна" }
        }
    }

    override suspend fun reconnect() = mutex.withLock {
        if (state.value != SessionState.Ready) return@withLock
        radioJob?.cancelAndJoin()
        startRadio(identity.identity().userId)
    }

    override fun foreground() { foreground = true; policy.foreground() }
    override fun background() { foreground = false; block(forcePin = false) }
    override fun closeTask() { foreground = false; block(forcePin = true) }
    override fun interacted() { if (state.value == SessionState.Ready) policy.interacted(clock.elapsedMillis()) }

    private fun block(forcePin: Boolean) {
        backgroundEpoch++
        policy.background(vault.biometricsEnabled)
        if (foreground) policy.foreground()
        if (forcePin || state.value is SessionState.Locked) policy.biometricFailed()
        mutableState.value = if (vault.configured) SessionState.Locked(policy.requiredMethod) else SessionState.Onboarding
        tasks.cancel()
        radioJob?.cancel()
        discovery.stop()
        transport.stop()
        scope.launch { mutex.withLock { cleanup() } }
    }

    private suspend fun cleanup() = withContext(NonCancellable) {
        tasks.stop()
        radioJob?.cancelAndJoin()
        discovery.stop()
        transport.stop()
        withContext(Dispatchers.IO) { vault.lock() }
        database.close()
    }

    override fun usePin() {
        policy.biometricFailed()
        if (state.value is SessionState.Locked) mutableState.value = SessionState.Locked(UnlockMethod.Pin)
    }
    override fun biometricFailed(enrollment: Boolean) {
        biometricEpoch = -1
        scope.launch(Dispatchers.IO) { vault.cancelBiometricOperation() }
        if (!enrollment) usePin()
    }
    override fun dismissError() { mutableError.value = null }

    override suspend fun prepare(enrollment: Boolean): Cipher = mutex.withLock {
        check(foreground && if (enrollment) state.value == SessionState.Ready
            else state.value == SessionState.Locked(UnlockMethod.Biometric))
        biometricEpoch = backgroundEpoch
        if (!enrollment) biometricUnlockToken = policy.beginUnlock()
        withContext(Dispatchers.IO) { if (enrollment) vault.prepareBiometricEnrollment() else vault.prepareBiometricUnlock() }
    }

    override suspend fun complete(cipher: Cipher, enrollment: Boolean) = mutex.withLock {
        check(foreground && biometricEpoch == backgroundEpoch) { "Сессия биометрии завершена" }
        if (enrollment) {
            check(state.value == SessionState.Ready)
            withContext(Dispatchers.IO) { vault.completeBiometricEnrollment(cipher) }
            mutableBiometrics.value = true
        } else {
            check(state.value == SessionState.Locked(UnlockMethod.Biometric))
            try {
                val key = withContext(Dispatchers.IO) { vault.completeBiometricUnlock(cipher) }
                try { finishUnlock(key, biometricUnlockToken) } finally { key.fill(0) }
            } catch (failure: Exception) {
                cleanup()
                usePin()
                throw failure
            }
        }
        biometricEpoch = -1
    }

    override suspend fun disableBiometrics() = mutex.withLock {
        check(state.value == SessionState.Ready)
        withContext(Dispatchers.IO) { vault.disableBiometrics() }
        mutableBiometrics.value = false
    }

    override suspend fun reset() = mutex.withLock {
        mutableState.value = SessionState.Checking
        policy.biometricFailed()
        cleanup()
        try {
            resetter.reset()
            mutableBiometrics.value = false
            mutableError.value = null
            mutableState.value = SessionState.Onboarding
        } catch (failure: Exception) { mutableState.value = SessionState.Fatal(failure.message ?: "Не удалось удалить данные") }
    }
}
