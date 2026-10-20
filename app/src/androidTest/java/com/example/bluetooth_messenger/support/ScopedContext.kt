package com.example.bluetooth_messenger.support

import android.content.Context
import android.content.ContextWrapper
import java.util.UUID

class ScopedContext(context: Context) : ContextWrapper(context) {
    private val prefix = "test-${UUID.randomUUID()}-"
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    override fun getDatabasePath(name: String) = super.getDatabasePath(prefix + name)
    override fun deleteDatabase(name: String) = super.deleteDatabase(prefix + name)
}
