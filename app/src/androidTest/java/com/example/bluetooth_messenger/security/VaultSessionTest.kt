package com.example.bluetooth_messenger.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bluetooth_messenger.data.local.database.DatabaseSession
import com.example.bluetooth_messenger.data.security.PinVault
import com.example.bluetooth_messenger.support.ScopedContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultSessionTest {
    private val context = ScopedContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val vault = PinVault(context)
    private val database = DatabaseSession(context)

    @After fun cleanup() = runBlocking {
        vault.lock()
        database.close()
        context.getSharedPreferences("vault", 0).edit().clear().commit()
        context.deleteDatabase("offgrid.db")
        Unit
    }
    @Test fun pinUnlockSurvivesRepositoryRecreationAndLockClearsSession() {
        val pin = "123456".toCharArray()
        val expected = vault.setup(pin)
        assertTrue(pin.all { it == '\u0000' })
        vault.lock()
        assertThrows(IllegalStateException::class.java) { vault.keyCopy() }
        assertThrows(IllegalArgumentException::class.java) { vault.openWithPin("654321".toCharArray()) }
        val restored = PinVault(context)
        assertArrayEquals(expected, restored.openWithPin("123456".toCharArray()))
        restored.lock()
        expected.fill(0)
    }
    @Test fun failedAttemptsRemainBlockedAfterRecreation() {
        vault.setup("123456".toCharArray()).fill(0)
        vault.lock()
        repeat(5) {
            assertThrows(IllegalArgumentException::class.java) { vault.openWithPin("654321".toCharArray()) }
        }
        assertTrue(PinVault(context).remainingCooldown() > 0)
        assertThrows(IllegalStateException::class.java) { vault.openWithPin("123456".toCharArray()) }
    }
    @Test fun databaseSessionRejectsAccessAfterCloseAndReopensWithSameSecret() = runBlocking<Unit> {
        val key = vault.setup("123456".toCharArray())
        database.open(key)
        assertTrue(database.use { it.isOpen })
        database.close()
        assertThrows(IllegalStateException::class.java) { runBlocking { database.use { it.isOpen } } }
        database.open(key)
        assertTrue(database.use { it.isOpen })
        key.fill(0)
    }
    @Test fun biometricEnrollmentCannotStartWhileLocked() {
        vault.setup("123456".toCharArray()).fill(0)
        vault.lock()
        assertThrows(IllegalStateException::class.java) { vault.prepareBiometricEnrollment() }
    }
}
