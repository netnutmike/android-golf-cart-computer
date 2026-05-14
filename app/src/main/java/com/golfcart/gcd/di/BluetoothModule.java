package com.golfcart.gcd.di;

import com.golfcart.gcd.data.bluetooth.MeshtasticConnection;
import com.golfcart.gcd.data.bluetooth.TelemetryConnection;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/**
 * Hilt module providing Bluetooth-related dependency bindings.
 * <p>
 * Binds the delegate implementations of {@link MeshtasticConnection} and
 * {@link TelemetryConnection} to their interfaces. The delegates forward calls
 * to the actual foreground service instances once they are bound.
 * <p>
 * Requirements: 16.13, 17.1, 17.2
 */
@Module
@InstallIn(SingletonComponent.class)
public abstract class BluetoothModule {

    /**
     * Binds the {@link MeshtasticConnectionDelegate} to the {@link MeshtasticConnection} interface.
     * <p>
     * The delegate awaits the foreground service binding and forwards all calls
     * to the actual {@link com.golfcart.gcd.data.bluetooth.MeshtasticService} instance.
     * <p>
     * Requirements: 1.1, 1.8, 17.1
     */
    @Binds
    @Singleton
    abstract MeshtasticConnection bindMeshtasticConnection(MeshtasticConnectionDelegate delegate);

    /**
     * Binds the {@link TelemetryConnectionDelegate} to the {@link TelemetryConnection} interface.
     * <p>
     * The delegate awaits the foreground service binding and forwards all calls
     * to the actual {@link com.golfcart.gcd.data.bluetooth.TelemetryService} instance.
     * <p>
     * Requirements: 8.1, 17.1
     */
    @Binds
    @Singleton
    abstract TelemetryConnection bindTelemetryConnection(TelemetryConnectionDelegate delegate);
}
