package com.golfcart.gcd.di;

import android.content.Context;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

/**
 * Hilt module providing location-related dependencies.
 * <p>
 * Provides: FusedLocationProviderClient, LocationRequest configuration.
 * <p>
 * Requirements: 5.1, 16.13
 */
@Module
@InstallIn(SingletonComponent.class)
public class LocationModule {

    /**
     * Provides the FusedLocationProviderClient for GPS location updates.
     * <p>
     * Requirement 5.1: Obtain GPS data from the Android device's internal GPS sensor.
     */
    @Provides
    @Singleton
    public FusedLocationProviderClient provideFusedLocationProviderClient(
            @ApplicationContext Context context) {
        return LocationServices.getFusedLocationProviderClient(context);
    }

    /**
     * Provides the default LocationRequest for GPS updates.
     * <p>
     * Uses high accuracy priority with an 8-second interval (matching the
     * Meshtastic GPS update interval when away from home).
     * <p>
     * Requirement 5.1, 11.6: GPS interval adjustable based on at_home status.
     */
    @Provides
    @Singleton
    public LocationRequest provideLocationRequest() {
        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 8000L)
                .setMinUpdateIntervalMillis(4000L)
                .setMaxUpdateDelayMillis(16000L)
                .build();
    }
}
