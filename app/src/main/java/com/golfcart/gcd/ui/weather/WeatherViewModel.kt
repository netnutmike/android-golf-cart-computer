package com.golfcart.gcd.ui.weather

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.MeshtasticConstants
import com.golfcart.gcd.data.persistence.CacheDateValidator
import com.golfcart.gcd.data.persistence.CacheValidity
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.domain.parser.HotPacketParser
import com.golfcart.gcd.domain.parser.WeatherData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Weather forecast screen.
 *
 * Loads cached weather data on start if from the current day, and sends a request
 * message via Meshtastic if the cache is stale or absent.
 *
 * Requirements: 3.4, 3.5, 3.6, 3.7, 3.8
 */
@HiltViewModel
class WeatherViewModel @Inject constructor(
    private val dataStoreRepository: DataStoreRepository,
    private val hotPacketParser: HotPacketParser,
    private val cacheDateValidator: CacheDateValidator,
    private val meshtasticConnection: MeshtasticConnection
) : ViewModel() {

    companion object {
        private const val TAG = "WeatherViewModel"

        /** Request message sent when weather cache is stale or absent. */
        const val WEATHER_REQUEST_MESSAGE = "~#01#GC#REQ_WX_ENT#"

        /** Channel 0 for weather request. */
        const val REQUEST_CHANNEL = 0
    }

    /** Current weather data state (null if no data available). */
    private val _weatherData = MutableStateFlow<WeatherData?>(null)
    val weatherData: StateFlow<WeatherData?> = _weatherData.asStateFlow()

    /** Whether a weather request has been sent this session. */
    private var requestSent = false

    init {
        loadCachedWeatherData()
    }

    /**
     * Loads stored weather data on start if from the current day.
     * If cache is stale or absent, sends a request message for fresh data.
     *
     * Requirement 3.7: Load stored weather data if from current day.
     * Requirement 3.8: Send request if cache is stale or absent.
     */
    private fun loadCachedWeatherData() {
        viewModelScope.launch {
            try {
                val cachedData = dataStoreRepository.getCachedWeather().first()

                if (cachedData.rawPacket != null && cachedData.dateYYYYMMDD != 0) {
                    val validity = cacheDateValidator.validate(cachedData.dateYYYYMMDD)

                    if (validity == CacheValidity.FRESH) {
                        // Parse the cached packet and mark as stored
                        val parseResult = hotPacketParser.parseWeatherPacket(cachedData.rawPacket)
                        parseResult.onSuccess { parsed ->
                            _weatherData.value = parsed.copy(
                                receivedTimestamp = cachedData.timestamp ?: "",
                                isStored = true
                            )
                            Log.i(TAG, "Loaded cached weather data from today")
                        }.onFailure { error ->
                            Log.w(TAG, "Failed to parse cached weather data: ${error.message}")
                            sendWeatherRequest()
                        }
                    } else {
                        // Cache is stale (from a previous day)
                        Log.i(TAG, "Cached weather data is stale, requesting fresh data")
                        sendWeatherRequest()
                    }
                } else {
                    // No cached data available
                    Log.i(TAG, "No cached weather data, requesting fresh data")
                    sendWeatherRequest()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cached weather data", e)
                sendWeatherRequest()
            }
        }
    }

    /**
     * Sends the weather/entertainment request message via Meshtastic broadcast on channel 0.
     *
     * Requirement 3.8: Send `~#01#GC#REQ_WX_ENT#` if cache is stale or absent.
     */
    private fun sendWeatherRequest() {
        if (requestSent) return
        requestSent = true

        viewModelScope.launch {
            try {
                // Only send if Meshtastic is connected
                val currentState = meshtasticConnection.connectionState.value
                if (currentState == ConnectionState.READY) {
                    meshtasticConnection.sendTextMessage(
                        text = WEATHER_REQUEST_MESSAGE,
                        destination = MeshtasticConstants.BROADCAST_ADDRESS,
                        channel = REQUEST_CHANNEL
                    )
                    Log.i(TAG, "Sent weather request: $WEATHER_REQUEST_MESSAGE")
                } else {
                    Log.d(TAG, "Meshtastic not ready (state=$currentState), " +
                            "will retry weather request when connected")
                    observeConnectionForRequest()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send weather request", e)
                requestSent = false
            }
        }
    }

    /**
     * Observes Meshtastic connection state and sends the weather request
     * once the connection becomes READY.
     */
    private fun observeConnectionForRequest() {
        viewModelScope.launch {
            meshtasticConnection.connectionState.collect { state ->
                if (state == ConnectionState.READY && requestSent) {
                    try {
                        meshtasticConnection.sendTextMessage(
                            text = WEATHER_REQUEST_MESSAGE,
                            destination = MeshtasticConstants.BROADCAST_ADDRESS,
                            channel = REQUEST_CHANNEL
                        )
                        Log.i(TAG, "Sent deferred weather request: $WEATHER_REQUEST_MESSAGE")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send deferred weather request", e)
                    }
                    return@collect
                }
            }
        }
    }

    /**
     * Updates the weather data with freshly received live data.
     * Called when a new weather HoT packet is received and parsed.
     *
     * @param data The newly parsed weather data (isStored = false).
     */
    fun onWeatherDataReceived(data: WeatherData) {
        _weatherData.value = data.copy(isStored = false)
        Log.i(TAG, "Updated weather display with live data")
    }
}
