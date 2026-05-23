package com.damarquez.putz.di

import com.damarquez.putz.data.transport.DaemonTransport
import com.damarquez.putz.data.transport.SmartDaemonTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds
    @Singleton
    abstract fun bindDaemonTransport(smart: SmartDaemonTransport): DaemonTransport
}
