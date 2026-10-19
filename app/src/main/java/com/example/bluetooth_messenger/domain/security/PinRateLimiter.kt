package com.example.bluetooth_messenger.domain.security

data class PinAttempts(val failures: Int = 0, val blockedUntil: Long = 0, val boot: Int = 0)

class PinRateLimiter {
    fun normalize(state: PinAttempts, now: Long, boot: Int): PinAttempts =
        if (state.boot == boot) state else state.copy(blockedUntil = now + cooldown(state.failures), boot = boot)

    fun failed(state: PinAttempts, now: Long, boot: Int): PinAttempts {
        val failures = state.failures.coerceIn(0, 29) + 1
        return PinAttempts(failures, now + cooldown(failures), boot)
    }

    fun remaining(state: PinAttempts, now: Long): Long = (state.blockedUntil - now).coerceAtLeast(0)

    private fun cooldown(failures: Int): Long =
        if (failures < 5) 0 else minOf(300_000L, 30_000L * (1L shl (failures - 5).coerceAtMost(4)))
}
