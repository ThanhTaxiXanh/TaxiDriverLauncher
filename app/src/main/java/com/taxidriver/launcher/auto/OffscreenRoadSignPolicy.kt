package com.taxidriver.launcher.auto

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

internal data class OffscreenRoadSign(
    val id: Long,
    val title: String,
    val distanceMeters: Int,
    val relativeDirection: String,
)

/** Informational nearby OSM signs, NOT navigation or traffic-law warnings. */
internal object OffscreenRoadSignPolicy {
    fun suggest(
        car: RoadCoordinate,
        bearingDegrees: Double?,
        candidates: List<NearbyMapSign>,
        inViewIds: Set<Long>,
        maxItems: Int = 2,
    ): List<OffscreenRoadSign> {
        if (maxItems <= 0) return emptyList()
        return candidates.asSequence()
            .filter { it.id !in inViewIds && it.distanceMeters in 0..500 }
            .distinctBy { it.id }
            .sortedWith(compareBy<NearbyMapSign> { it.distanceMeters }.thenBy { it.id })
            .take(maxItems)
            .map { sign ->
                val direction = relativeDirection(car, sign.position, bearingDegrees)
                val name = if (sign.kind == RoadSignKind.SPEED_LIMIT &&
                    sign.speedLimitKmh != null
                ) "Biển ${sign.speedLimitKmh} km/h" else sign.kind.title
                OffscreenRoadSign(sign.id, name, sign.distanceMeters, direction)
            }.toList()
    }

    private fun relativeDirection(
        car: RoadCoordinate, target: RoadCoordinate, bearing: Double?,
    ): String {
        val east = (target.longitude - car.longitude) *
            cos(Math.toRadians((target.latitude + car.latitude) / 2.0))
        val north = target.latitude - car.latitude
        val absolute = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
        if (bearing == null || !bearing.isFinite()) {
            return when (((absolute + 45.0) / 90.0).toInt() % 4) {
                0 -> "Bắc"
                1 -> "Đông"
                2 -> "Nam"
                else -> "Tây"
            }
        }
        val diff = ((absolute - bearing + 540.0) % 360.0) - 180.0
        return when {
            abs(diff) < 35.0 -> "Trước"
            diff in 35.0..145.0 -> "Phải"
            diff in -145.0..-35.0 -> "Trái"
            else -> "Sau"
        }
    }
}
