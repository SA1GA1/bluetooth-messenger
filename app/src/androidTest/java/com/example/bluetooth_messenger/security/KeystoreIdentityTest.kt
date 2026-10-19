package com.example.bluetooth_messenger.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.data.security.KeystoreIdentityRepository
import com.example.bluetooth_messenger.data.security.MessageCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreIdentityTest {
    @Test fun hardwareIdentitySurvivesReinitializationAndDecryptsForItsOwnPublicKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = KeystoreIdentityRepository(context)
        repository.generateIdentityKeys()
        val identity = repository.identity()
        repository.generateIdentityKeys()
        val reloaded = KeystoreIdentityRepository(context).identity()
        assertEquals(identity.userId, reloaded.userId)
        assertArrayEquals(identity.signingKey, reloaded.signingKey)
        assertArrayEquals(identity.agreementKey, reloaded.agreementKey)
        val crypto = MessageCrypto()
        val aad = "offgrid-device-check".toByteArray()
        val ciphertext = crypto.encrypt("private".toByteArray(), crypto.decodeKey(identity.agreementKey), aad)
        assertArrayEquals("private".toByteArray(), repository.decrypt(ciphertext, aad))
        assertTrue(crypto.verify(aad, repository.sign(aad), crypto.decodeKey(identity.signingKey)))
    }
}
