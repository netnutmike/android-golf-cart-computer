package com.golfcart.gcd.data.bluetooth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Android Foreground Service managing the Bluetooth connection to the GCI ESP-32 computer.
 *
 * Connects via Bluetooth Classic SPP (Serial Port Profile) to receive vehicle telemetry
 * data and send status updates. Runs as a foreground service with a persistent notification
 * to maintain connectivity when the app is backgrounded.
 *
 * Uses a separate notification channel from [MeshtasticService] to allow independent
 * notification management.
 */
class TelemetryService : LifecycleService(), TelemetryConnection {

    companion object {
        private const val TAG = "TelemetryService"

        /** Notification channel ID for the GCI telemetry foreground service. */
        const val NOTIFICATION_CHANNEL_ID = "gci_telemetry_service_channel"

        /** Notification channel display name. */
        const val NOTIFICATION_CHANNEL_NAME = "GCI Telemetry Connection"

        /** Notification ID for the foreground service (distinct from Meshtastic). */
        const val NOTIFICATION_ID = 1002

        /** Standard SPP UUID for Bluetooth Classic serial communication. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Heartbeat interval in milliseconds (10 seconds). */
        const val HEARTBEAT_INTERVAL_MS = 10_000L

        /** Liveness timeout in milliseconds (40 seconds = 4 missed heartbeats). */
        const val LIVENESS_TIMEOUT_MS = 40_000L

        /** Pairing window timeout in seconds. */
        const val DEFAULT_PAIRING_TIMEOUT_SECONDS = 6

        /** SharedPreferences name for GCI pairing data. */
        const val PREFS_NAME = "gci_pairing"

        /** SharedPreferences key for the paired GCI device address. */
        const val KEY_PAIRED_ADDRESS = "paired_gci_address"
    }

    // --- State Flows ---

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetryData = MutableStateFlow(TelemetryData())
    override val telemetryData: StateFlow<TelemetryData> = _telemetryData.asStateFlow()

    // --- Internal State ---

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var livenessJob: Job? = null
    private var connectedDeviceAddress: String? = null
    private var sequenceNumber: Int = 0
    private var lastDataReceivedTime: Long = 0L

    // --- Reconnection with exponential backoff ---

    private val reconnectionStrategy = ReconnectionStrategy(tag = "GciReconnect")

    /**
     * Deferred used during pairing to signal when an ACK is received from the GCI.
     * Non-null only while a pairing operation is in progress.
     */
    private var pairingAckDeferred: CompletableDeferred<String>? = null

    // --- Binder for local binding ---

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): TelemetryService = this@TelemetryService
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
        Log.i(TAG, "TelemetryService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        closeConnection()
        Log.i(TAG, "TelemetryService destroyed")
        super.onDestroy()
    }

    // --- Foreground Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains Bluetooth connection to GCI telemetry computer"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("GCI: Disconnected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
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
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // --- Permission Checking ---

    /**
     * Checks whether the required Bluetooth permissions are granted.
     */
    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
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

    // --- Bluetooth Connection ---

    override suspend fun connect(deviceAddress: String) {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Cannot connect: Bluetooth permissions not granted")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        updateNotification("GCI: Connecting...")
        connectedDeviceAddress = deviceAddress

        serviceScope.launch {
            try {
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                val adapter = bluetoothManager?.adapter
                if (adapter == null) {
                    Log.e(TAG, "Bluetooth adapter not available")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    updateNotification("GCI: Bluetooth unavailable")
                    return@launch
                }

                val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)

                // Cancel discovery to speed up connection
                adapter.cancelDiscovery()

                socket.connect()

                bluetoothSocket = socket
                inputStream = socket.inputStream
                outputStream = socket.outputStream

                _connectionState.value = ConnectionState.READY
                updateNotification("GCI: Connected")
                lastDataReceivedTime = System.currentTimeMillis()

                // Reset reconnection backoff on successful connection
                reconnectionStrategy.reset()

                Log.i(TAG, "Connected to GCI at $deviceAddress")

                // Start reading incoming data
                startReadLoop()

                // Start heartbeat
                startHeartbeat()

                // Start liveness monitoring
                startLivenessMonitor()

            } catch (e: IOException) {
                Log.e(TAG, "Connection failed to $deviceAddress", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                updateNotification("GCI: Connection failed")
                closeConnection()
            } catch (e: SecurityException) {
                Log.e(TAG, "Bluetooth permission denied", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                updateNotification("GCI: Permission denied")
            }
        }
    }

    override suspend fun disconnect() {
        closeConnection()
        _connectionState.value = ConnectionState.DISCONNECTED
        updateNotification("GCI: Disconnected")
        Log.i(TAG, "Disconnected from GCI")
    }

    private fun closeConnection() {
        readJob?.cancel()
        readJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        livenessJob?.cancel()
        livenessJob = null

        try {
            inputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing input stream", e)
        }
        try {
            outputStream?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing output stream", e)
        }
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket", e)
        }

        inputStream = null
        outputStream = null
        bluetoothSocket = null
    }

    // --- Data Reading ---

    /**
     * Starts the background loop that reads incoming data from the GCI Bluetooth socket.
     * Parses message envelopes and routes telemetry data to the state flow.
     */
    private fun startReadLoop() {
        readJob?.cancel()
        readJob = serviceScope.launch {
            val buffer = ByteArray(1024)
            val messageBuffer = mutableListOf<Byte>()

            try {
                while (isActive) {
                    val stream = inputStream ?: break
                    val bytesRead = stream.read(buffer)
                    if (bytesRead == -1) {
                        Log.w(TAG, "End of stream reached")
                        break
                    }

                    // Append received bytes to the message buffer
                    for (i in 0 until bytesRead) {
                        messageBuffer.add(buffer[i])
                    }

                    // Try to parse complete messages from the buffer
                    processMessageBuffer(messageBuffer)
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Read error, connection lost", e)
                }
            }

            // Connection lost — trigger reconnection
            if (isActive) {
                handleConnectionLost()
            }
        }
    }

    /**
     * Processes the accumulated message buffer, extracting complete GCI messages.
     *
     * Attempts to parse messages from the front of the buffer. If a complete message
     * is found, it is removed from the buffer and processed. If the buffer doesn't
     * contain a complete message, parsing stops until more data arrives.
     */
    private fun processMessageBuffer(buffer: MutableList<Byte>) {
        while (buffer.size >= GciProtocol.HEADER_SIZE) {
            // Peek at the data_len field to determine if we have a complete message
            // Header: type(1) + timestamp(4) + seq_num(2) + data_len(2) = 9 bytes
            val dataLenLow = buffer[7].toInt() and 0xFF
            val dataLenHigh = buffer[8].toInt() and 0xFF
            val dataLen = dataLenLow or (dataLenHigh shl 8)

            val totalMessageSize = GciProtocol.HEADER_SIZE + dataLen

            if (buffer.size < totalMessageSize) {
                // Not enough data yet for a complete message
                break
            }

            // Extract the complete message bytes
            val messageBytes = ByteArray(totalMessageSize)
            for (i in 0 until totalMessageSize) {
                messageBytes[i] = buffer[i]
            }

            // Remove processed bytes from the buffer
            repeat(totalMessageSize) { buffer.removeAt(0) }

            // Parse and handle the message
            val message = GciProtocol.parseMessage(messageBytes)
            if (message != null) {
                handleIncomingMessage(message)
            } else {
                Log.w(TAG, "Failed to parse GCI message (${messageBytes.size} bytes)")
            }
        }
    }

    /**
     * Handles a successfully parsed incoming GCI message.
     */
    private fun handleIncomingMessage(message: GciMessage) {
        lastDataReceivedTime = System.currentTimeMillis()

        when (message.type) {
            GciMessageType.TELEMETRY -> {
                val telemetry = GciProtocol.parseTelemetryPayload(message.payload)
                if (telemetry != null) {
                    _telemetryData.value = telemetry
                    Log.d(TAG, "Telemetry received: batt=${telemetry.batteryVoltage}V, " +
                        "fuel=${telemetry.fuelLevel}, temp=${telemetry.airTemperature}°F")
                } else {
                    Log.w(TAG, "Failed to parse telemetry payload (${message.payload.size} bytes)")
                }
            }
            GciMessageType.ACK -> {
                Log.d(TAG, "ACK received, seq=${message.sequenceNumber}")
                handleAckForPairing(message)
            }
            GciMessageType.HEARTBEAT -> {
                Log.d(TAG, "Heartbeat response received")
            }
            else -> {
                Log.d(TAG, "Received ${message.type} message, seq=${message.sequenceNumber}")
            }
        }
    }

    /**
     * Handles an ACK message in the context of an active pairing operation.
     *
     * If a pairing operation is in progress (pairingAckDeferred is non-null),
     * completes the deferred with the connected device address, signaling
     * successful pairing.
     */
    private fun handleAckForPairing(message: GciMessage) {
        val deferred = pairingAckDeferred ?: return

        // The ACK was received during a pairing window — extract the responding
        // device address. The connected device address is the GCI that responded.
        val respondingAddress = connectedDeviceAddress
        if (respondingAddress != null) {
            deferred.complete(respondingAddress)
            Log.i(TAG, "Pairing ACK received from device: $respondingAddress")
        } else {
            // If we don't have a connected device address yet, use a placeholder
            // that indicates an ACK was received (the address will be determined
            // from the Bluetooth connection context)
            deferred.complete("UNKNOWN")
            Log.w(TAG, "Pairing ACK received but no connected device address available")
        }
    }

    // --- Heartbeat ---

    /**
     * Starts the periodic heartbeat sender (10-second interval).
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    sendHeartbeat()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send heartbeat", e)
                }
            }
        }
    }

    /**
     * Starts the liveness monitor that marks the GCI as disconnected
     * if no data is received within the timeout period (40 seconds).
     */
    private fun startLivenessMonitor() {
        livenessJob?.cancel()
        livenessJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS) // Check every heartbeat interval
                val elapsed = System.currentTimeMillis() - lastDataReceivedTime
                if (elapsed > LIVENESS_TIMEOUT_MS) {
                    Log.w(TAG, "GCI liveness timeout (${elapsed}ms since last data)")
                    handleConnectionLost()
                    break
                }
            }
        }
    }

    /**
     * Handles a lost connection — cleans up and attempts reconnection with exponential backoff.
     *
     * Uses exponential backoff (1s, 2s, 4s, 8s, max 30s) to avoid overwhelming
     * the GCI with rapid reconnection attempts. The backoff resets on successful
     * connection. This reconnection operates independently of the Meshtastic connection.
     *
     * Validates: Requirements 17.4 (automatic reconnection without affecting Meshtastic)
     */
    private fun handleConnectionLost() {
        val address = connectedDeviceAddress
        closeConnection()
        _connectionState.value = ConnectionState.DISCONNECTED
        updateNotification("GCI: Disconnected")

        // Attempt automatic reconnection with exponential backoff
        if (address != null) {
            serviceScope.launch {
                Log.i(TAG, "Attempting reconnection to $address " +
                    "(attempt #${reconnectionStrategy.attemptCount + 1})...")
                reconnectionStrategy.waitForNextAttempt()
                connect(address)
            }
        }
    }

    // --- Message Sending ---

    override suspend fun sendHeartbeat() {
        val timestamp = System.currentTimeMillis() / 1000
        val bytes = GciProtocol.buildHeartbeatMessage(timestamp, nextSequenceNumber())
        writeBytes(bytes)
    }

    override suspend fun sendGpsData(
        latitude: Float,
        longitude: Float,
        altitude: Float,
        speed: Float,
        heading: Float,
        satellites: Int
    ) {
        val timestamp = System.currentTimeMillis() / 1000
        val bytes = GciProtocol.buildGpsDataMessage(
            latitude, longitude, altitude, speed, heading, satellites,
            timestamp, nextSequenceNumber()
        )
        writeBytes(bytes)
    }

    override suspend fun sendIsHome(isHome: Boolean) {
        val timestamp = System.currentTimeMillis() / 1000
        val bytes = GciProtocol.buildIsHomeMessage(isHome, timestamp, nextSequenceNumber())
        writeBytes(bytes)
    }

    override suspend fun sendIsDaytime(isDaytime: Boolean) {
        val timestamp = System.currentTimeMillis() / 1000
        val bytes = GciProtocol.buildIsDaytimeMessage(isDaytime, timestamp, nextSequenceNumber())
        writeBytes(bytes)
    }

    override suspend fun pairNewDevice(timeoutSeconds: Int) {
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "Cannot pair: Bluetooth permissions not granted")
            return
        }

        Log.i(TAG, "Pairing initiated with ${timeoutSeconds}s timeout")
        _connectionState.value = ConnectionState.SCANNING
        updateNotification("GCI: Pairing...")

        val previousAddress = getPairedDeviceAddress()

        try {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            if (adapter == null) {
                Log.e(TAG, "Bluetooth adapter not available for pairing")
                _connectionState.value = ConnectionState.DISCONNECTED
                updateNotification("GCI: Bluetooth unavailable")
                return
            }

            // Get the GCD's own Bluetooth MAC address for the pairing command
            val localMacAddress = getLocalMacAddress(adapter)
            if (localMacAddress == null) {
                Log.e(TAG, "Cannot determine local Bluetooth MAC address for pairing")
                _connectionState.value = ConnectionState.DISCONNECTED
                updateNotification("GCI: Pairing failed")
                return
            }

            val macBytes = GciProtocol.parseMacAddress(localMacAddress)

            // Build and broadcast the pairing command
            val timestamp = System.currentTimeMillis() / 1000
            val pairingCommandBytes = GciProtocol.buildPairingCommand(
                macAddress = macBytes,
                timestamp = timestamp,
                sequenceNumber = nextSequenceNumber()
            )

            // Set up the ACK deferred before sending the command
            val ackDeferred = CompletableDeferred<String>()
            pairingAckDeferred = ackDeferred

            // Send the pairing command (broadcast)
            writeBytes(pairingCommandBytes)
            Log.i(TAG, "Pairing command broadcast sent (MAC: $localMacAddress)")

            // Wait for ACK within the timeout window
            val timeoutMs = timeoutSeconds * 1000L
            val ackDeviceAddress = withTimeoutOrNull(timeoutMs) {
                ackDeferred.await()
            }

            // Clear the deferred
            pairingAckDeferred = null

            if (ackDeviceAddress != null) {
                // ACK received — persist the new paired device address
                persistPairedDeviceAddress(ackDeviceAddress)
                connectedDeviceAddress = ackDeviceAddress
                _connectionState.value = ConnectionState.READY
                updateNotification("GCI: Paired")
                Log.i(TAG, "Pairing successful with device: $ackDeviceAddress")
            } else {
                // No ACK received — restore previous paired device address
                Log.w(TAG, "Pairing timeout: no ACK received within ${timeoutSeconds}s")
                if (previousAddress != null) {
                    Log.i(TAG, "Restoring previously paired device: $previousAddress")
                    connectedDeviceAddress = previousAddress
                }
                _connectionState.value = ConnectionState.DISCONNECTED
                updateNotification("GCI: Pairing timeout")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Pairing failed due to I/O error", e)
            pairingAckDeferred = null
            if (previousAddress != null) {
                connectedDeviceAddress = previousAddress
            }
            _connectionState.value = ConnectionState.DISCONNECTED
            updateNotification("GCI: Pairing failed")
        } catch (e: SecurityException) {
            Log.e(TAG, "Pairing failed: Bluetooth permission denied", e)
            pairingAckDeferred = null
            _connectionState.value = ConnectionState.DISCONNECTED
            updateNotification("GCI: Permission denied")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Pairing failed: invalid MAC address", e)
            pairingAckDeferred = null
            _connectionState.value = ConnectionState.DISCONNECTED
            updateNotification("GCI: Pairing failed")
        }
    }

    // --- GCI Pairing Persistence ---

    /**
     * Persists the paired GCI device address for automatic reconnection.
     *
     * @param deviceAddress The Bluetooth MAC address of the paired GCI device.
     */
    fun persistPairedDeviceAddress(deviceAddress: String) {
        val prefs = getGciPreferences()
        prefs.edit().putString(KEY_PAIRED_ADDRESS, deviceAddress).apply()
        Log.i(TAG, "Persisted paired GCI device address: $deviceAddress")
    }

    /**
     * Retrieves the previously paired GCI device address, if any.
     *
     * @return The paired GCI device Bluetooth address, or null if no device has been paired.
     */
    fun getPairedDeviceAddress(): String? {
        val prefs = getGciPreferences()
        return prefs.getString(KEY_PAIRED_ADDRESS, null)
    }

    /**
     * Clears the persisted paired GCI device address.
     */
    fun clearPairedDeviceAddress() {
        val prefs = getGciPreferences()
        prefs.edit().remove(KEY_PAIRED_ADDRESS).apply()
        Log.i(TAG, "Cleared paired GCI device address")
    }

    /**
     * Returns the SharedPreferences instance for GCI pairing data.
     */
    private fun getGciPreferences(): SharedPreferences {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves the local Bluetooth MAC address for inclusion in the pairing command.
     *
     * On Android 6.0+ the adapter address is restricted. This method attempts to
     * retrieve it via the BluetoothAdapter. If the system returns the placeholder
     * "02:00:00:00:00:00", it falls back to the persisted address from SharedPreferences
     * (which can be set during initial device setup).
     *
     * @param adapter The BluetoothAdapter instance.
     * @return The local MAC address string, or null if unavailable.
     */
    @Suppress("MissingPermission")
    private fun getLocalMacAddress(adapter: BluetoothAdapter): String? {
        return try {
            val address = adapter.address
            if (address != null && address != "02:00:00:00:00:00") {
                address
            } else {
                // Fallback: check if a local MAC was stored in preferences
                val prefs = getGciPreferences()
                prefs.getString("local_mac_address", null)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read Bluetooth adapter address", e)
            val prefs = getGciPreferences()
            prefs.getString("local_mac_address", null)
        }
    }

    /**
     * Writes raw bytes to the Bluetooth output stream.
     *
     * @param data The bytes to send.
     * @throws IOException if the write fails.
     */
    private fun writeBytes(data: ByteArray) {
        val stream = outputStream
        if (stream == null) {
            Log.w(TAG, "Cannot write: output stream is null")
            return
        }

        try {
            stream.write(data)
            stream.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Write failed", e)
            throw e
        }
    }

    /**
     * Returns the next sequence number, wrapping at 65535 (unsigned 16-bit).
     */
    private fun nextSequenceNumber(): Int {
        val seq = sequenceNumber
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        return seq
    }

    // --- Utility ---

    /**
     * Returns the currently connected device address, or null if not connected.
     */
    fun getConnectedDeviceAddress(): String? = connectedDeviceAddress
}
