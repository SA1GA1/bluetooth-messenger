package com.example.bluetooth_messenger.data.repository

import com.example.bluetooth_messenger.data.local.preferences.AppPreferencesDataSource
import com.example.bluetooth_messenger.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.Flow

class OnboardingRepositoryImpl (
    private val dataSource: AppPreferencesDataSource
): OnboardingRepository {

    override val isOnboardingCompletedFlow: Flow<Boolean> = dataSource.isOnboardingCompletedFlow

    override suspend fun saveOnboardingStatus(isComplete: Boolean) {
        dataSource.setOnboardingCompleted(isComplete)
    }
}