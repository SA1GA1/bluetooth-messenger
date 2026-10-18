package com.example.bluetooth_messenger.domain.session

enum class UnlockMethod { Pin, Biometric }

class SessionPolicy {
    var unlocked: Boolean = false
        private set
    var requiredMethod: UnlockMethod = UnlockMethod.Pin
        private set
    private var lastInteractionMillis = 0L

    fun unlock() {
        unlocked = true
    }

    fun background(biometricsEnabled: Boolean) {
        if (!unlocked) return
        unlocked = false
        requiredMethod = if (biometricsEnabled) UnlockMethod.Biometric else UnlockMethod.Pin
    }

    fun biometricFailed() {
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
