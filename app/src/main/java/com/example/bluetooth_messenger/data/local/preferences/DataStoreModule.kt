package com.example.bluetooth_messenger.data.local.preferences

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Application.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

val IS_ONBOARDING_COMPLETED = booleanPreferencesKey("isOnboardingCompleted")