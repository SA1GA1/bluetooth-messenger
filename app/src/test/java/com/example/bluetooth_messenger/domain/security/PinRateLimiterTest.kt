package com.example.bluetooth_messenger.domain.security

import org.junit.Assert.*
import org.junit.Test

class PinRateLimiterTest {
    private val limiter = PinRateLimiter()
    @Test fun fifthFailureStartsThirtySecondCooldown() {
        var state = PinAttempts(boot = 1)
        repeat(4) { state = limiter.failed(state, 1000, 1); assertEquals(0L, limiter.remaining(state, 1000)) }
        state = limiter.failed(state, 1000, 1)
        assertEquals(30_000L, limiter.remaining(state, 1000))
        assertEquals(0L, limiter.remaining(state, 31_000))
    }
    @Test fun repeatedFailuresCapAtFiveMinutes() {
        var state = PinAttempts(boot = 1)
        repeat(20) { state = limiter.failed(state, 0, 1) }
        assertEquals(300_000L, limiter.remaining(state, 0))
    }
    @Test fun rebootRestoresCooldownInsteadOfBypassingIt() {
        val restored = limiter.normalize(PinAttempts(5, 999_000, 1), 100, 2)
        assertEquals(30_000L, limiter.remaining(restored, 100))
        assertEquals(2, restored.boot)
        assertEquals(restored, limiter.normalize(restored, 200, 2))
    }
}
