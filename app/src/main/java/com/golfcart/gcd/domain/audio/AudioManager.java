package com.golfcart.gcd.domain.audio;

/**
 * Interface for managing audio feedback for system events.
 * <p>
 * Provides methods to play various system tones (startup, notification, alert,
 * confirmation, click, error) and to configure the speaker volume level.
 * <p>
 * Audio playback failures should silently fail with a log warning, as specified
 * in the design document's error handling strategy.
 * <p>
 * Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8
 */
public interface AudioManager {

    /**
     * Plays the startup tone when the application launches.
     * <p>
     * Requirement 14.1: Play a startup tone when the application launches.
     */
    void playStartupTone();

    /**
     * Plays the message notification tone when a new Meshtastic message is received.
     * <p>
     * Requirement 14.2: Play a message notification tone when a new Meshtastic message is received.
     */
    void playMessageNotification();

    /**
     * Plays an alert tone for important notifications.
     * <p>
     * Requirement 14.3: Play an alert tone for important notifications.
     */
    void playAlert();

    /**
     * Plays a confirmation tone for successful user actions.
     * <p>
     * Requirement 14.4: Play a confirmation tone for successful user actions.
     */
    void playConfirmation();

    /**
     * Plays a click tone for button presses.
     * <p>
     * Requirement 14.5: Play a click tone for button presses.
     */
    void playClick();

    /**
     * Plays an error tone for failed operations.
     * <p>
     * Requirement 14.6: Play an error tone for failed operations.
     */
    void playError();

    /**
     * Sets the speaker volume level.
     * <p>
     * Requirement 14.7: Provide a configurable speaker volume level (range 0-20).
     *
     * @param level Volume level from 0 (mute) to 20 (maximum).
     *              Values outside this range will be clamped.
     */
    void setVolume(int level);

    /**
     * Gets the current speaker volume level.
     *
     * @return Current volume level (0-20).
     */
    int getVolume();

    /**
     * Releases any audio resources held by this manager.
     * Should be called when the application is shutting down.
     */
    void release();
}
