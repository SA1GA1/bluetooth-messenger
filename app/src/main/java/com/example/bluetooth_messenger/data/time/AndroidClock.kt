package com.example.bluetooth_messenger.data.time

import android.os.SystemClock
import com.example.bluetooth_messenger.domain.time.AppClock
import javax.inject.Inject

class AndroidClock @Inject constructor() : AppClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    override fun wallMillis(): Long = System.currentTimeMillis()
}
