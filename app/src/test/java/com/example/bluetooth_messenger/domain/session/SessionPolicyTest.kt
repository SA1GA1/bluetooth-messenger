package com.example.bluetooth_messenger.domain.session

import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    @Test fun coldStartRequiresPin() {
        val policy = SessionPolicy()
        assertFalse(policy.unlocked)
        assertEquals(UnlockMethod.Pin, policy.requiredMethod)
    }

    @Test fun backgroundLocksImmediatelyAndAllowsEnabledBiometricsAfterPinUnlock() {
        val policy = SessionPolicy()
        policy.unlock()
        policy.background(biometricsEnabled = true)
        assertFalse(policy.unlocked)
        assertEquals(UnlockMethod.Biometric, policy.requiredMethod)
    }

    @Test fun backgroundBeforeFirstUnlockCannotEnableBiometrics() {
        val policy = SessionPolicy()
        policy.background(biometricsEnabled = true)
        assertEquals(UnlockMethod.Pin, policy.requiredMethod)
    }

    @Test fun biometricFailureRequiresPinUntilNextSuccessfulUnlock() {
        val policy = SessionPolicy()
        policy.unlock()
        policy.background(true)
        policy.biometricFailed()
        policy.background(true)
        assertEquals(UnlockMethod.Pin, policy.requiredMethod)
    }

    @Test fun disabledBiometricsAlwaysRequiresPin() {
        val policy = SessionPolicy()
        policy.unlock()
        policy.background(false)
        assertEquals(UnlockMethod.Pin, policy.requiredMethod)
    }

    @Test fun idleTimeoutUsesLastInteractionAndLocksAtBoundary() {
        val policy = SessionPolicy()
        policy.unlock()
        policy.interacted(10_000)
        policy.checkIdle(309_999, 5)
        assertTrue(policy.unlocked)
        policy.checkIdle(310_000, 5)
        assertFalse(policy.unlocked)
    }

    @Test fun delayedAuthenticationCannotUnlockAfterBackgroundAndReturn() {
        val policy = SessionPolicy()
        val token = policy.beginUnlock()
        policy.background(false)
        assertFalse(policy.canCompleteUnlock(token))
        policy.foreground()
        assertFalse(policy.canCompleteUnlock(token))
        assertTrue(policy.canCompleteUnlock(policy.beginUnlock()))
    }

    @Test fun unsupportedTimeoutIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionPolicy().checkIdle(0, 2)
        }
    }
}
