package com.example.bluetooth_messenger.ui.messenger

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.lifecycle.LifecycleCoroutineScope
import com.example.bluetooth_messenger.ui.global.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class NativeBiometric(private val activity: Activity, private val vm: AppViewModel, private val scope: LifecycleCoroutineScope) {
    private var signal: CancellationSignal? = null
    private var finished = true
    private var enrolling = false
    private var generation = 0
    fun authenticate(enrollment: Boolean) {
        if(!finished) return
        finished = false; enrolling = enrollment
        val token = ++generation
        scope.launch {
            try {
                val cipher = vm.biometric.prepare(enrollment)
                if(finished || token != generation) return@launch
                val cancellation = CancellationSignal(); signal = cancellation
                val prompt = BiometricPrompt.Builder(activity).setTitle(if(enrollment) "Включить биометрию" else "Разблокировать OffGrid")
                    .setSubtitle("После первой ошибки потребуется PIN")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButton(if(enrollment) "Отмена" else "Ввести PIN",activity.mainExecutor) { _,_ -> fail(token) }.build()
                prompt.authenticate(BiometricPrompt.CryptoObject(cipher),cancellation,activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if(finished || token != generation) return
                            finished = true; signal = null
                            val authorized = result.cryptoObject?.cipher
                            if(authorized == null) { vm.session.biometricFailed(enrollment); return }
                            scope.launch {
                                try { vm.biometric.complete(authorized,enrollment) }
                                catch(failure: CancellationException) { throw failure }
                                catch(failure: Exception) { vm.session.biometricFailed(enrollment); if(enrollment) vm.reportError(failure.message ?: "Не удалось включить биометрию") }
                            }
                        }
                        override fun onAuthenticationFailed() { fail(token) }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { fail(token) }
                    })
            } catch(failure: CancellationException) { throw failure }
            catch(failure: Exception) { fail(token); if(enrollment && token == generation) vm.reportError(failure.message ?: "Биометрия недоступна") }
        }
    }
    private fun fail(token: Int? = null) {
        if(finished || (token != null && token != generation)) return
        finished = true
        val cancellation = signal; signal = null
        vm.session.biometricFailed(enrolling)
        cancellation?.cancel()
    }
    fun cancel() { fail() }
}
