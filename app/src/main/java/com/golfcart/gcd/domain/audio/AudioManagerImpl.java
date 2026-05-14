package com.golfcart.gcd.domain.audio;

import android.media.ToneGenerator;
import android.util.Log;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Implementation of {@link AudioManager} using Android's {@link ToneGenerator}.
 * <p>
 * Uses DTMF and supervisory tones to provide distinct audio feedback for different
 * system events. Volume is configurable from 0 (mute) to 20 (maximum) and is
 * mapped to ToneGenerator's 0-100 volume range.
 * <p>
 * Audio playback failures are silently caught and logged as warnings, per the
 * design document's error handling strategy.
 * <p>
 * Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8
 */
@Singleton
public class AudioManagerImpl implements AudioManager {

    private static final String TAG = "AudioManager";

    /** Minimum volume level. */
    private static final int MIN_VOLUME = 0;

    /** Maximum volume level. */
    private static final int MAX_VOLUME = 20;

    /** Duration in milliseconds for the startup tone. */
    private static final int STARTUP_TONE_DURATION_MS = 200;

    /** Duration in milliseconds for the notification tone. */
    private static final int NOTIFICATION_TONE_DURATION_MS = 150;

    /** Duration in milliseconds for the alert tone. */
    private static final int ALERT_TONE_DURATION_MS = 300;

    /** Duration in milliseconds for the confirmation tone. */
    private static final int CONFIRMATION_TONE_DURATION_MS = 100;

    /** Duration in milliseconds for the click tone. */
    private static final int CLICK_TONE_DURATION_MS = 50;

    /** Duration in milliseconds for the error tone. */
    private static final int ERROR_TONE_DURATION_MS = 250;

    /** Current volume level (0-20). */
    private volatile int volumeLevel;

    /** ToneGenerator instance, lazily created and recreated on volume change. */
    private ToneGenerator toneGenerator;

    @Inject
    public AudioManagerImpl() {
        this.volumeLevel = 10; // Default volume, will be overridden by persisted preference
        this.toneGenerator = createToneGenerator();
    }

    @Override
    public void playStartupTone() {
        if (volumeLevel == 0) return;
        playTone(ToneGenerator.TONE_PROP_BEEP, STARTUP_TONE_DURATION_MS);
    }

    @Override
    public void playMessageNotification() {
        if (volumeLevel == 0) return;
        playTone(ToneGenerator.TONE_PROP_BEEP2, NOTIFICATION_TONE_DURATION_MS);
    }

    @Override
    public void playAlert() {
        if (volumeLevel == 0) return;
        playTone(ToneGenerator.TONE_SUP_ERROR, ALERT_TONE_DURATION_MS);
    }

    @Override
    public void playConfirmation() {
        if (volumeLevel == 0) return;
        playTone(ToneGenerator.TONE_PROP_ACK, CONFIRMATION_TONE_DURATION_MS);
    }

    @Override
    public void playClick() {
        if (volumeLevel == 0) return;
        playTone(ToneGenerator.TONE_PROP_NACK, CLICK_TONE_DURATION_MS);
    }

    @Override
    public void playError() {
        if (volumeLevel == 0) return;
        playTone(ToneGenerator.TONE_SUP_ERROR, ERROR_TONE_DURATION_MS);
    }

    @Override
    public void setVolume(int level) {
        int clampedLevel = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, level));
        if (this.volumeLevel != clampedLevel) {
            this.volumeLevel = clampedLevel;
            // Recreate ToneGenerator with new volume
            releaseToneGenerator();
            if (clampedLevel > 0) {
                this.toneGenerator = createToneGenerator();
            }
            Log.d(TAG, "Volume set to " + clampedLevel);
        }
    }

    @Override
    public int getVolume() {
        return volumeLevel;
    }

    @Override
    public void release() {
        releaseToneGenerator();
        Log.d(TAG, "AudioManager released");
    }

    /**
     * Plays a tone with the specified type and duration.
     * Silently catches and logs any playback failures.
     *
     * @param toneType The ToneGenerator tone type constant
     * @param durationMs Duration in milliseconds
     */
    private void playTone(int toneType, int durationMs) {
        try {
            ToneGenerator generator = toneGenerator;
            if (generator == null) {
                generator = createToneGenerator();
                toneGenerator = generator;
            }
            if (generator != null) {
                generator.startTone(toneType, durationMs);
            }
        } catch (Exception e) {
            Log.w(TAG, "Audio playback failed: " + e.getMessage());
        }
    }

    /**
     * Creates a new ToneGenerator with the current volume level.
     * Maps the 0-20 volume range to ToneGenerator's 0-100 range.
     *
     * @return A new ToneGenerator instance, or null if creation fails
     */
    private ToneGenerator createToneGenerator() {
        if (volumeLevel == 0) {
            return null;
        }
        try {
            int toneVolume = (volumeLevel * 100) / MAX_VOLUME;
            return new ToneGenerator(
                    android.media.AudioManager.STREAM_NOTIFICATION,
                    toneVolume
            );
        } catch (Exception e) {
            Log.w(TAG, "Failed to create ToneGenerator: " + e.getMessage());
            return null;
        }
    }

    /**
     * Releases the current ToneGenerator instance and sets it to null.
     */
    private void releaseToneGenerator() {
        try {
            if (toneGenerator != null) {
                toneGenerator.release();
                toneGenerator = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing ToneGenerator: " + e.getMessage());
        }
    }
}
