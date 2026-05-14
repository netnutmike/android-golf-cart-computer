package com.golfcart.gcd.domain.sync

import android.util.Log
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.MeshtasticConstants
import com.golfcart.gcd.data.bluetooth.MeshtasticMessageHandler
import com.golfcart.gcd.data.bluetooth.MeshtasticMessageHandler.IncomingTextMessage
import com.golfcart.gcd.data.persistence.CacheDateValidator
import com.golfcart.gcd.data.persistence.CacheValidity
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.domain.parser.HotPacketParser
import com.golfcart.gcd.domain.parser.VenueEvent
import com.golfcart.gcd.domain.parser.WeatherData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the end-to-end data synchronization and caching flow.
 *
 * Responsibilities:
 * 1. Observes incoming text messages from [MeshtasticMessageHandler]
 * 2. Identifies HoT packets and routes to the appropriate parser
 * 3. Caches parsed data to DataStore with timestamp and date
 * 4. Notifies ViewModels of new data via shared flows
 * 5. Triggers the "new data received" indicator
 * 6. Manages the "(stored)" indicator for cached vs live data
 * 7. Requests fresh data when cache is stale on startup
 *
 * Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6
 */
@Singleton
class DataSyncManager @Inject constructor(
    private val messageHandler: MeshtasticMessageHandler,
    private val hotPacketParser: HotPacketParser,
    private val dataStoreRepository: DataStoreRepository,
    private val cacheDateValidator: CacheDateValidator,
    private val meshtasticConnection: MeshtasticConnection
) {

    /**
     * The text messages flow to observe. Defaults to [messageHandler.textMessages]
     * but can be overridden for testing via [startWithTextMessages].
     */
    private var textMessagesSource: SharedFlow<IncomingTextMessage>? = null

    companion object {
        private const val TAG = "DataSyncManager"

        /** Request message sent when weather/venue cache is stale or absent. */
        const val FRESH_DATA_REQUEST = "~#01#GC#REQ_WX_ENT#"

        /** Channel 0 for data requests. */
        const val REQUEST_CHANNEL = 0

        /** Timestamp format for display. */
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")

        /** Weather packet type code. */
        private const val PACKET_TYPE_WEATHER = 1

        /** Venue/event packet type code. */
        private const val PACKET_TYPE_VENUE = 2
    }

    // --- Events emitted to ViewModels ---

    private val _weatherDataReceived = MutableSharedFlow<WeatherData>(extraBufferCapacity = 1)
    /** Flow of freshly parsed weather data for WeatherViewModel. */
    val weatherDataReceived: SharedFlow<WeatherData> = _weatherDataReceived.asSharedFlow()

    private val _venueDataReceived = MutableSharedFlow<VenueDataEvent>(extraBufferCapacity = 1)
    /** Flow of freshly parsed venue data for EntertainmentViewModel. */
    val venueDataReceived: SharedFlow<VenueDataEvent> = _venueDataReceived.asSharedFlow()

    private val _newDataIndicator = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Flow that emits when new data is received, for MainViewModel's 5-second indicator. */
    val newDataIndicator: SharedFlow<Unit> = _newDataIndicator.asSharedFlow()

    /** Whether a fresh data request has been sent this session. */
    private var freshDataRequestSent = false

    /**
     * Starts observing incoming text messages and processing HoT packets.
     * Should be called once from the application scope during initialization.
     *
     * @param scope The coroutine scope to launch collection in (typically application scope).
     */
    fun start(scope: CoroutineScope) {
        val source = textMessagesSource ?: messageHandler.textMessages

        // Observe incoming text messages from MeshtasticMessageHandler
        scope.launch {
            source.collect { message ->
                processIncomingTextMessage(message)
            }
        }

        // Check cache freshness on startup and request data if stale
        scope.launch {
            checkCacheAndRequestFreshData()
        }

        Log.i(TAG, "DataSyncManager started — observing incoming text messages")
    }

    /**
     * Starts the DataSyncManager with a custom text messages source.
     * Used for testing to inject a controllable flow.
     *
     * @param scope The coroutine scope to launch collection in.
     * @param textMessages The flow of incoming text messages to observe.
     */
    fun startWithTextMessages(scope: CoroutineScope, textMessages: SharedFlow<IncomingTextMessage>) {
        textMessagesSource = textMessages
        start(scope)
    }

    /**
     * Processes an incoming text message to determine if it's a HoT packet.
     * If so, parses it, caches it, and notifies ViewModels.
     */
    private suspend fun processIncomingTextMessage(message: IncomingTextMessage) {
        val text = message.text

        // Check if this is a HoT packet
        if (!hotPacketParser.isHotPacket(text)) {
            return // Not a HoT packet, ignore
        }

        val packetType = hotPacketParser.parsePacketType(text)
        val timestamp = formatCurrentTimestamp()
        val currentDate = cacheDateValidator.getCurrentDateYYYYMMDD()

        when (packetType) {
            PACKET_TYPE_WEATHER -> handleWeatherPacket(text, timestamp, currentDate)
            PACKET_TYPE_VENUE -> handleVenuePacket(text, timestamp, currentDate)
            else -> Log.w(TAG, "Unknown HoT packet type: $packetType, text: '${text.take(40)}'")
        }
    }

    /**
     * Handles a weather HoT packet: parse → cache → notify.
     *
     * Requirement 19.1: Persist raw packet data, timestamp, and date.
     * Requirement 19.6: Clear "(stored)" indicator when live data replaces cache.
     */
    private suspend fun handleWeatherPacket(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {
        val parseResult = hotPacketParser.parseWeatherPacket(rawPacket)

        parseResult.onSuccess { weatherData ->
            // Cache the raw packet with timestamp and date
            dataStoreRepository.cacheWeatherData(rawPacket, timestamp, dateYYYYMMDD)
            Log.i(TAG, "Cached weather data (date=$dateYYYYMMDD, timestamp=$timestamp)")

            // Emit live data (isStored = false) to clear the "(stored)" indicator
            val liveData = weatherData.copy(
                receivedTimestamp = timestamp,
                isStored = false
            )
            _weatherDataReceived.tryEmit(liveData)

            // Trigger the "new data received" indicator
            _newDataIndicator.tryEmit(Unit)

            Log.i(TAG, "Weather data received and dispatched: currentTemp=${weatherData.currentTemp}")
        }.onFailure { error ->
            Log.w(TAG, "Failed to parse weather packet: ${error.message}, " +
                    "raw='${rawPacket.take(40)}${if (rawPacket.length > 40) "..." else ""}'")
        }
    }

    /**
     * Handles a venue/event HoT packet: parse → cache → notify.
     *
     * Requirement 19.2: Persist raw packet data, timestamp, and date.
     * Requirement 19.6: Clear "(stored)" indicator when live data replaces cache.
     */
    private suspend fun handleVenuePacket(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {
        val parseResult = hotPacketParser.parseVenueEventPacket(rawPacket)

        parseResult.onSuccess { venues ->
            // Cache the raw packet with timestamp and date
            dataStoreRepository.cacheVenueData(rawPacket, timestamp, dateYYYYMMDD)
            Log.i(TAG, "Cached venue/event data (date=$dateYYYYMMDD, timestamp=$timestamp, " +
                    "${venues.size} entries)")

            // Emit live data (isStored = false) to clear the "(stored)" indicator
            val event = VenueDataEvent(
                venues = venues,
                timestamp = timestamp,
                isStored = false
            )
            _venueDataReceived.tryEmit(event)

            // Trigger the "new data received" indicator
            _newDataIndicator.tryEmit(Unit)

            Log.i(TAG, "Venue/event data received and dispatched: ${venues.size} entries")
        }.onFailure { error ->
            Log.w(TAG, "Failed to parse venue/event packet: ${error.message}, " +
                    "raw='${rawPacket.take(40)}${if (rawPacket.length > 40) "..." else ""}'")
        }
    }

    /**
     * Checks cached data freshness on startup and requests fresh data if stale.
     *
     * Requirement 19.3: Validate cached data by comparing stored date against today's date.
     * Requirement 19.5: Request fresh data if cache is from a previous day or absent.
     */
    private suspend fun checkCacheAndRequestFreshData() {
        try {
            val cachedWeather = dataStoreRepository.getCachedWeather().first()
            val cachedVenue = dataStoreRepository.getCachedVenueEvents().first()

            val weatherStale = cachedWeather.rawPacket == null ||
                    cachedWeather.dateYYYYMMDD == 0 ||
                    cacheDateValidator.validate(cachedWeather.dateYYYYMMDD) == CacheValidity.STALE

            val venueStale = cachedVenue.rawPacket == null ||
                    cachedVenue.dateYYYYMMDD == 0 ||
                    cacheDateValidator.validate(cachedVenue.dateYYYYMMDD) == CacheValidity.STALE

            if (weatherStale || venueStale) {
                Log.i(TAG, "Cache is stale (weather=$weatherStale, venue=$venueStale), " +
                        "will request fresh data when Meshtastic is ready")
                sendFreshDataRequest()
            } else {
                Log.i(TAG, "Cache is fresh for today — no request needed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking cache freshness", e)
            sendFreshDataRequest()
        }
    }

    /**
     * Sends the fresh data request message via Meshtastic broadcast.
     * Waits for the connection to be READY if not already connected.
     *
     * Requirement 19.5: Request fresh data from the mesh network.
     */
    private suspend fun sendFreshDataRequest() {
        if (freshDataRequestSent) return
        freshDataRequestSent = true

        try {
            val currentState = meshtasticConnection.connectionState.value
            if (currentState == ConnectionState.READY) {
                meshtasticConnection.sendTextMessage(
                    text = FRESH_DATA_REQUEST,
                    destination = MeshtasticConstants.BROADCAST_ADDRESS,
                    channel = REQUEST_CHANNEL
                )
                Log.i(TAG, "Sent fresh data request: $FRESH_DATA_REQUEST")
            } else {
                Log.d(TAG, "Meshtastic not ready (state=$currentState), " +
                        "waiting for connection to send fresh data request")
                waitForConnectionAndSendRequest()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send fresh data request", e)
            freshDataRequestSent = false
        }
    }

    /**
     * Waits for the Meshtastic connection to become READY, then sends the request.
     */
    private suspend fun waitForConnectionAndSendRequest() {
        meshtasticConnection.connectionState.collect { state ->
            if (state == ConnectionState.READY) {
                try {
                    meshtasticConnection.sendTextMessage(
                        text = FRESH_DATA_REQUEST,
                        destination = MeshtasticConstants.BROADCAST_ADDRESS,
                        channel = REQUEST_CHANNEL
                    )
                    Log.i(TAG, "Sent deferred fresh data request: $FRESH_DATA_REQUEST")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send deferred fresh data request", e)
                }
                return@collect
            }
        }
    }

    /**
     * Formats the current time as a display timestamp (e.g., "2:30 PM").
     */
    private fun formatCurrentTimestamp(): String {
        return try {
            val now = Instant.now().atZone(ZoneId.systemDefault())
            TIMESTAMP_FORMATTER.format(now)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to format timestamp", e)
            ""
        }
    }
}

/**
 * Event data class for venue data received events.
 *
 * @property venues The parsed list of venue/event entries.
 * @property timestamp The formatted timestamp of when data was received.
 * @property isStored Whether this data was loaded from cache (always false for live data).
 */
data class VenueDataEvent(
    val venues: List<VenueEvent>,
    val timestamp: String,
    val isStored: Boolean
)
