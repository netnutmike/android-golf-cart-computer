package com.golfcart.gcd.di

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.MeshtasticService
import com.golfcart.gcd.data.bluetooth.TelemetryConnection
import com.golfcart.gcd.data.bluetooth.TelemetryData
import com.golfcart.gcd.data.bluetooth.TelemetryService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the lifecycle of Bluetooth foreground services and provides
 * access to their connection interfaces for dependency injection.
 *
 * This class starts and binds to [MeshtasticService] and [TelemetryService],
 * then delegates all interface calls to the bound service instances. This allows
 * the connection interfaces to be injected as singletons throughout the app
 * while the actual implementations live in Android foreground services.
 *
 * Requirements: 16.13, 8.13, 8.14
 */
@Singleton
class BluetoothServiceManager @Inject constructor() {

    companion object {
        private const val TAG = "BluetoothServiceMgr"
    }

    /** Deferred that completes when MeshtasticService is bound. */
    val meshtasticServiceReady = CompletableDeferred<MeshtasticService>()

    /** Deferred that completes when TelemetryService is bound. */
    val telemetryServiceReady = CompletableDeferred<TelemetryService>()

    private var meshtasticService: MeshtasticService? = null
    private var telemetryService: TelemetryService? = null

    private val meshtasticServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshtasticService.LocalBinder
            meshtasticService = binder.getService()
            meshtasticServiceReady.complete(binder.getService())
            Log.i(TAG, "MeshtasticService bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshtasticService = null
            Log.w(TAG, "MeshtasticService disconnected")
        }
    }

    private val telemetryServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TelemetryService.LocalBinder
            telemetryService = binder.getService()
            telemetryServiceReady.complete(binder.getService())
            Log.i(TAG, "TelemetryService bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            telemetryService = null
            Log.w(TAG, "TelemetryService disconnected")
        }
    }

    /**
     * Starts and binds both Bluetooth foreground services.
     * Should be called from [com.golfcart.gcd.GcdApplication.onCreate].
     *
     * @param context The application context.
     */
    fun startServices(context: Context) {
        // Start MeshtasticService as foreground service
        val meshtasticIntent = Intent(context, MeshtasticService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(meshtasticIntent)
        } else {
            context.startService(meshtasticIntent)
        }
        context.bindService(meshtasticIntent, meshtasticServiceConnection, Context.BIND_AUTO_CREATE)

        // Start TelemetryService as foreground service
        val telemetryIntent = Intent(context, TelemetryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(telemetryIntent)
        } else {
            context.startService(telemetryIntent)
        }
        context.bindService(telemetryIntent, telemetryServiceConnection, Context.BIND_AUTO_CREATE)

        Log.i(TAG, "Bluetooth services started and binding initiated")
    }

    /**
     * Returns the bound [MeshtasticService] instance, or null if not yet bound.
     */
    fun getMeshtasticService(): MeshtasticService? = meshtasticService

    /**
     * Returns the bound [TelemetryService] instance, or null if not yet bound.
     */
    fun getTelemetryService(): TelemetryService? = telemetryService
}

/**
 * Delegating implementation of [MeshtasticConnection] that forwards all calls
 * to the bound [MeshtasticService] instance.
 *
 * Before the service is bound, state flows emit default values and suspend
 * functions await the service binding via [BluetoothServiceManager.meshtasticServiceReady].
 */
@Singleton
class MeshtasticConnectionDelegate @Inject constructor(
    private val serviceManager: BluetoothServiceManager
) : MeshtasticConnection {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _nodeId = MutableStateFlow("")
    override val nodeId: StateFlow<String> = _nodeId.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    /**
     * Initializes the delegate by collecting from the actual service's flows.
     * Called after the service is bound.
     */
    fun initialize(scope: CoroutineScope) {
        scope.launch {
            val service = serviceManager.meshtasticServiceReady.await()

            // Forward connection state
            launch { service.connectionState.collect { _connectionState.value = it } }
            // Forward node ID
            launch { service.nodeId.collect { _nodeId.value = it } }
            // Forward incoming packets
            launch { service.incomingPackets.collect { _incomingPackets.tryEmit(it) } }
        }
    }

    private suspend fun getService(): MeshtasticService {
        return serviceManager.meshtasticServiceReady.await()
    }

    override suspend fun sendTextMessage(text: String, destination: Long, channel: Int) {
        getService().sendTextMessage(text, destination, channel)
    }

    override suspend fun sendAdminMessage(message: ByteArray) {
        getService().sendAdminMessage(message)
    }

    override suspend fun setPositionConfig(config: ByteArray) {
        getService().setPositionConfig(config)
    }

    override suspend fun rebootRadio(delaySeconds: Int) {
        getService().rebootRadio(delaySeconds)
    }

    override suspend fun disconnect() {
        getService().disconnect()
    }

    override suspend fun connect(deviceAddress: String) {
        getService().connect(deviceAddress)
    }

    override suspend fun startScan() {
        getService().startScan()
    }

    override suspend fun stopScan() {
        getService().stopScan()
    }
}

/**
 * Delegating implementation of [TelemetryConnection] that forwards all calls
 * to the bound [TelemetryService] instance.
 *
 * Before the service is bound, state flows emit default values and suspend
 * functions await the service binding via [BluetoothServiceManager.telemetryServiceReady].
 */
@Singleton
class TelemetryConnectionDelegate @Inject constructor(
    private val serviceManager: BluetoothServiceManager
) : TelemetryConnection {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetryData = MutableStateFlow(TelemetryData())
    override val telemetryData: StateFlow<TelemetryData> = _telemetryData.asStateFlow()

    /**
     * Initializes the delegate by collecting from the actual service's flows.
     * Called after the service is bound.
     */
    fun initialize(scope: CoroutineScope) {
        scope.launch {
            val service = serviceManager.telemetryServiceReady.await()

            // Forward connection state
            launch { service.connectionState.collect { _connectionState.value = it } }
            // Forward telemetry data
            launch { service.telemetryData.collect { _telemetryData.value = it } }
        }
    }

    private suspend fun getService(): TelemetryService {
        return serviceManager.telemetryServiceReady.await()
    }

    override suspend fun sendHeartbeat() {
        getService().sendHeartbeat()
    }

    override suspend fun sendGpsData(
        latitude: Float,
        longitude: Float,
        altitude: Float,
        speed: Float,
        heading: Float,
        satellites: Int
    ) {
        getService().sendGpsData(latitude, longitude, altitude, speed, heading, satellites)
    }

    override suspend fun sendIsHome(isHome: Boolean) {
        getService().sendIsHome(isHome)
    }

    override suspend fun sendIsDaytime(isDaytime: Boolean) {
        getService().sendIsDaytime(isDaytime)
    }

    override suspend fun pairNewDevice(timeoutSeconds: Int) {
        getService().pairNewDevice(timeoutSeconds)
    }

    override suspend fun connect(deviceAddress: String) {
        getService().connect(deviceAddress)
    }

    override suspend fun disconnect() {
        getService().disconnect()
    }
}
