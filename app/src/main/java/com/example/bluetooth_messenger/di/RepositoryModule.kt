package com.example.bluetooth_messenger.di

import com.example.bluetooth_messenger.data.security.KeystoreIdentityRepository
import com.example.bluetooth_messenger.domain.repository.IdentityKeyRepository
import com.example.bluetooth_messenger.domain.repository.IdentityCrypto
import com.example.bluetooth_messenger.data.transport.AndroidBleRadio
import com.example.bluetooth_messenger.domain.transport.DiscoveryService
import com.example.bluetooth_messenger.domain.transport.PeerTransport
import com.example.bluetooth_messenger.core.security.BiometricGateway
import com.example.bluetooth_messenger.data.session.AndroidSessionController
import com.example.bluetooth_messenger.data.repository.SettingsRepositoryImpl
import com.example.bluetooth_messenger.domain.repository.SettingsRepository
import com.example.bluetooth_messenger.domain.session.SessionController
import com.example.bluetooth_messenger.domain.time.AppClock
import com.example.bluetooth_messenger.data.time.AndroidClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun fileLink(implementation: com.example.bluetooth_messenger.data.transport.AndroidWifiDirect): com.example.bluetooth_messenger.domain.transport.FileLink
    @Binds abstract fun messenger(implementation: com.example.bluetooth_messenger.data.repository.DefaultMessengerRepository): com.example.bluetooth_messenger.domain.repository.MessengerRepository
    @Binds abstract fun pages(implementation: com.example.bluetooth_messenger.data.repository.RoomMessagePages): com.example.bluetooth_messenger.core.paging.MessagePages
    @Binds abstract fun clock(implementation: AndroidClock): AppClock
    @Binds abstract fun identityCrypto(implementation: KeystoreIdentityRepository): IdentityCrypto
    @Binds abstract fun session(implementation: AndroidSessionController): SessionController
    @Binds abstract fun biometrics(implementation: AndroidSessionController): BiometricGateway
    @Binds abstract fun settings(implementation: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun discovery(implementation: AndroidBleRadio): DiscoveryService
    @Binds abstract fun transport(implementation: AndroidBleRadio): PeerTransport
    @Binds abstract fun identityRepository(implementation: KeystoreIdentityRepository): IdentityKeyRepository
}
