package com.golfcart.gcd.ui.entertainment

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golfcart.gcd.data.persistence.CacheDateValidator
import com.golfcart.gcd.data.persistence.CacheValidity
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.domain.parser.HotPacketParser
import com.golfcart.gcd.domain.parser.VenueEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Entertainment venue/event screen.
 *
 * Loads cached venue/event data on start if from the current day, and refreshes
 * the display when new data arrives while the screen is active.
 *
 * Requirements: 4.3, 4.4, 4.5, 4.6, 4.7, 4.8
 */
@HiltViewModel
class EntertainmentViewModel @Inject constructor(
    private val dataStoreRepository: DataStoreRepository,
    private val hotPacketParser: HotPacketParser,
    private val cacheDateValidator: CacheDateValidator
) : ViewModel() {

    companion object {
        private const val TAG = "EntertainmentViewModel"
    }

    /** Current entertainment data state (null if no data available). */
    private val _entertainmentData = MutableStateFlow<EntertainmentData?>(null)
    val entertainmentData: StateFlow<EntertainmentData?> = _entertainmentData.asStateFlow()

    init {
        loadCachedVenueData()
    }

    /**
     * Loads stored venue/event data on start if from the current day.
     *
     * Requirement 4.7: Load stored venue/event data if from current day.
     */
    private fun loadCachedVenueData() {
        viewModelScope.launch {
            try {
                val cachedData = dataStoreRepository.getCachedVenueEvents().first()

                if (cachedData.rawPacket != null && cachedData.dateYYYYMMDD != 0) {
                    val validity = cacheDateValidator.validate(cachedData.dateYYYYMMDD)

                    if (validity == CacheValidity.FRESH) {
                        // Parse the cached packet and mark as stored
                        val parseResult = hotPacketParser.parseVenueEventPacket(cachedData.rawPacket)
                        parseResult.onSuccess { venues ->
                            _entertainmentData.value = EntertainmentData(
                                venues = venues,
                                receivedTimestamp = cachedData.timestamp ?: "",
                                isStored = true
                            )
                            Log.i(TAG, "Loaded cached venue/event data from today (${venues.size} entries)")
                        }.onFailure { error ->
                            Log.w(TAG, "Failed to parse cached venue/event data: ${error.message}")
                        }
                    } else {
                        Log.i(TAG, "Cached venue/event data is stale (from a previous day)")
                    }
                } else {
                    Log.i(TAG, "No cached venue/event data available")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cached venue/event data", e)
            }
        }
    }

    /**
     * Updates the entertainment data with freshly received live data.
     * Called when a new venue/event HoT packet is received and parsed.
     *
     * Requirement 4.8: Refresh display when new data arrives while screen is active.
     *
     * @param venues The newly parsed list of venue/event entries.
     * @param timestamp The timestamp of when the data was received.
     */
    fun onVenueDataReceived(venues: List<VenueEvent>, timestamp: String) {
        _entertainmentData.value = EntertainmentData(
            venues = venues,
            receivedTimestamp = timestamp,
            isStored = false
        )
        Log.i(TAG, "Updated entertainment display with live data (${venues.size} entries)")
    }
}
