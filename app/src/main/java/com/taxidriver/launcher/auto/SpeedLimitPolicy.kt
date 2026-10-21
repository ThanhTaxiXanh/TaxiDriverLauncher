package com.taxidriver.launcher.auto

import kotlin.math.roundToInt

/** Road-matched Mapbox data only. This policy NEVER infers a limit from road class. */
internal class SpeedLimitPolicy {
    private var currentLimit: Int? = null
    private var lastMatchedMillis: Long = -1L

    fun onMatchedLimit(
        value: Int?,
        unit: RoadSpeedUnit,
        onRoad: Boolean,
        reliableMatch: Boolean,
        elapsedRealtimeMillis: Long,
    ) {
        if (elapsedRealtimeMillis < lastMatchedMillis) return
        lastMatchedMillis = elapsedRealtimeMillis
        currentLimit = when {
            !onRoad || !reliableMatch || value == null -> null
            value <= 0 || value > 200 -> null
            unit == RoadSpeedUnit.KMH -> value
            unit == RoadSpeedUnit.MPH -> (value * 1.609344).roundToInt()
            else -> null
        }
    }

    fun limitAt(nowElapsedMillis: Long): Int? {
        val elapsed = nowElapsedMillis - lastMatchedMillis
        return if (lastMatchedMillis >= 0 && elapsed in 0..6_000L) currentLimit else null
    }

    fun isOverLimit(currentSpeedKmh: Int?, nowElapsedMillis: Long): Boolean {
        val limit = limitAt(nowElapsedMillis) ?: return false
        val speed = currentSpeedKmh ?: return false
        // Small hysteresis to avoid triggering from an isolated 1 km/h GPS fluctuation.
        return speed > limit + 3
    }

    fun clear() {
        currentLimit = null
        lastMatchedMillis = -1L
    }
}

internal enum class RoadSpeedUnit { KMH, MPH, UNKNOWN }
