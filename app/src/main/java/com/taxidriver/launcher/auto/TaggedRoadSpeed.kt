package com.taxidriver.launcher.auto

import kotlin.math.cos
import kotlin.math.hypot

/** A road-way annotation from an EXPLICIT numeric OSM maxspeed tag.
 * Informational visualization of the tagged segment, not the current car limit.
 */
internal data class TaggedRoadSpeed(
    val wayId: Long,
    val name: String,
    val limitKmh: Int,
    val coordinate: RoadCoordinate,
    val geometry: List<RoadCoordinate>,
)

internal object TaggedRoadSpeedPolicy {
    /** A single map icon is safe only when the value applies both ways.
     * Direction-dependent limits are intentionally skipped on the overview.
     */
    fun commonLimit(main: Int?, forward: Int?, backward: Int?): Int? {
        val limit = when {
            forward != null && backward != null -> if (forward == backward) forward else null
            forward != null || backward != null -> null
            else -> main
        }
        return limit?.takeIf { it in 5..200 }
    }

    /** Project onto OSM polyline, so a marker is always attached to tagged geometry. */
    fun nearestRoadPoint(
        vehicle: RoadCoordinate,
        geometry: List<RoadCoordinate>,
    ): Pair<RoadCoordinate, Double>? {
        if (geometry.size < 2 || !vehicle.latitude.isFinite() || !vehicle.longitude.isFinite()) {
            return null
        }
        val eastMeters = 111_320.0 * cos(Math.toRadians(vehicle.latitude))
        var nearest = Double.POSITIVE_INFINITY
        var projected: RoadCoordinate? = null
        for (i in 0 until geometry.size - 1) {
            val a = geometry[i]
            val b = geometry[i + 1]
            if (!a.latitude.isFinite() || !a.longitude.isFinite() ||
                !b.latitude.isFinite() || !b.longitude.isFinite()
            ) continue
            val ax = (a.longitude - vehicle.longitude) * eastMeters
            val ay = (a.latitude - vehicle.latitude) * 111_320.0
            val bx = (b.longitude - vehicle.longitude) * eastMeters
            val by = (b.latitude - vehicle.latitude) * 111_320.0
            val dx = bx - ax
            val dy = by - ay
            val sq = dx * dx + dy * dy
            if (sq < 1.0) continue
            val t = ((-ax * dx - ay * dy) / sq).coerceIn(0.0, 1.0)
            val dist = hypot(ax + dx * t, ay + dy * t)
            if (dist < nearest) {
                nearest = dist
                projected = RoadCoordinate(
                    a.latitude + (b.latitude - a.latitude) * t,
                    a.longitude + (b.longitude - a.longitude) * t,
                )
            }
        }
        return projected?.let { it to nearest }
    }

    /** Favor closest tagged ways, but map-space anti-overlap is applied later. */
    fun bounded(
        vehicle: RoadCoordinate,
        tagged: List<TaggedRoadSpeed>,
        radiusMeters: Double = 650.0,
        maxWays: Int = 65,
    ): List<TaggedRoadSpeed> =
        tagged.mapNotNull { way ->
            val nearest = nearestRoadPoint(vehicle, way.geometry) ?: return@mapNotNull null
            if (nearest.second > radiusMeters) null else Triple(way, nearest.first, nearest.second)
        }.sortedWith(compareBy<Triple<TaggedRoadSpeed, RoadCoordinate, Double>> { it.third }
            .thenBy { it.first.wayId })
            .distinctBy { it.first.wayId }
            .take(maxWays)
            .map { (way, point, _) -> way.copy(coordinate = point) }
}
