package com.example.bluetooth_messenger.data.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.example.bluetooth_messenger.data.local.security.CryptoConstants.AGREEMENT_KEY_ALIAS
import com.example.bluetooth_messenger.data.local.security.CryptoConstants.EC_ALGORITHM
import com.example.bluetooth_messenger.data.local.security.CryptoConstants.EC_CURVE_P256
import com.example.bluetooth_messenger.data.local.security.CryptoConstants.KEY_STORE_PROVIDER
import com.example.bluetooth_messenger.data.local.security.CryptoConstants.SIGNING_KEY_ALIAS
import com.example.bluetooth_messenger.domain.repository.IdentityKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

class IdentityKeyRepositoryImpl : IdentityKeyRepository {

    override suspend fun hasIdentityKeys(): Boolean {
        return withContext(Dispatchers.IO) {
            try {

                // инициализация хранилища и получение экземпляра
                val keyStore: KeyStore = KeyStore.getInstance(KEY_STORE_PROVIDER).apply {
                    load(null)
                }

                val expectedSigningPurposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                val isSigningKeyValid = checkValidKey(keyStore, SIGNING_KEY_ALIAS, expectedSigningPurposes)
                if (!isSigningKeyValid) return@withContext false

                val expectedAgreementPurposes = KeyProperties.PURPOSE_AGREE_KEY
                val isAgreementKeyValid = checkValidKey(keyStore, AGREEMENT_KEY_ALIAS, expectedAgreementPurposes)

                return@withContext isAgreementKeyValid

            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun generateIdentityKeys() = withContext(Dispatchers.IO) {

        val keyStore: KeyStore = KeyStore.getInstance(KEY_STORE_PROVIDER).apply {
            load(null)
        }

        if (hasIdentityKeys()) {
            return@withContext
        }

        try {

            if (keyStore.containsAlias(SIGNING_KEY_ALIAS)) keyStore.deleteEntry(SIGNING_KEY_ALIAS)
            if (keyStore.containsAlias(AGREEMENT_KEY_ALIAS)) keyStore.deleteEntry(AGREEMENT_KEY_ALIAS)

            generateEcP256KeyPair(
                SIGNING_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                true
            )

            generateEcP256KeyPair(
                AGREEMENT_KEY_ALIAS,
                KeyProperties.PURPOSE_AGREE_KEY,
                false
            )

            if (!hasIdentityKeys()) {
                throw IllegalStateException("generateIdentityKeys error")
            }

        } catch (e: Exception) {

            try {

                if (keyStore.containsAlias(SIGNING_KEY_ALIAS)) keyStore.deleteEntry(SIGNING_KEY_ALIAS)
                if (keyStore.containsAlias(AGREEMENT_KEY_ALIAS)) keyStore.deleteEntry(AGREEMENT_KEY_ALIAS)

            } catch (_: Exception) {
                throw e
            }

            throw e
        }
    }

    private fun generateEcP256KeyPair(alias: String, purposes: Int, includeDigests: Boolean) {

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEY_STORE_PROVIDER
        )

        val builder = KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE_P256))

        if (includeDigests) {
            builder.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
        }

        keyPairGenerator.initialize(builder.build())
        keyPairGenerator.generateKeyPair()
    }

    private fun checkValidKey(keyStore: KeyStore, alias: String, expectedPurposes: Int): Boolean {
        try {
            if(!keyStore.containsAlias(alias)) return false

            val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            val certificate = keyStore.getCertificate(alias)
            val publicKey = certificate?.publicKey as? ECPublicKey

            if (privateKey == null || publicKey == null) return false
            if (publicKey.algorithm != EC_ALGORITHM || privateKey.algorithm != EC_ALGORITHM) return false

            val factory = KeyFactory.getInstance(privateKey.algorithm, KEY_STORE_PROVIDER)
            val keyInfo: KeyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)

            if ((keyInfo.purposes and expectedPurposes) != expectedPurposes) return false

            return checkIsP256(publicKey.params)

        } catch (_: Exception) {
            return false
        }
    }

    private fun checkIsP256(params: ECParameterSpec?): Boolean {
        if (params == null) return false

        return try {
            // параметры кривой P-256 от системы
            val algorithmParameters = java.security.AlgorithmParameters.getInstance(EC_ALGORITHM).apply {
                init(java.security.spec.ECGenParameterSpec(EC_CURVE_P256))
            }

            val referenceP256Spec = algorithmParameters.getParameterSpec(ECParameterSpec::class.java)

            // сравниваем математические параметры
            // проверка (поля, коэффициента a и b), базовой точки и порядка группы
            params.curve == referenceP256Spec.curve &&
                    params.generator == referenceP256Spec.generator &&
                    params.order == referenceP256Spec.order &&
                    params.cofactor == referenceP256Spec.cofactor
        }
        catch (_: Exception) {
            false
        }
    }
}
