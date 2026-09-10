package com.example.bluetooth_messenger.domain.repository

import kotlinx.coroutines.flow.Flow

interface OnboardingRepository {
    val isOnboardingCompletedFlow: Flow<Boolean>

    suspend fun saveOnboardingStatus(isComplete: Boolean)
}