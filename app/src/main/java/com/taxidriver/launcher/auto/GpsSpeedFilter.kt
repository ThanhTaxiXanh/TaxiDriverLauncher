package com.taxidriver.launcher.auto

import kotlin.math.roundToInt

/**
 * A speed display is not a speed limit. It must not fabricate speed when the
 * location provider has no recent, usable speed measurement.
 */
internal class GpsSpeedFilter {
    private var smoothedMetersPerSecond: Double? = null
    private var lastFixElapsedMillis: Long? = null

    /** Consume only a location fix already accepted by GpsFixPolicy. */
    fun accept(
        speedMetersPerSecond: Float?,
        speedAccuracyMetersPerSecond: Float?,
        elapsedRealtimeMillis: Long,
    ) {
        if (speedMetersPerSecond == null ||
            !speedMetersPerSecond.isFinite() ||
            speedMetersPerSecond < 0f ||
            speedMetersPerSecond > 75f ||
            (speedAccuracyMetersPerSecond != null &&
                (!speedAccuracyMetersPerSecond.isFinite() ||
                    speedAccuracyMetersPerSecond > 5f))
        ) {
            // Invalid measurement must not extend the age of the last trusted fix.
            return
        }
        val previousTime = lastFixElapsedMillis
        if (previousTime != null && elapsedRealtimeMillis <= previousTime) return

        // Below ~3.6 km/h we snap to zero to avoid GPS idle drift.
        val sample = if (speedMetersPerSecond < 1.0f) 0.0 else speedMetersPerSecond.toDouble()
        val previous = smoothedMetersPerSecond
        smoothedMetersPerSecond = when {
            sample == 0.0 -> 0.0
            previous == null -> sample
            else -> previous + (sample - previous) * 0.35
        }
        lastFixElapsedMillis = elapsedRealtimeMillis
    }

    /** Null means unknown/stale, and must be displayed as a dash, not 0 km/h. */
    fun kmh(nowElapsedMillis: Long): Int? {
        val last = lastFixElapsedMillis ?: return null
        val dt = nowElapsedMillis - last
        if (dt < 0L || dt > 3_500L) return null
        return (smoothedMetersPerSecond!! * 3.6).roundToInt()
    }

    fun reset() {
        smoothedMetersPerSecond = null
        lastFixElapsedMillis = null
    }
}

/** Reserved for future verified road data; never infer limits or signs from GPS speed. */
internal data class VerifiedRoadInformation(
    val speedLimitKmh: Int? = null,
    val upcomingSign: String? = null,
)
