package com.example.bluetooth_messenger.data.security

import com.example.bluetooth_messenger.domain.model.Limits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MessageCrypto {
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, recipient: PublicKey, associatedData: ByteArray): ByteArray {
        require(plaintext.size <= Limits.PACKET_BYTES - 256)
        validateKey(recipient)
        val ephemeral = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val salt = ByteArray(32).also(random::nextBytes)
        val secret = agree(ephemeral.private, recipient)
        val key = hkdf(secret, salt, "offgrid-message-v1".toByteArray())
        secret.fill(0)
        val sealed = try { seal(plaintext, key, associatedData) } finally { key.fill(0) }
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeByte(1)
                it.writeShort(ephemeral.public.encoded.size)
                it.write(ephemeral.public.encoded)
                it.write(salt)
                it.write(sealed)
            }
        }.toByteArray()
    }

    fun decrypt(packet: ByteArray, recipient: PrivateKey, associatedData: ByteArray): ByteArray {
        require(packet.size in 64..Limits.PACKET_BYTES)
        val input = DataInputStream(ByteArrayInputStream(packet))
        require(input.readUnsignedByte() == 1)
        val publicLength = input.readUnsignedShort()
        require(publicLength in 80..128 && input.available() >= publicLength + 32 + 28)
        val publicBytes = ByteArray(publicLength).also(input::readFully)
        val ephemeral = decodeKey(publicBytes)
        val salt = ByteArray(32).also(input::readFully)
        val sealed = ByteArray(input.available()).also(input::readFully)
        val secret = agree(recipient, ephemeral)
        val key = hkdf(secret, salt, "offgrid-message-v1".toByteArray())
        secret.fill(0)
        return try { open(sealed, key, associatedData) } finally { key.fill(0) }
    }

    fun sign(bytes: ByteArray, key: PrivateKey): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update(bytes)
        sign()
    }

    fun verify(bytes: ByteArray, signature: ByteArray, key: PublicKey): Boolean = try {
        validateKey(key)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(bytes)
            verify(signature)
        }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    fun seal(plaintext: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray {
        require(key.size == 32)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return iv + cipher.doFinal(plaintext)
    }

    fun open(sealed: ByteArray, key: ByteArray, associatedData: ByteArray): ByteArray {
        require(key.size == 32 && sealed.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    fun decodeKey(encoded: ByteArray): PublicKey {
        require(encoded.size in 80..128)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)).also(::validateKey)
    }

    private fun validateKey(key: PublicKey) {
        require(key is ECPublicKey)
        val expected = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val actual = key.params
        require(actual.curve == expected.curve && actual.generator == expected.generator &&
            actual.order == expected.order && actual.cofactor == expected.cofactor)
        val point = key.w
        val field = actual.curve.field as java.security.spec.ECFieldFp
        require(point.affineX.signum() >= 0 && point.affineX < field.p &&
            point.affineY.signum() >= 0 && point.affineY < field.p)
        require(point.affineY.modPow(java.math.BigInteger.valueOf(2), field.p) ==
            point.affineX.modPow(java.math.BigInteger.valueOf(3), field.p)
                .add(actual.curve.a.multiply(point.affineX)).add(actual.curve.b).mod(field.p))
    }

    private fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(privateKey)
        doPhase(publicKey, true)
        generateSecret()
    }

    internal fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val extracted = mac.doFinal(secret)
        return try {
            mac.init(SecretKeySpec(extracted, "HmacSHA256"))
            mac.doFinal(info + byteArrayOf(1))
        } finally { extracted.fill(0) }
    }
}
