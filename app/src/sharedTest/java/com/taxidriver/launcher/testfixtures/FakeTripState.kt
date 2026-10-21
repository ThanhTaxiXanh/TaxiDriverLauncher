package com.taxidriver.launcher.testfixtures

import com.taxidriver.launcher.data.location.TripState

internal fun fakeTripState(
    distanceMeters: Double = 24_400.0,
    avgSpeedMs: Double = 11.7,
    currentSpeedMs: Double = 0.0,
    // Null by default: the trip-start label formats through the device
    // locale/timezone, so screenshot fixtures stay deterministic without it.
    startedAtEpochMs: Long? = null,
): TripState =
    TripState(
        distanceMeters = distanceMeters,
        avgSpeedMs = avgSpeedMs,
        currentSpeedMs = currentSpeedMs,
        startedAtEpochMs = startedAtEpochMs,
    )
