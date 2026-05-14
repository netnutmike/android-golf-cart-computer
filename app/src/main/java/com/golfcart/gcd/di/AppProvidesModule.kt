package com.golfcart.gcd.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.golfcart.gcd.data.bluetooth.MeshtasticMessageHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Hilt module providing concrete instances that require Kotlin-specific construction.
 *
 * Provides:
 * - Application-scoped [CoroutineScope] for domain managers
 * - Jetpack [DataStore] for persistent preferences
 * - [MeshtasticMessageHandler] for message routing
 *
 * Requirements: 15.1, 15.2, 16.13
 */
@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {

    /** DataStore delegate for preferences storage. */
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gcd_preferences")

    /**
     * Provides the application-scoped [CoroutineScope] for domain managers.
     *
     * Uses a [SupervisorJob] so that failure of one child coroutine does not
     * cancel siblings, and [Dispatchers.Default] for CPU-bound work.
     * Domain managers that need to launch coroutines inject this scope.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * Provides the Jetpack DataStore instance for persistent preferences storage.
     *
     * The DataStore is created using the application context with the name "gcd_preferences".
     * This is a singleton — only one DataStore instance should exist per file.
     *
     * Requirements: 15.1, 15.2
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    /**
     * Provides the [MeshtasticMessageHandler] singleton.
     *
     * The message handler is shared between the [MeshtasticService] (which routes
     * incoming packets to it) and consumers like [DataSyncManager] and [MessagingViewModel]
     * (which observe its SharedFlows).
     *
     * The localNodeNum lambda retrieves the node number from the service manager's
     * bound MeshtasticService instance once available.
     *
     * Requirements: 2.3, 2.4, 2.7, 2.9, 18.6, 18.7
     */
    @Provides
    @Singleton
    fun provideMeshtasticMessageHandler(
        serviceManager: BluetoothServiceManager
    ): MeshtasticMessageHandler {
        return MeshtasticMessageHandler(
            localNodeNum = {
                serviceManager.getMeshtasticService()?.getLocalNodeNum() ?: 0L
            }
        )
    }
}
