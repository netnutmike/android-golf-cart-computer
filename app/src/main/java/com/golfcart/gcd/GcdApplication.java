package com.golfcart.gcd;

import android.app.Application;
import android.util.Log;

import com.golfcart.gcd.di.BluetoothServiceManager;
import com.golfcart.gcd.di.GciStatusNotifier;
import com.golfcart.gcd.di.MeshtasticConnectionDelegate;
import com.golfcart.gcd.di.TelemetryConnectionDelegate;
import com.golfcart.gcd.domain.audio.AudioManager;
import com.golfcart.gcd.domain.audio.AudioVolumeInitializer;
import com.golfcart.gcd.domain.sync.DataSyncManager;

import javax.inject.Inject;

import dagger.hilt.android.HiltAndroidApp;
import kotlinx.coroutines.CoroutineScope;

/**
 * Main Application class for the Golf Cart Display Computer.
 * <p>
 * Annotated with @HiltAndroidApp to trigger Hilt's code generation
 * and serve as the application-level dependency container.
 * <p>
 * Responsibilities:
 * - Starts Bluetooth foreground services on app launch
 * - Initializes connection delegates for DI
 * - Starts the DataSyncManager for weather/venue data flow
 * - Starts the GciStatusNotifier for at_home/is_daytime notifications
 * - Plays the startup tone on application launch (Requirement 14.1)
 * - Initializes the audio volume from persisted preferences (Requirement 14.8)
 * <p>
 * Requirements: 16.13, 8.13, 8.14, 14.1, 14.8
 */
@HiltAndroidApp
public class GcdApplication extends Application {

    private static final String TAG = "GcdApplication";

    @Inject
    AudioManager audioManager;

    @Inject
    AudioVolumeInitializer audioVolumeInitializer;

    @Inject
    BluetoothServiceManager bluetoothServiceManager;

    @Inject
    MeshtasticConnectionDelegate meshtasticConnectionDelegate;

    @Inject
    TelemetryConnectionDelegate telemetryConnectionDelegate;

    @Inject
    DataSyncManager dataSyncManager;

    @Inject
    GciStatusNotifier gciStatusNotifier;

    @Inject
    CoroutineScope applicationScope;

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize audio volume from persisted preferences
        audioVolumeInitializer.initialize();

        // Start Bluetooth foreground services and bind to them
        bluetoothServiceManager.startServices(this);

        // Initialize connection delegates to forward service flows
        meshtasticConnectionDelegate.initialize(applicationScope);
        telemetryConnectionDelegate.initialize(applicationScope);

        // Start the DataSyncManager to observe incoming HoT packets
        dataSyncManager.start(applicationScope);

        // Start the GCI status notifier for at_home/is_daytime notifications
        gciStatusNotifier.start(applicationScope);

        // Play startup tone
        playStartupTone();

        Log.i(TAG, "GcdApplication initialized — services started, DI wired");
    }

    /**
     * Plays the startup tone after Hilt injection is complete.
     * <p>
     * Requirement 14.1: Play a startup tone when the application launches.
     */
    private void playStartupTone() {
        try {
            audioManager.playStartupTone();
            Log.i(TAG, "Startup tone played");
        } catch (Exception e) {
            Log.w(TAG, "Failed to play startup tone: " + e.getMessage());
        }
    }
}
