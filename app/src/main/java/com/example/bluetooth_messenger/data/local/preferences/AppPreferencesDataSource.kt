package com.example.bluetooth_messenger.data.local.preferences

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppPreferencesDataSource(private val context: Context) {

    private val application = context.applicationContext as Application
    val isOnboardingCompletedFlow: Flow<Boolean> = application.dataStore.data.map { preferences ->
        preferences[IS_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(isComplete: Boolean) {
        application.dataStore.edit { preferences ->
            preferences[IS_ONBOARDING_COMPLETED] = isComplete
        }
    }
}
