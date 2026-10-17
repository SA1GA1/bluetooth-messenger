package com.example.bluetooth_messenger.domain.session

enum class UnlockMethod { Pin, Biometric }

class SessionPolicy {
    var unlocked: Boolean = false
        private set
    var requiredMethod: UnlockMethod = UnlockMethod.Pin
        private set
    private var lastInteractionMillis = 0L
    @Volatile private var generation = 0L
    @Volatile private var inForeground = true
    @Synchronized fun beginUnlock(): Long = ++generation
    fun canCompleteUnlock(token: Long): Boolean = inForeground && token == generation && !unlocked
    fun foreground() { inForeground = true }


    fun unlock() {
        unlocked = true
    }

    @Synchronized fun background(biometricsEnabled: Boolean) {
        inForeground = false
        generation++
        if (!unlocked) return
        unlocked = false
        requiredMethod = if (biometricsEnabled) UnlockMethod.Biometric else UnlockMethod.Pin
    }

    @Synchronized fun biometricFailed() {
        generation++
        unlocked = false
        requiredMethod = UnlockMethod.Pin
    }

    fun checkIdle(nowMillis: Long, timeoutMinutes: Int) {
        require(timeoutMinutes in setOf(1, 3, 5, 15))
        if (unlocked && nowMillis - lastInteractionMillis >= timeoutMinutes * 60_000L) {
            unlocked = false
            requiredMethod = UnlockMethod.Pin
        }
    }

    fun interacted(nowMillis: Long) {
        lastInteractionMillis = nowMillis
    }
}
