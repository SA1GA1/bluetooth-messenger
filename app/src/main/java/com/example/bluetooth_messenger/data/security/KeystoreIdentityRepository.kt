package com.example.bluetooth_messenger.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.example.bluetooth_messenger.domain.model.PeerIdentity
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.domain.repository.IdentityKeyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class KeystoreIdentityRepository
@Inject
constructor(@ApplicationContext private val context: Context) :
    IdentityKeyRepository, IdentityCrypto {
    private val mutex = Mutex()
    private val preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val crypto = MessageCrypto()

    override suspend fun hasIdentityKeys(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                identity()
                true
            } catch (_: java.security.GeneralSecurityException) {
                false
            } catch (_: IllegalStateException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }

    override suspend fun generateIdentityKeys() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val store = store()
                if (preferences.contains("user_id")) {
                    identity()
                    return@withLock
                }
                check(!store.containsAlias(SIGNING) && !store.containsAlias(AGREEMENT)) {
                    "Обнаружены ключи без идентичности. Требуется явный сброс приложения."
                }
                try {
                    generate(SIGNING, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    generate(AGREEMENT, KeyProperties.PURPOSE_AGREE_KEY)
                    validate(store(), SIGNING)
                    validate(store(), AGREEMENT)
                    check(
                        preferences
                            .edit()
                            .putString("user_id", UUID.randomUUID().toString())
                            .commit()
                    )
                } catch (failure: Exception) {
                    store().deleteEntry(SIGNING)
                    store().deleteEntry(AGREEMENT)
                    throw failure
                }
            }
        }

    override suspend fun identity(): PeerIdentity =
        withContext(Dispatchers.IO) {
            val userId =
                checkNotNull(preferences.getString("user_id", null)) { "Идентичность не создана" }
            val store = store()
            validate(store, SIGNING)
            validate(store, AGREEMENT)
            PeerIdentity(
                userId,
                store.getCertificate(SIGNING).publicKey.encoded,
                store.getCertificate(AGREEMENT).publicKey.encoded,
            )
        }

    override suspend fun sign(bytes: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            crypto.sign(bytes, privateKey(SIGNING))
        }

    override suspend fun decrypt(bytes: ByteArray, associatedData: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            crypto.decrypt(bytes, privateKey(AGREEMENT), associatedData)
        }

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun privateKey(alias: String) =
        store().getKey(alias, null) as? PrivateKey
            ?: error("Приватный ключ недоступен: требуется явный сброс")

    private fun validate(store: KeyStore, alias: String) {
        val key = store.getKey(alias, null) as? PrivateKey ?: error("Ключ идентичности утрачен")
        val info =
            KeyFactory.getInstance("EC", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        check(
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        ) {
            "Устройство не поддерживает аппаратную защиту P-256"
        }
        val purposes =
            if (alias == SIGNING) KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            else KeyProperties.PURPOSE_AGREE_KEY
        check(info.purposes and purposes == purposes && key.encoded == null)
        crypto.decodeKey(store.getCertificate(alias).publicKey.encoded)
    }

    private fun generate(alias: String, purposes: Int) {
        fun generateWith(strongBox: Boolean) {
            val builder =
                KeyGenParameterSpec.Builder(alias, purposes)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setIsStrongBoxBacked(strongBox)
            if (purposes and KeyProperties.PURPOSE_SIGN != 0)
                builder.setDigests(KeyProperties.DIGEST_SHA256)
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
                .apply {
                    initialize(builder.build())
                }
                .generateKeyPair()
        }
        try {
            generateWith(true)
        } catch (_: java.security.GeneralSecurityException) {
            generateWith(false)
        } catch (_: android.security.keystore.StrongBoxUnavailableException) {
            generateWith(false)
        }
    }

    private companion object {
        const val SIGNING = "identity_signing_key"
        const val AGREEMENT = "identity_agreement_key"
    }
}
