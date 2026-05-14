package com.golfcart.gcd.data.bluetooth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Android Foreground Service managing the BLE connection to the Meshtastic radio.
 *
 * Uses Kable for BLE scanning, connection, MTU negotiation, and GATT characteristic
 * read/write/notify operations. Runs as a foreground service with a persistent
 * notification to maintain connectivity when the app is backgrounded.
 */
class MeshtasticService : LifecycleService(), MeshtasticConnection {

    companion object {
        private const val TAG = "MeshtasticService"
    }

    // --- State Flows ---

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _nodeId = MutableStateFlow("")
    override val nodeId: StateFlow<String> = _nodeId.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    // --- Internal State ---

    private var peripheral: Peripheral? = null
    private var protocol: MeshtasticProtocol? = null
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var fromNumJob: Job? = null
    private var negotiatedMtu: Int = MeshtasticConstants.DEFAULT_PAYLOAD_SIZE

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Handshake and Lifecycle ---

    private val handshake = MeshtasticHandshake()
    private var lifecycleManager: MeshtasticLifecycleManager? = null
    private var connectedDeviceAddress: String? = null

    // --- Admin Commands (read-modify-write for position config) ---

    private val adminCommands = MeshtasticAdminCommands()

    // --- Reconnection with exponential backoff ---

    private val reconnectionStrategy = ReconnectionStrategy(tag = "MeshtasticReconnect")

    // --- Message Handler ---

    private val messageHandler = MeshtasticMessageHandler(
        localNodeNum = { handshake.myNodeNum }
    )

    // --- Binder for local binding ---

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MeshtasticService = this@MeshtasticService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // --- Service Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification()
        Log.i(TAG, "MeshtasticService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleManager?.stop()
        serviceScope.cancel()
        scanJob?.cancel()
        connectionJob?.cancel()
        fromNumJob?.cancel()
        Log.i(TAG, "MeshtasticService destroyed")
        super.onDestroy()
    }

    // --- Foreground Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            MeshtasticConstants.NOTIFICATION_CHANNEL_ID,
            MeshtasticConstants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains BLE connection to Meshtastic radio"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Meshtastic: Disconnected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                MeshtasticConstants.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(MeshtasticConstants.NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, MeshtasticConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Golf Cart Computer")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(MeshtasticConstants.NOTIFICATION_ID, notification)
    }

    // --- Permission Checking ---

    /**
     * Checks whether the required Bluetooth permissions are granted.
     * On Android 12+ (API 31), BLUETOOTH_CONNECT and BLUETOOTH_SCAN are required.
     * On older versions, BLUETOOTH and BLUETOOTH_ADMIN suffice.
     */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns the list of Bluetooth permissions required for the current API level.
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    // --- BLE Scanning ---

    override suspend fun startScan() {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Cannot scan: Bluetooth permissions not granted")
            return
        }

        // Check for a previously bonded device and try direct connection first
        val bondedAddress = lifecycleManager?.getBondedDeviceAddress(this)
            ?: MeshtasticLifecycleManager(
                scope = serviceScope,
                protocol = null,
                handshake = handshake,
                onReconnectNeeded = { handleReconnect() }
            ).getBondedDeviceAddress(this)

        if (bondedAddress != null) {
            Log.i(TAG, "Found bonded device address: $bondedAddress, attempting direct connection")
            connect(bondedAddress)
            return
        }

        _connectionState.value = ConnectionState.SCANNING
        updateNotification("Meshtastic: Scanning...")

        scanJob?.cancel()
        scanJob = serviceScope.launch {
            try {
                val scanner = Scanner()
                scanner.advertisements
                    .filter { advertisement ->
                        isMeshtasticDevice(advertisement)
                    }
                    .catch { e ->
                        Log.e(TAG, "Scan error", e)
                        _connectionState.value = ConnectionState.DISCONNECTED
                        updateNotification("Meshtastic: Scan failed")
                    }
                    .collect { advertisement ->
                        Log.i(TAG, "Found Meshtastic device: ${advertisement.name} [${advertisement.address}]")
                        // Auto-connect to the first matching device found
                        stopScan()
                        connect(advertisement.address)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                updateNotification("Meshtastic: Scan failed")
            }
        }
    }

    override suspend fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /**
     * Checks if a BLE advertisement matches the Meshtastic device name pattern.
     */
    private fun isMeshtasticDevice(advertisement: Advertisement): Boolean {
        val name = advertisement.name ?: return false
        return MeshtasticConstants.DEVICE_NAME_PATTERN.matches(name)
    }

    // --- BLE Connection ---

    override suspend fun connect(deviceAddress: String) {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Cannot connect: Bluetooth permissions not granted")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        updateNotification("Meshtastic: Connecting...")
        connectedDeviceAddress = deviceAddress

        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            try {
                // Scan for the specific device by address
                val scanner = Scanner()
                val targetAdvertisement = scanner.advertisements
                    .filter { it.address == deviceAddress }
                    .first()

                // Create peripheral from the advertisement
                val newPeripheral = serviceScope.peripheral(targetAdvertisement) {
                    // Request a larger MTU for efficient packet transfer
                    onServicesDiscovered {
                        requestMtu(MeshtasticConstants.DESIRED_MTU)
                    }
                }

                peripheral = newPeripheral

                // Connect to the peripheral
                newPeripheral.connect()

                // MTU negotiation result — Kable handles this during connect
                // The effective write size is (negotiated MTU - MTU_OVERHEAD)
                negotiatedMtu = try {
                    // Kable's MTU is available after connection
                    MeshtasticConstants.DESIRED_MTU - MeshtasticConstants.MTU_OVERHEAD
                } catch (e: Exception) {
                    Log.w(TAG, "MTU negotiation info unavailable, using default", e)
                    MeshtasticConstants.DEFAULT_PAYLOAD_SIZE
                }

                Log.i(TAG, "Connected to $deviceAddress, effective payload size: $negotiatedMtu")

                _connectionState.value = ConnectionState.CONNECTED
                updateNotification("Meshtastic: Connected")

                // Create protocol handler for this peripheral
                val newProtocol = MeshtasticProtocol(newPeripheral, negotiatedMtu)
                protocol = newProtocol

                Log.i(TAG, "GATT characteristics discovered:")
                Log.i(TAG, "  Service: ${MeshtasticConstants.SERVICE_UUID}")
                Log.i(TAG, "  TORADIO: ${MeshtasticConstants.TORADIO_UUID}")
                Log.i(TAG, "  FROMRADIO: ${MeshtasticConstants.FROMRADIO_UUID}")
                Log.i(TAG, "  FROMNUM: ${MeshtasticConstants.FROMNUM_UUID}")

                // Subscribe to FROMNUM notifications and start polling
                subscribeToFromNum(newProtocol)

                // Initiate the Meshtastic handshake
                initiateHandshake(newProtocol)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to $deviceAddress", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                updateNotification("Meshtastic: Connection failed")
                peripheral = null
                protocol = null
            }
        }
    }

    override suspend fun disconnect() {
        try {
            // Perform graceful disconnect: send ToRadio(disconnect=true) before closing
            lifecycleManager?.performGracefulDisconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error during graceful disconnect", e)
        }

        try {
            peripheral?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error during BLE disconnect", e)
        } finally {
            fromNumJob?.cancel()
            fromNumJob = null
            peripheral = null
            protocol = null
            lifecycleManager?.stop()
            lifecycleManager = null
            handshake.reset()
            adminCommands.reset()
            _connectionState.value = ConnectionState.DISCONNECTED
            _nodeId.value = ""
            connectedDeviceAddress = null
            updateNotification("Meshtastic: Disconnected")
            Log.i(TAG, "Disconnected from Meshtastic device")
        }
    }

    // --- Message Sending ---

    /**
     * Sends a text message via the Meshtastic mesh network.
     *
     * Constructs a MeshPacket with TEXT_MESSAGE_APP port, random packet ID,
     * and the specified destination/channel. Enforces the 237-byte payload limit.
     *
     * @param text The message text to send.
     * @param destination The destination node number (use [MeshtasticConstants.BROADCAST_ADDRESS] for broadcast).
     * @param channel The channel index to send on.
     * @throws MeshtasticMessageHandler.PayloadTooLargeException if text exceeds 237 bytes when UTF-8 encoded.
     */
    override suspend fun sendTextMessage(text: String, destination: Long, channel: Int) {
        val currentProtocol = protocol
        if (currentProtocol == null || _connectionState.value != ConnectionState.READY) {
            Log.w(TAG, "Cannot send text message: not connected/ready")
            return
        }

        try {
            val toRadioBytes = messageHandler.buildTextMessage(text, destination, channel)
            currentProtocol.writeToRadio(toRadioBytes)
            Log.i(TAG, "Sent text message to ${destination.toString(16)} on channel $channel")
        } catch (e: MeshtasticMessageHandler.PayloadTooLargeException) {
            Log.e(TAG, "Text message payload too large", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
            throw e
        }
    }

    /**
     * Sends an admin message to the connected radio.
     *
     * Admin messages are self-addressed (sent to local node) on channel 0
     * using the ADMIN_APP port number.
     *
     * @param message The protobuf-encoded AdminMessage bytes.
     * @throws MeshtasticMessageHandler.PayloadTooLargeException if payload exceeds 237 bytes.
     */
    override suspend fun sendAdminMessage(message: ByteArray) {
        val currentProtocol = protocol
        if (currentProtocol == null || _connectionState.value != ConnectionState.READY) {
            Log.w(TAG, "Cannot send admin message: not connected/ready")
            return
        }

        try {
            val toRadioBytes = messageHandler.buildAdminMessage(message)
            currentProtocol.writeToRadio(toRadioBytes)
            Log.i(TAG, "Sent admin message: ${message.size} bytes")
        } catch (e: MeshtasticMessageHandler.PayloadTooLargeException) {
            Log.e(TAG, "Admin message payload too large", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send admin message", e)
            throw e
        }
    }

    /**
     * Sets the position configuration on the connected radio.
     *
     * Uses a read-modify-write pattern via [MeshtasticAdminCommands]: merges the
     * incoming position config with the stored config from handshake to preserve
     * unmodified fields, then wraps in AdminMessage(set_config) and sends.
     *
     * Requirement 12.5: Read-modify-write pattern preserves unmodified fields
     *
     * @param config The protobuf-encoded PositionConfig bytes (may be partial — only
     *               fields that need changing). The service will merge with stored config.
     */
    override suspend fun setPositionConfig(config: ByteArray) {
        val currentProtocol = protocol
        if (currentProtocol == null || _connectionState.value != ConnectionState.READY) {
            Log.w(TAG, "Cannot set position config: not connected/ready")
            return
        }

        try {
            // Extract the GPS interval from the incoming config to use read-modify-write
            val incomingFields = adminCommands.parseProtobufFields(config)
            val gpsIntervalField = incomingFields.find {
                it.tag == MeshtasticAdminCommands.POSITION_CONFIG_GPS_UPDATE_INTERVAL_TAG
            }

            val mergedConfig = if (gpsIntervalField != null) {
                // Use read-modify-write: merge with stored config from handshake
                val intervalValue = adminCommands.decodeVarintValue(gpsIntervalField.data).toInt()
                adminCommands.buildPositionConfigWithGpsInterval(intervalValue)
            } else {
                // No GPS interval field — send as-is (caller provided full config)
                config
            }

            val adminPayload = messageHandler.buildSetPositionConfigPayload(mergedConfig)
            val toRadioBytes = messageHandler.buildAdminMessage(adminPayload)
            currentProtocol.writeToRadio(toRadioBytes)
            Log.i(TAG, "Sent position config update: ${mergedConfig.size} bytes (read-modify-write)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set position config", e)
            throw e
        }
    }

    /**
     * Reboots the connected Meshtastic radio after the specified delay.
     *
     * Sends an AdminMessage with reboot_seconds field set.
     *
     * @param delaySeconds Delay before reboot in seconds.
     */
    override suspend fun rebootRadio(delaySeconds: Int) {
        val currentProtocol = protocol
        if (currentProtocol == null || _connectionState.value != ConnectionState.READY) {
            Log.w(TAG, "Cannot reboot radio: not connected/ready")
            return
        }

        try {
            val rebootPayload = messageHandler.buildRebootAdminPayload(delaySeconds)
            val toRadioBytes = messageHandler.buildAdminMessage(rebootPayload)
            currentProtocol.writeToRadio(toRadioBytes)
            Log.i(TAG, "Sent reboot command with ${delaySeconds}s delay")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reboot command", e)
            throw e
        }
    }

    // --- Handshake and Lifecycle Management ---

    /**
     * Subscribes to FROMNUM notifications and starts the FROMRADIO polling loop.
     * Each FROMNUM notification triggers a poll of FROMRADIO until empty.
     */
    private fun subscribeToFromNum(meshtasticProtocol: MeshtasticProtocol) {
        fromNumJob?.cancel()
        fromNumJob = meshtasticProtocol.observeFromNumNotifications()
            .onEach {
                // FROMNUM notification received — poll FROMRADIO for all available data
                val packets = meshtasticProtocol.pollFromRadio()
                for (packet in packets) {
                    processReceivedPacket(packet)
                }
            }
            .catch { e ->
                Log.e(TAG, "FROMNUM notification stream error", e)
                handleConnectionLost()
            }
            .launchIn(serviceScope)

        Log.i(TAG, "Subscribed to FROMNUM notifications")
    }

    /**
     * Initiates the Meshtastic handshake sequence.
     *
     * Sends ToRadio(want_config_id=<random>) and transitions to HANDSHAKING state.
     * The handshake responses are processed in [processReceivedPacket].
     */
    private suspend fun initiateHandshake(meshtasticProtocol: MeshtasticProtocol) {
        _connectionState.value = ConnectionState.HANDSHAKING
        updateNotification("Meshtastic: Handshaking...")

        val handshakeBytes = handshake.initiateHandshake()
        try {
            meshtasticProtocol.writeToRadio(handshakeBytes)
            Log.i(TAG, "Handshake initiated, config_id=${handshake.configId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send handshake message", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            updateNotification("Meshtastic: Handshake failed")
        }
    }

    /**
     * Processes a received FromRadio packet.
     *
     * During handshake, packets are routed to the handshake processor.
     * After handshake completion, packets are routed through the message handler
     * for portnum-based routing and also emitted to [incomingPackets] for raw access.
     */
    private fun processReceivedPacket(packetBytes: ByteArray) {
        // Update liveness timestamp
        lifecycleManager?.onDataReceived()

        if (_connectionState.value == ConnectionState.HANDSHAKING) {
            val handshakeComplete = handshake.processFromRadio(packetBytes)
            if (handshakeComplete) {
                onHandshakeComplete()
            }
        } else {
            // Post-handshake: route through message handler for portnum-based routing
            messageHandler.routeIncomingPacket(packetBytes)
            // Also emit raw bytes for any consumers that need unprocessed packets
            _incomingPackets.tryEmit(packetBytes)
        }
    }

    /**
     * Called when the handshake completes successfully.
     *
     * Transitions to READY state, updates the node ID, persists the bonded device,
     * starts the lifecycle manager (heartbeat + liveness monitoring), and parses
     * position config received during handshake for later read-modify-write operations.
     * Resets the reconnection backoff since the connection is now established.
     *
     * Validates: Requirements 12.3 (position config read during handshake)
     */
    private fun onHandshakeComplete() {
        _connectionState.value = ConnectionState.READY
        _nodeId.value = handshake.nodeIdHex
        updateNotification("Meshtastic: Ready (${handshake.nodeIdHex})")

        // Reset reconnection backoff on successful connection
        reconnectionStrategy.reset()

        // Parse position config from handshake for read-modify-write operations
        // Requirement 12.3: Read position config during handshake
        // Requirement 12.5: Read-modify-write pattern preserves unmodified fields
        val configBytes = handshake.positionConfigBytes
        if (configBytes != null) {
            adminCommands.parsePositionConfigFromHandshake(configBytes)
            Log.i(TAG, "Position config parsed from handshake for admin commands")
        } else {
            Log.w(TAG, "No position config received during handshake")
        }

        Log.i(TAG, "Handshake complete! Node ID: ${handshake.nodeIdHex}")

        // Persist the bonded device address for future reconnections
        connectedDeviceAddress?.let { address ->
            val manager = MeshtasticLifecycleManager(
                scope = serviceScope,
                protocol = protocol,
                handshake = handshake,
                onReconnectNeeded = { handleReconnect() }
            )
            manager.persistBondedDevice(this, address)
            manager.start()
            lifecycleManager = manager
        }
    }

    /**
     * Handles reconnection when the liveness timeout expires or connection is lost.
     *
     * Uses exponential backoff (1s, 2s, 4s, 8s, max 30s) to avoid overwhelming
     * the radio with rapid reconnection attempts. The backoff resets on successful
     * connection. This reconnection operates independently of the GCI connection.
     *
     * Validates: Requirements 17.3 (automatic reconnection without affecting GCI)
     */
    private fun handleReconnect() {
        serviceScope.launch {
            Log.w(TAG, "Reconnection triggered (attempt #${reconnectionStrategy.attemptCount + 1})")
            val address = connectedDeviceAddress

            // Clean up current connection state
            fromNumJob?.cancel()
            fromNumJob = null
            peripheral = null
            protocol = null
            handshake.reset()
            adminCommands.reset()
            _connectionState.value = ConnectionState.DISCONNECTED
            _nodeId.value = ""
            updateNotification("Meshtastic: Reconnecting...")

            // Wait with exponential backoff before attempting reconnection
            reconnectionStrategy.waitForNextAttempt()

            // Attempt to reconnect to the same device
            if (address != null) {
                connect(address)
            } else {
                startScan()
            }
        }
    }

    /**
     * Handles an unexpected connection loss (e.g., FROMNUM stream error).
     */
    private fun handleConnectionLost() {
        lifecycleManager?.stop()
        handleReconnect()
    }

    // --- Utility ---

    /**
     * Returns the current effective payload size based on MTU negotiation.
     * Payload size = negotiated MTU - 3 bytes overhead.
     */
    fun getEffectivePayloadSize(): Int = negotiatedMtu

    /**
     * Returns the currently connected peripheral, or null if not connected.
     */
    fun getPeripheral(): Peripheral? = peripheral

    /**
     * Returns the current MeshtasticProtocol instance, or null if not connected.
     */
    fun getProtocol(): MeshtasticProtocol? = protocol

    /**
     * Returns the handshake handler for testing or external access.
     */
    fun getHandshake(): MeshtasticHandshake = handshake

    /**
     * Returns the local node number from the handshake.
     * Returns 0 if the handshake has not completed yet.
     */
    fun getLocalNodeNum(): Long = handshake.myNodeNum

    /**
     * Returns the message handler for accessing routed incoming message flows.
     */
    fun getMessageHandler(): MeshtasticMessageHandler = messageHandler

    /**
     * Returns the lifecycle manager, or null if not in READY state.
     */
    fun getLifecycleManager(): MeshtasticLifecycleManager? = lifecycleManager

    /**
     * Returns the admin commands handler for position config and reboot operations.
     */
    fun getAdminCommands(): MeshtasticAdminCommands = adminCommands
}
