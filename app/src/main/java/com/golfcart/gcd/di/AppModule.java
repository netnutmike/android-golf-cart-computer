package com.golfcart.gcd.di;

import com.golfcart.gcd.data.persistence.DataStoreRepository;
import com.golfcart.gcd.data.persistence.DataStoreRepositoryImpl;
import com.golfcart.gcd.domain.audio.AudioManager;
import com.golfcart.gcd.domain.audio.AudioManagerImpl;
import com.golfcart.gcd.domain.brightness.BacklightManager;
import com.golfcart.gcd.domain.brightness.BacklightManagerImpl;
import com.golfcart.gcd.domain.geofence.GeofenceManager;
import com.golfcart.gcd.domain.geofence.GeofenceManagerImpl;
import com.golfcart.gcd.domain.gps.GpsProcessor;
import com.golfcart.gcd.domain.gps.GpsProcessorImpl;
import com.golfcart.gcd.domain.odometer.OdometerManager;
import com.golfcart.gcd.domain.odometer.OdometerManagerImpl;
import com.golfcart.gcd.domain.parser.HotPacketParser;
import com.golfcart.gcd.domain.parser.HotPacketParserImpl;
import com.golfcart.gcd.domain.service.ServiceReminderManager;
import com.golfcart.gcd.domain.service.ServiceReminderManagerImpl;
import com.golfcart.gcd.domain.sleep.SleepManager;
import com.golfcart.gcd.domain.sleep.SleepManagerImpl;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/**
 * Hilt module providing application-level interface bindings.
 * <p>
 * Binds all domain manager implementations to their interfaces as singletons.
 * Concrete @Provides methods (DataStore, CoroutineScope) are in {@link AppProvidesModule}.
 * <p>
 * Requirements: 16.13
 */
@Module
@InstallIn(SingletonComponent.class)
public abstract class AppModule {

    /**
     * Binds the {@link AudioManagerImpl} implementation to the {@link AudioManager} interface.
     * Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8
     */
    @Binds
    @Singleton
    abstract AudioManager bindAudioManager(AudioManagerImpl impl);

    /**
     * Binds the {@link DataStoreRepositoryImpl} to the {@link DataStoreRepository} interface.
     * Requirements: 15.1, 15.2, 15.3, 15.4
     */
    @Binds
    @Singleton
    abstract DataStoreRepository bindDataStoreRepository(DataStoreRepositoryImpl impl);

    /**
     * Binds the {@link GpsProcessorImpl} to the {@link GpsProcessor} interface.
     * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7
     */
    @Binds
    @Singleton
    abstract GpsProcessor bindGpsProcessor(GpsProcessorImpl impl);

    /**
     * Binds the {@link OdometerManagerImpl} to the {@link OdometerManager} interface.
     * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10
     */
    @Binds
    @Singleton
    abstract OdometerManager bindOdometerManager(OdometerManagerImpl impl);

    /**
     * Binds the {@link ServiceReminderManagerImpl} to the {@link ServiceReminderManager} interface.
     * Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9
     */
    @Binds
    @Singleton
    abstract ServiceReminderManager bindServiceReminderManager(ServiceReminderManagerImpl impl);

    /**
     * Binds the {@link GeofenceManagerImpl} to the {@link GeofenceManager} interface.
     * Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8
     */
    @Binds
    @Singleton
    abstract GeofenceManager bindGeofenceManager(GeofenceManagerImpl impl);

    /**
     * Binds the {@link BacklightManagerImpl} to the {@link BacklightManager} interface.
     * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7
     */
    @Binds
    @Singleton
    abstract BacklightManager bindBacklightManager(BacklightManagerImpl impl);

    /**
     * Binds the {@link SleepManagerImpl} to the {@link SleepManager} interface.
     * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
     */
    @Binds
    @Singleton
    abstract SleepManager bindSleepManager(SleepManagerImpl impl);

    /**
     * Binds the {@link HotPacketParserImpl} to the {@link HotPacketParser} interface.
     * Requirements: 3.1, 3.2, 3.3, 3.9, 3.10, 3.11, 3.12, 4.1, 4.2, 4.4
     */
    @Binds
    @Singleton
    abstract HotPacketParser bindHotPacketParser(HotPacketParserImpl impl);
}
