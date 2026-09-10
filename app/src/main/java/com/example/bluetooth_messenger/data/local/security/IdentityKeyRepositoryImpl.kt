package com.example.bluetooth_messenger.data.local.security

import com.example.bluetooth_messenger.domain.repository.IdentityKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec

private const val IDENTITY_KEY_ALIAS = "identity_master_key"
private const val KEY_STORE_PROVIDER = "AndroidKeyStore"

class IdentityKeyRepositoryImpl: IdentityKeyRepository {

    override suspend fun hasIdentityKeys(): Boolean {
        return withContext(Dispatchers.IO) {
            try {

                // инициализация хранилища и получение экземпляра
                val keyStore: KeyStore = KeyStore.getInstance(KEY_STORE_PROVIDER).apply {
                    load(null)
                }

                val privateKey = keyStore.getKey(IDENTITY_KEY_ALIAS, null) as? PrivateKey
                val certificate = keyStore.getCertificate(IDENTITY_KEY_ALIAS)
                val publicKey = certificate?.publicKey as? ECPublicKey

                if (privateKey == null) return@withContext false
                if (publicKey == null) return@withContext false

                if (publicKey.algorithm != "EC" || privateKey.algorithm != "EC") return@withContext false

                val isP256 = checkIsP256(publicKey.params)

                return@withContext isP256
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun checkIsP256(params: ECParameterSpec?): Boolean {
        if (params == null) return false

        return try {
            // параметры кривой P-256 от системы
            val algorithmParameters = java.security.AlgorithmParameters.getInstance("EC").apply {
                init(java.security.spec.ECGenParameterSpec("secp256r1"))
            }

            val referenceP256Spec = algorithmParameters.getParameterSpec(ECParameterSpec::class.java)

            // сравниваем математические параметры
            // проверка (поля, коэффициента a и b), базовой точки и порядка группы
            params.curve == referenceP256Spec.curve &&
                    params.generator == referenceP256Spec.generator &&
                    params.order == referenceP256Spec.order &&
                    params.cofactor == referenceP256Spec.cofactor
        }
        catch (e: Exception) {
            false
        }
    }
}