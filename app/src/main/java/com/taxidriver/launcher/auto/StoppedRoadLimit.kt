package com.taxidriver.launcher.auto

import kotlin.math.cos
import kotlin.math.hypot
import java.util.Locale

/**
 * Bounded continuation of an already verified speed limit while stationary.
 * No guessing by road type, and no continuation after moving or road change.
 */
internal class StoppedRoadLimit {
    private var previousLimit: Int? = null
    private var previousLat = 0.0
    private var previousLon = 0.0
    private var previousRoadNames: Set<String> = emptySet()
    private var verifiedAt = -1L

    private fun names(values: List<String>): Set<String> = values.map {
        it.trim().lowercase(Locale.ROOT)
            .removePrefix("đường ").removePrefix("phố ")
    }.filter(String::isNotBlank).toSet()

    fun remember(
        limit: Int?, latitude: Double, longitude: Double,
        roadNames: List<String>, reliableRoad: Boolean,
        moving: Boolean, nowMillis: Long,
    ) {
        if (limit == null || limit !in 5..200 || !moving || !reliableRoad ||
            !latitude.isFinite() || !longitude.isFinite()
        ) return
        val nameSet = names(roadNames)
        if (nameSet.isEmpty()) return
        previousLimit = limit
        previousLat = latitude
        previousLon = longitude
        previousRoadNames = nameSet
        verifiedAt = nowMillis
    }

    fun atStop(
        latitude: Double, longitude: Double,
        speedKmh: Int?, accuracyMeters: Float?,
        currentRoadNames: List<String>, reliableRoad: Boolean,
        fixAgeMillis: Long, nowMillis: Long,
    ): Int? {
        val held = previousLimit ?: return null
        if (speedKmh == null || speedKmh > 3 ||
            accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters > 20f ||
            fixAgeMillis !in 0L..3_500L ||
            nowMillis - verifiedAt !in 0L..90_000L ||
            !latitude.isFinite() || !longitude.isFinite()
        ) return null
        if (reliableRoad && names(currentRoadNames).intersect(previousRoadNames).isEmpty()) {
            clear()
            return null
        }
        val north = (latitude - previousLat) * 111_320.0
        val east = (longitude - previousLon) * 111_320.0 *
            cos(Math.toRadians((latitude + previousLat) / 2.0))
        return if (hypot(east, north) <= 25.0) held else null
    }

    fun clear() {
        previousLimit = null
        previousRoadNames = emptySet()
        verifiedAt = -1L
    }
}
