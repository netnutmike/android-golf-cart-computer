package com.golfcart.gcd.domain.sync

import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.MeshtasticConstants
import com.golfcart.gcd.data.bluetooth.MeshtasticMessageHandler
import com.golfcart.gcd.data.bluetooth.MeshtasticMessageHandler.IncomingTextMessage
import com.golfcart.gcd.data.persistence.CacheDateValidator
import com.golfcart.gcd.data.persistence.CacheValidity
import com.golfcart.gcd.data.persistence.CachedVenueData
import com.golfcart.gcd.data.persistence.CachedWeatherData
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.data.persistence.OdometerData
import com.golfcart.gcd.data.persistence.PreferenceKey
import com.golfcart.gcd.data.persistence.UserPreferences
import com.golfcart.gcd.domain.parser.HotPacketParser
import com.golfcart.gcd.domain.parser.HourForecast
import com.golfcart.gcd.domain.parser.VenueEvent
import com.golfcart.gcd.domain.parser.WeatherData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DataSyncManager].
 *
 * Tests the end-to-end data synchronization flow:
 * - Weather data reception → parse → cache → display
 * - Venue/event data reception → parse → cache → display
 * - "(stored)" indicator management
 * - Fresh data request when cache is stale
 *
 * Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataSyncManagerTest {

    private lateinit var dataSyncManager: DataSyncManager
    private lateinit var messageHandler: MeshtasticMessageHandler
    private lateinit var fakeParser: FakeHotPacketParser
    private lateinit var fakeRepository: FakeDataStoreRepository
    private lateinit var fakeCacheValidator: FakeCacheDateValidator
    private lateinit var fakeMeshtasticConnection: FakeMeshtasticConnection
    private lateinit var textMessagesFlow: MutableSharedFlow<IncomingTextMessage>

    @BeforeEach
    fun setUp() {
        messageHandler = MeshtasticMessageHandler { 0x11223344L }
        fakeParser = FakeHotPacketParser()
        fakeRepository = FakeDataStoreRepository()
        fakeCacheValidator = FakeCacheDateValidator()
        fakeMeshtasticConnection = FakeMeshtasticConnection()
        textMessagesFlow = MutableSharedFlow(extraBufferCapacity = 64)

        dataSyncManager = DataSyncManager(
            messageHandler = messageHandler,
            hotPacketParser = fakeParser,
            dataStoreRepository = fakeRepository,
            cacheDateValidator = fakeCacheValidator,
            meshtasticConnection = fakeMeshtasticConnection
        )
    }

    @Test
    @DisplayName("Weather data reception → parse → cache → display flow")
    fun weatherDataReceptionFlow() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        // Set up cache as fresh so no request is sent
        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("existing", "1:00 PM", 20250115)
        fakeRepository.cachedVenueData = CachedVenueData("existing", "1:00 PM", 20250115)

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        // Collect weather data events
        var receivedWeather: WeatherData? = null
        val collectJob = testScope.launch {
            dataSyncManager.weatherDataReceived.collect { receivedWeather = it }
        }

        // Simulate incoming weather HoT packet
        val weatherPacket = "|#01#72#10am,1,75,0.0#11am,2,78,30%#12pm,3,80,50%#1pm,4,82,0.0#"
        textMessagesFlow.emit(IncomingTextMessage(
            from = 0x12345678L,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            packetId = 1,
            text = weatherPacket
        ))
        testScope.advanceUntilIdle()

        // Verify weather data was parsed and emitted
        assertNotNull(receivedWeather)
        assertEquals(72, receivedWeather!!.currentTemp)
        assertFalse(receivedWeather!!.isStored, "Live data should have isStored = false")
        assertEquals(4, receivedWeather!!.forecasts.size)

        // Verify data was cached
        assertEquals(weatherPacket, fakeRepository.lastCachedWeatherPacket)
        assertEquals(20250115, fakeRepository.lastCachedWeatherDate)

        collectJob.cancel()
    }

    @Test
    @DisplayName("Venue/event data reception → parse → cache → display flow")
    fun venueDataReceptionFlow() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        // Set up cache as fresh
        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("existing", "1:00 PM", 20250115)
        fakeRepository.cachedVenueData = CachedVenueData("existing", "1:00 PM", 20250115)

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        // Collect venue data events
        var receivedVenue: VenueDataEvent? = null
        val collectJob = testScope.launch {
            dataSyncManager.venueDataReceived.collect { receivedVenue = it }
        }

        // Simulate incoming venue HoT packet
        val venuePacket = "|#02#Katie Belles,Live Band#Brownwood Paddock,DJ Night#"
        textMessagesFlow.emit(IncomingTextMessage(
            from = 0x12345678L,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            packetId = 2,
            text = venuePacket
        ))
        testScope.advanceUntilIdle()

        // Verify venue data was parsed and emitted
        assertNotNull(receivedVenue)
        assertEquals(2, receivedVenue!!.venues.size)
        assertFalse(receivedVenue!!.isStored, "Live data should have isStored = false")
        assertEquals("Katie Belles", receivedVenue!!.venues[0].venueName)
        assertEquals("Live Band", receivedVenue!!.venues[0].eventName)

        // Verify data was cached
        assertEquals(venuePacket, fakeRepository.lastCachedVenuePacket)
        assertEquals(20250115, fakeRepository.lastCachedVenueDate)

        collectJob.cancel()
    }

    @Test
    @DisplayName("Live data clears (stored) indicator — Requirement 19.6")
    fun liveDataClearsStoredIndicator() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("existing", "1:00 PM", 20250115)
        fakeRepository.cachedVenueData = CachedVenueData("existing", "1:00 PM", 20250115)

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        var receivedWeather: WeatherData? = null
        val collectJob = testScope.launch {
            dataSyncManager.weatherDataReceived.collect { receivedWeather = it }
        }

        // Send a weather packet
        val weatherPacket = "|#01#72#10am,1,75,0.0#11am,2,78,30%#12pm,3,80,50%#1pm,4,82,0.0#"
        textMessagesFlow.emit(IncomingTextMessage(
            from = 0x12345678L,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            packetId = 1,
            text = weatherPacket
        ))
        testScope.advanceUntilIdle()

        // Requirement 19.6: Live data should have isStored = false
        assertNotNull(receivedWeather)
        assertFalse(receivedWeather!!.isStored,
            "Requirement 19.6: Live data should clear the (stored) indicator")

        collectJob.cancel()
    }

    @Test
    @DisplayName("New data received indicator is triggered")
    fun newDataIndicatorTriggered() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("existing", "1:00 PM", 20250115)
        fakeRepository.cachedVenueData = CachedVenueData("existing", "1:00 PM", 20250115)

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        var indicatorTriggered = false
        val collectJob = testScope.launch {
            dataSyncManager.newDataIndicator.collect { indicatorTriggered = true }
        }

        // Send a weather packet
        val weatherPacket = "|#01#72#10am,1,75,0.0#11am,2,78,30%#12pm,3,80,50%#1pm,4,82,0.0#"
        textMessagesFlow.emit(IncomingTextMessage(
            from = 0x12345678L,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            packetId = 1,
            text = weatherPacket
        ))
        testScope.advanceUntilIdle()

        assertTrue(indicatorTriggered, "New data indicator should be triggered on data receipt")

        collectJob.cancel()
    }

    @Test
    @DisplayName("Fresh data request sent when cache is stale — Requirement 19.5")
    fun freshDataRequestWhenCacheStale() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        // Set up stale cache
        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("old", "1:00 PM", 20250114) // yesterday
        fakeRepository.cachedVenueData = CachedVenueData("old", "1:00 PM", 20250114)

        // Meshtastic is ready
        fakeMeshtasticConnection.setConnectionState(ConnectionState.READY)

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        // Verify fresh data request was sent
        assertTrue(fakeMeshtasticConnection.sentMessages.isNotEmpty(),
            "Should send fresh data request when cache is stale")
        assertEquals(DataSyncManager.FRESH_DATA_REQUEST,
            fakeMeshtasticConnection.sentMessages.first().text)
    }

    @Test
    @DisplayName("No fresh data request when cache is fresh")
    fun noRequestWhenCacheFresh() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        // Set up fresh cache
        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("today", "1:00 PM", 20250115)
        fakeRepository.cachedVenueData = CachedVenueData("today", "1:00 PM", 20250115)

        fakeMeshtasticConnection.setConnectionState(ConnectionState.READY)

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        // Verify no request was sent
        assertTrue(fakeMeshtasticConnection.sentMessages.isEmpty(),
            "Should NOT send fresh data request when cache is fresh")
    }

    @Test
    @DisplayName("Non-HoT messages are ignored")
    fun nonHotMessagesIgnored() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("existing", "1:00 PM", 20250115)
        fakeRepository.cachedVenueData = CachedVenueData("existing", "1:00 PM", 20250115)

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        var weatherReceived = false
        val collectJob = testScope.launch {
            dataSyncManager.weatherDataReceived.collect { weatherReceived = true }
        }

        // Send a regular text message (not a HoT packet)
        textMessagesFlow.emit(IncomingTextMessage(
            from = 0x12345678L,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            packetId = 1,
            text = "Hello from the mesh!"
        ))
        testScope.advanceUntilIdle()

        assertFalse(weatherReceived, "Non-HoT messages should be ignored")

        collectJob.cancel()
    }

    @Test
    @DisplayName("Malformed HoT packets are discarded gracefully")
    fun malformedPacketsDiscarded() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("existing", "1:00 PM", 20250115)
        fakeRepository.cachedVenueData = CachedVenueData("existing", "1:00 PM", 20250115)

        // Make parser return failure for malformed packets
        fakeParser.weatherParseFailure = true

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        var weatherReceived = false
        val collectJob = testScope.launch {
            dataSyncManager.weatherDataReceived.collect { weatherReceived = true }
        }

        // Send a malformed weather packet
        textMessagesFlow.emit(IncomingTextMessage(
            from = 0x12345678L,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            packetId = 1,
            text = "|#01#bad_data"
        ))
        testScope.advanceUntilIdle()

        assertFalse(weatherReceived, "Malformed packets should not emit weather data")
        assertNull(fakeRepository.lastCachedWeatherPacket,
            "Malformed packets should not be cached")

        collectJob.cancel()
    }

    @Test
    @DisplayName("Fresh data request deferred until Meshtastic is ready")
    fun freshDataRequestDeferredUntilReady() = runTest {
        val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

        // Set up stale cache
        fakeCacheValidator.currentDate = 20250115
        fakeRepository.cachedWeatherData = CachedWeatherData("old", "1:00 PM", 20250114)
        fakeRepository.cachedVenueData = CachedVenueData("old", "1:00 PM", 20250114)

        // Meshtastic is NOT ready yet
        fakeMeshtasticConnection.setConnectionState(ConnectionState.CONNECTING)

        dataSyncManager.startWithTextMessages(testScope, textMessagesFlow)
        testScope.advanceUntilIdle()

        // No request sent yet
        assertTrue(fakeMeshtasticConnection.sentMessages.isEmpty(),
            "Should not send request when Meshtastic is not ready")

        // Now Meshtastic becomes ready
        fakeMeshtasticConnection.setConnectionState(ConnectionState.READY)
        testScope.advanceUntilIdle()

        // Request should now be sent
        assertTrue(fakeMeshtasticConnection.sentMessages.isNotEmpty(),
            "Should send request once Meshtastic becomes ready")
        assertEquals(DataSyncManager.FRESH_DATA_REQUEST,
            fakeMeshtasticConnection.sentMessages.first().text)
    }

    // --- Fake implementations ---

    private class FakeHotPacketParser : HotPacketParser {
        var weatherParseFailure = false
        var venueParseFailure = false

        override fun parseWeatherPacket(rawPacket: String): Result<WeatherData> {
            if (weatherParseFailure) {
                return Result.failure(IllegalArgumentException("Malformed weather packet"))
            }
            val forecasts = listOf(
                HourForecast("10am", 1, 75, ""),
                HourForecast("11am", 2, 78, "30%"),
                HourForecast("12pm", 3, 80, "50%"),
                HourForecast("1pm", 4, 82, "")
            )
            return Result.success(WeatherData(
                currentTemp = 72,
                forecasts = forecasts,
                receivedTimestamp = "",
                isStored = false
            ))
        }

        override fun parseVenueEventPacket(rawPacket: String): Result<List<VenueEvent>> {
            if (venueParseFailure) {
                return Result.failure(IllegalArgumentException("Malformed venue packet"))
            }
            return Result.success(listOf(
                VenueEvent("Katie Belles", "Live Band"),
                VenueEvent("Brownwood Paddock", "DJ Night")
            ))
        }

        override fun isHotPacket(text: String): Boolean = text.startsWith("|")

        override fun parsePacketType(text: String): Int {
            if (text.length < 4) return -1
            return try {
                text.substring(2, 4).toInt()
            } catch (e: NumberFormatException) {
                -1
            }
        }
    }

    private class FakeCacheDateValidator : CacheDateValidator() {
        var currentDate: Int = 20250115

        override fun validate(storedDateYYYYMMDD: Int): CacheValidity {
            return if (storedDateYYYYMMDD == currentDate) CacheValidity.FRESH else CacheValidity.STALE
        }

        override fun getCurrentDateYYYYMMDD(): Int = currentDate
    }

    private class FakeDataStoreRepository : DataStoreRepository {
        var cachedWeatherData = CachedWeatherData()
        var cachedVenueData = CachedVenueData()
        var lastCachedWeatherPacket: String? = null
        var lastCachedWeatherDate: Int = 0
        var lastCachedVenuePacket: String? = null
        var lastCachedVenueDate: Int = 0

        override fun getPreferences(): Flow<UserPreferences> = flowOf(UserPreferences())
        override suspend fun updatePreference(key: PreferenceKey, value: Any) {}
        override suspend fun resetAllPreferences() {}
        override fun getCachedWeather(): Flow<CachedWeatherData> = flowOf(cachedWeatherData)
        override fun getCachedVenueEvents(): Flow<CachedVenueData> = flowOf(cachedVenueData)

        override suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {
            lastCachedWeatherPacket = rawPacket
            lastCachedWeatherDate = dateYYYYMMDD
        }

        override suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {
            lastCachedVenuePacket = rawPacket
            lastCachedVenueDate = dateYYYYMMDD
        }

        override suspend fun persistOdometer(accumDistance: Float, tripDistance: Float) {}
        override suspend fun persistDrivingHours(tenthsOfHours: Int) {}
        override fun getPersistedOdometer(): Flow<OdometerData> = flowOf(OdometerData())
        override fun getPersistedDrivingHours(): Flow<Int> = flowOf(0)
    }

    private class FakeMeshtasticConnection : MeshtasticConnection {
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<ConnectionState> = _connectionState
        override val nodeId: StateFlow<String> = MutableStateFlow("")
        override val incomingPackets: SharedFlow<ByteArray> = MutableSharedFlow()

        data class SentMessage(val text: String, val destination: Long, val channel: Int)
        val sentMessages = mutableListOf<SentMessage>()

        fun setConnectionState(state: ConnectionState) {
            _connectionState.value = state
        }

        override suspend fun sendTextMessage(text: String, destination: Long, channel: Int) {
            sentMessages.add(SentMessage(text, destination, channel))
        }

        override suspend fun sendAdminMessage(message: ByteArray) {}
        override suspend fun setPositionConfig(config: ByteArray) {}
        override suspend fun rebootRadio(delaySeconds: Int) {}
        override suspend fun disconnect() {}
        override suspend fun connect(deviceAddress: String) {}
        override suspend fun startScan() {}
        override suspend fun stopScan() {}
    }
}
