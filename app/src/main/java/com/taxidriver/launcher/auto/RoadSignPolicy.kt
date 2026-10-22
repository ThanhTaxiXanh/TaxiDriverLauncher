package com.taxidriver.launcher.auto

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.PI

/** Only signs explicitly associated with a road way and a driving direction. */
internal enum class RoadSignKind(val title: String, val glyph: String) {
    STOP("Dừng lại", "STOP"),
    GIVE_WAY("Nhường đường", "⚠"),
    PEDESTRIAN("Lối người đi bộ", "🚶"),
    RAILWAY("Đường sắt giao cắt", "⚠"),
    HAZARD("Nguy hiểm", "⚠"),
    OTHER("Biển báo OSM", "i"),
    SPEED_LIMIT("Giới hạn tốc độ", "km/h"),
}

internal data class RoadCoordinate(val latitude: Double, val longitude: Double)

internal data class RoadSign(
    val id: Long,
    val kind: RoadSignKind,
    val position: RoadCoordinate,
    val roadName: String,
    val roadPoints: List<RoadCoordinate>,
    val roadNodeIds: List<Long>,
    val direction: String,
)

/** A verifiable OSM node shown on the map, not necessarily a driving warning. */
internal data class OsmMapSign(
    val id: Long,
    val kind: RoadSignKind,
    val position: RoadCoordinate,
    val speedLimitKmh: Int? = null,
)

internal data class NearbyMapSign(
    val id: Long,
    val kind: RoadSignKind,
    val position: RoadCoordinate,
    val distanceMeters: Int,
    val speedLimitKmh: Int? = null,
)

internal data class UpcomingRoadSign(
    val id: Long,
    val kind: RoadSignKind,
    val distanceMeters: Int,
)

/**
 * Deliberately restrictive: OSM road-name agreement with Mapbox free-drive,
 * explicit forward/backward direction, and measured along-way distance.
 * Never infer a sign from proximity alone or assume an upcoming turn.
 */
internal object RoadSignPolicy {
    private const val EARTH_METERS_PER_DEGREE = 111_320.0

    /** Radius, not distance ahead. Works at zero speed and for other roads. */
    fun visibleWithinRadius(
        car: RoadCoordinate,
        candidates: List<OsmMapSign>,
        radiusMeters: Double = 500.0,
    ): List<NearbyMapSign> {
        if (!car.latitude.isFinite() || !car.longitude.isFinite() ||
            car.latitude !in -90.0..90.0 || car.longitude !in -180.0..180.0 ||
            !radiusMeters.isFinite() || radiusMeters <= 0.0
        ) return emptyList()

        return candidates.mapNotNull { sign ->
            val p = sign.position
            if (!p.latitude.isFinite() || !p.longitude.isFinite() ||
                p.latitude !in -90.0..90.0 || p.longitude !in -180.0..180.0
            ) return@mapNotNull null
            val meanLat = Math.toRadians((car.latitude + p.latitude) / 2.0)
            val dx = (p.longitude - car.longitude) * EARTH_METERS_PER_DEGREE * cos(meanLat)
            val dy = (p.latitude - car.latitude) * EARTH_METERS_PER_DEGREE
            val distance = hypot(dx, dy)
            if (distance > radiusMeters) return@mapNotNull null
            NearbyMapSign(sign.id, sign.kind, p, distance.toInt(), sign.speedLimitKmh)
        }.sortedBy { it.distanceMeters }.distinctBy { it.id }
    }

    fun findAhead(
        car: RoadCoordinate,
        headingDegrees: Double?,
        matchedRoadNames: List<String>,
        reliableMatch: Boolean,
        signs: List<RoadSign>,
    ): UpcomingRoadSign? {
        if (!reliableMatch || headingDegrees == null || !headingDegrees.isFinite()) return null
        val names = matchedRoadNames.map(::normalize).filter(String::isNotEmpty).toSet()
        if (names.isEmpty()) return null
        return signs.mapNotNull { sign ->
            if (normalize(sign.roadName) !in names ||
                sign.direction !in setOf("forward", "backward") ||
                sign.roadPoints.size < 2 ||
                sign.roadPoints.size != sign.roadNodeIds.size
            ) return@mapNotNull null

            val signIndex = sign.roadNodeIds.indexOf(sign.id)
            if (signIndex == -1) return@mapNotNull null

            val eastScale = EARTH_METERS_PER_DEGREE * cos(car.latitude * PI / 180.0)
            val points = sign.roadPoints.map {
                Pair((it.longitude - car.longitude) * eastScale,
                    (it.latitude - car.latitude) * EARTH_METERS_PER_DEGREE)
            }
            val along = DoubleArray(points.size)
            for (i in 1 until points.size) {
                along[i] = along[i - 1] +
                    hypot(points[i].first - points[i - 1].first,
                        points[i].second - points[i - 1].second)
            }
            var nearest = Double.POSITIVE_INFINITY
            var carAlong = 0.0
            var segmentHeading = 0.0
            for (i in 0 until points.lastIndex) {
                val (ax, ay) = points[i]
                val (bx, by) = points[i + 1]
                val dx = bx - ax
                val dy = by - ay
                val lenSq = dx * dx + dy * dy
                if (lenSq < 1.0) continue
                val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
                val dist = hypot(ax + t * dx, ay + t * dy)
                if (dist < nearest) {
                    nearest = dist
                    carAlong = along[i] + t * kotlin.math.sqrt(lenSq)
                    segmentHeading = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                }
            }
            if (nearest > 16.0) return@mapNotNull null

            val forward = sign.direction == "forward"
            val travelHeading = (segmentHeading + if (forward) 0.0 else 180.0) % 360.0
            val difference = abs(((travelHeading - headingDegrees + 540.0) % 360.0) - 180.0)
            if (difference > 30.0) return@mapNotNull null

            val signedDistance = (along[signIndex] - carAlong) * (if (forward) 1.0 else -1.0)
            if (signedDistance < 25.0 || signedDistance > 400.0) return@mapNotNull null
            UpcomingRoadSign(sign.id, sign.kind, signedDistance.toInt())
        }.minByOrNull { it.distanceMeters }
    }

    private fun normalize(name: String): String =
        name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
