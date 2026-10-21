package com.example.bluetooth_messenger.domain.time

interface AppClock {
    fun elapsedMillis(): Long
    fun wallMillis(): Long
}
