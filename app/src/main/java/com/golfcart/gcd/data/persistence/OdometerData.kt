package com.golfcart.gcd.data.persistence

/**
 * Data class representing persisted odometer values.
 * Used to load accumulated and trip distances on application startup.
 *
 * Requirements: 6.9, 6.10 — Persist and load odometer values.
 */
data class OdometerData(
    val accumDistance: Float = 0.0f,
    val tripDistance: Float = 0.0f
)
