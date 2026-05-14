package com.golfcart.gcd.di;

import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.android.components.ServiceComponent;

/**
 * Hilt module providing service-scoped dependencies.
 * <p>
 * Provides: Dependencies scoped to Android foreground services
 * (MeshtasticService, TelemetryService).
 */
@Module
@InstallIn(ServiceComponent.class)
public abstract class ServiceModule {
    // Bindings will be added as foreground service implementations are created
}
