package com.golfcart.gcd.domain.service

/**
 * Represents the current state of the service reminder system.
 *
 * Driving hours are stored internally in tenths of hours (6-minute resolution)
 * but exposed as a float for display purposes.
 *
 * Requirements: 7.2, 7.6, 7.7
 */
data class ServiceReminderState(
    /** Driving hours since last service, in tenths of hours (6-minute resolution). */
    val tenthsOfHoursSinceService: Int = 0,

    /** Configurable service interval in hours (default: 100). */
    val serviceIntervalHours: Int = 100
) {
    /** Hours since last service as a float for display (e.g., 12.3 hours). */
    val hoursSinceService: Float
        get() = tenthsOfHoursSinceService / 10.0f

    /** Whether service is due (hours since service >= service interval). */
    val isServiceDue: Boolean
        get() = hoursSinceService >= serviceIntervalHours
}
