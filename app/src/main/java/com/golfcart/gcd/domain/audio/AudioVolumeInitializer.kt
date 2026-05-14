package com.golfcart.gcd.domain.audio

import android.util.Log
import com.golfcart.gcd.data.persistence.DataStoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initializes the [AudioManager] volume from persisted preferences on startup
 * and keeps it in sync when preferences change.
 *
 * This bridges the Kotlin DataStore preferences flow to the Java AudioManager
 * implementation, ensuring the volume level is always consistent with the
 * persisted user preference.
 *
 * Uses its own coroutine scope for lifecycle management since it is an
 * application-level singleton.
 *
 * Requirements: 14.7, 14.8
 */
@Singleton
class AudioVolumeInitializer @Inject constructor(
    private val audioManager: AudioManager,
    private val dataStoreRepository: DataStoreRepository
) {

    companion object {
        private const val TAG = "AudioVolumeInitializer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Initializes the AudioManager volume from persisted preferences.
     * Loads the current preference value and applies it, then observes
     * for future changes to keep the volume in sync.
     *
     * Requirement 14.8: Persist the speaker volume setting.
     */
    fun initialize() {
        scope.launch {
            try {
                // Load initial volume from persisted preferences
                val prefs = dataStoreRepository.getPreferences().first()
                audioManager.setVolume(prefs.speakerVolume)
                Log.d(TAG, "AudioManager volume initialized to ${prefs.speakerVolume}")

                // Observe preference changes to keep volume in sync
                dataStoreRepository.getPreferences().collect { preferences ->
                    val currentVolume = audioManager.volume
                    if (currentVolume != preferences.speakerVolume) {
                        audioManager.setVolume(preferences.speakerVolume)
                        Log.d(TAG, "AudioManager volume updated to ${preferences.speakerVolume}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize audio volume: ${e.message}")
            }
        }
    }
}
