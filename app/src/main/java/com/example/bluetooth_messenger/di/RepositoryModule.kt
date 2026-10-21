package com.example.bluetooth_messenger.di

import com.example.bluetooth_messenger.data.security.KeystoreIdentityRepository
import com.example.bluetooth_messenger.domain.repository.IdentityKeyRepository
import com.example.bluetooth_messenger.data.transport.AndroidBleRadio
import com.example.bluetooth_messenger.domain.transport.DiscoveryService
import com.example.bluetooth_messenger.domain.transport.PeerTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun discovery(implementation: AndroidBleRadio): DiscoveryService
    @Binds abstract fun transport(implementation: AndroidBleRadio): PeerTransport
    @Binds abstract fun identityRepository(implementation: KeystoreIdentityRepository): IdentityKeyRepository
}
