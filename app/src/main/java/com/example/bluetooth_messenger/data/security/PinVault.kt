package com.example.bluetooth_messenger.data.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.bluetooth_messenger.domain.security.PinAttempts
import com.example.bluetooth_messenger.domain.security.PinRateLimiter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PinVault @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val envelopes = PinEnvelope()
    private val limiter = PinRateLimiter()
    private var secret: ByteArray? = null
    private var biometricCipher: Cipher? = null
    private var enrolling = false
    val configured: Boolean get() = preferences.contains("envelope")
    val biometricsEnabled: Boolean get() = preferences.contains("biometric")

    @Synchronized
    fun setup(pin: CharArray): ByteArray {
        check(!configured && !context.getDatabasePath("offgrid.db").exists()) { "Для сброса данных требуется подтверждение" }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            val wrapped = envelopes.wrap(bytes, pin)
            val cipher = masterCipher(Cipher.ENCRYPT_MODE, create = true)
            val encrypted = cipher.iv + cipher.doFinal(wrapped.ciphertext)
            check(preferences.edit().putString("salt", encode(wrapped.salt))
                .putString("envelope", encode(encrypted)).commit()) { "Не удалось сохранить защиту" }
            secret?.fill(0)
            secret = bytes.copyOf()
            return bytes.copyOf()
        } finally { bytes.fill(0); pin.fill('\u0000') }
    }

    @Synchronized
    fun openWithPin(pin: CharArray): ByteArray {
        try {
            check(configured) { "Защита ещё не настроена" }
            val state = attempts()
            check(limiter.remaining(state, SystemClock.elapsedRealtime()) == 0L) { "Слишком много попыток. Подождите." }
            val encrypted = decode("envelope")
            require(encrypted.size >= 28)
            val cipher = masterCipher(Cipher.DECRYPT_MODE, encrypted.copyOfRange(0, 12))
            val inner = cipher.doFinal(encrypted, 12, encrypted.size - 12)
            val bytes = try {
                envelopes.unwrap(WrappedPinSecret(decode("salt"), inner), pin)
            } catch (_: AEADBadTagException) {
                saveAttempts(limiter.failed(state, SystemClock.elapsedRealtime(), boot()))
                throw IllegalArgumentException("Неверный PIN")
            } finally { inner.fill(0) }
            require(bytes.size == 32)
            saveAttempts(PinAttempts(boot = boot()))
            secret?.fill(0)
            secret = bytes.copyOf()
            return bytes
        } finally { pin.fill('\u0000') }
    }

    @Synchronized
    fun remainingCooldown(): Long = limiter.remaining(attempts(), SystemClock.elapsedRealtime())

    @Synchronized
    fun keyCopy(): ByteArray = checkNotNull(secret) { "Приложение заблокировано" }.copyOf()

    @Synchronized
    fun lock() {
        secret?.fill(0)
        secret = null
        biometricCipher = null
        enrolling = false
    }

    @Synchronized
    fun prepareBiometricEnrollment(): Cipher {
        check(secret != null)
        val key = key(BIOMETRIC, create = true, authenticated = true)
        enrolling = true
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(BIO_CONTEXT)
        }.also { biometricCipher = it }
    }

    @Synchronized
    fun completeBiometricEnrollment(cipher: Cipher) {
        check(cipher === biometricCipher && enrolling)
        val bytes = cipher.doFinal(checkNotNull(secret))
        check(preferences.edit().putString("biometric", encode(cipher.iv + bytes)).commit())
        biometricCipher = null
        enrolling = false
    }

    @Synchronized
    fun prepareBiometricUnlock(): Cipher {
        check(configured && biometricsEnabled)
        val bytes = decode("biometric")
        require(bytes.size == 60)
        enrolling = false
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(BIOMETRIC), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(BIO_CONTEXT)
        }.also { biometricCipher = it }
    }

    @Synchronized
    fun completeBiometricUnlock(cipher: Cipher): ByteArray {
        check(cipher === biometricCipher && !enrolling)
        val bytes = decode("biometric")
        val key = cipher.doFinal(bytes, 12, bytes.size - 12)
        require(key.size == 32)
        secret?.fill(0)
        secret = key.copyOf()
        biometricCipher = null
        return key
    }

    @Synchronized
    fun disableBiometrics() {
        check(secret != null)
        check(preferences.edit().remove("biometric").commit())
        store().deleteEntry(BIOMETRIC)
        biometricCipher = null
    }

    @Synchronized
    fun resetSecrets() {
        lock()
        check(preferences.edit().clear().commit())
        store().deleteEntry(MASTER)
        store().deleteEntry(BIOMETRIC)
    }

    private fun boot(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private fun attempts(): PinAttempts {
        val old = PinAttempts(preferences.getInt("failures", 0), preferences.getLong("blocked_until", 0), preferences.getInt("boot", -1))
        return limiter.normalize(old, SystemClock.elapsedRealtime(), boot()).also { if (it != old) saveAttempts(it) }
    }
    private fun saveAttempts(state: PinAttempts) {
        check(preferences.edit().putInt("failures", state.failures).putLong("blocked_until", state.blockedUntil)
            .putInt("boot", state.boot).commit())
    }
    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(name: String): ByteArray {
        val value = checkNotNull(preferences.getString(name, null)) { "Данные защиты утрачены" }
        require(value.length <= 1024)
        return Base64.getDecoder().decode(value)
    }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(alias: String, create: Boolean = false, authenticated: Boolean = false): SecretKey {
        val store = store()
        if (!store.containsAlias(alias)) {
            check(create) { "Ключ защиты утрачен. Автоматическое восстановление невозможно." }
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            if (authenticated) builder.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
            KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(builder.build()) }.generateKey()
        }
        return store.getKey(alias, null) as? SecretKey ?: error("Ключ защиты недоступен")
    }
    private fun masterCipher(mode: Int, iv: ByteArray? = null, create: Boolean = false): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (iv == null) init(mode, key(MASTER, create))
            else init(mode, key(MASTER), GCMParameterSpec(128, iv))
            updateAAD("offgrid-vault-master-v1".toByteArray())
        }
    private companion object {
        const val MASTER = "offgrid_vault_master_v1"
        const val BIOMETRIC = "offgrid_biometric_v1"
        val BIO_CONTEXT = "offgrid-biometric-v1".toByteArray()
    }
}
