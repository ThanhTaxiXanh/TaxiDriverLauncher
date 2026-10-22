package com.taxidriver.launcher.auto

import kotlin.math.abs

/** Candidate geometry is derived from *tagged* OSM ways, not road class rules. */
internal data class OsmSpeedCandidate(
    val wayId: Long,
    val distanceMeters: Double,
    val headingDegrees: Double,
    val roadName: String,
    val forwardLimit: Int?,
    val backwardLimit: Int?,
    val undirectedLimit: Int?,
)

internal object SpeedGeometryMatcher {
    private fun names(roadNames: List<String>): Set<String> =
        roadNames.map {
            it.trim().lowercase()
                .removePrefix("đường ")
                .removePrefix("phố ")
                .replace(Regex("\\s+"), " ")
        }.filter(String::isNotBlank).toSet()

    private fun directionLimit(candidate: OsmSpeedCandidate, bearing: Double): Int? {
        val relative = ((candidate.headingDegrees - bearing + 540.0) % 360.0) - 180.0
        val forward = abs(relative) <= 30.0
        val backward = abs(relative) >= 150.0
        if (!forward && !backward) return null
        return (if (forward) candidate.forwardLimit else candidate.backwardLimit)
            ?: candidate.undirectedLimit
    }

    /** Accept either confirmed same-road matching or very strict unambiguous GPS geometry. */
    fun match(
        candidates: List<OsmSpeedCandidate>,
        roadNames: List<String>,
        bearingDegrees: Double?,
        accuracyMeters: Float?,
    ): SpeedLimitMatch {
        if (bearingDegrees == null || !bearingDegrees.isFinite()) {
            return SpeedLimitMatch(null, SpeedLimitDiagnostic.HEADING_MISSING)
        }
        val close = candidates.filter {
            it.distanceMeters.isFinite() && it.distanceMeters in 0.0..28.0
        }
        if (close.isEmpty()) return SpeedLimitMatch(null, SpeedLimitDiagnostic.NO_TAGGED_WAY)
        val tagged = close.mapNotNull { candidate ->
            val limit = directionLimit(candidate, bearingDegrees)
            if (limit == null || limit !in 5..200) null else candidate to limit
        }
        if (tagged.isEmpty()) {
            return SpeedLimitMatch(null, SpeedLimitDiagnostic.DIRECTION_UNCERTAIN)
        }
        val named = names(roadNames)
        val namedMatches = tagged.filter { (c, _) ->
            c.roadName.trim().lowercase().removePrefix("đường ")
                .removePrefix("phố ") in named
        }.filter { (c, _) -> c.distanceMeters <= 24.0 }
        if (namedMatches.isNotEmpty()) {
            val closest = namedMatches.minOf { it.first.distanceMeters }
            val plausible = namedMatches.filter { it.first.distanceMeters <= closest + 6.0 }
                .map { it.second }.toSet()
            return if (plausible.size == 1) {
                SpeedLimitMatch(plausible.first(), SpeedLimitDiagnostic.VERIFIED)
            } else SpeedLimitMatch(null, SpeedLimitDiagnostic.CONFLICT)
        }
        // Fail closed if GPS horizontal accuracy is too weak to disambiguate roads.
        if (accuracyMeters == null || accuracyMeters > 12f || accuracyMeters <= 0f) {
            return SpeedLimitMatch(null, SpeedLimitDiagnostic.ROAD_UNMATCHED)
        }
        val nearest = tagged.minByOrNull { it.first.distanceMeters }
            ?: return SpeedLimitMatch(null, SpeedLimitDiagnostic.NO_TAGGED_WAY)
        if (nearest.first.distanceMeters > 10.0) {
            return SpeedLimitMatch(null, SpeedLimitDiagnostic.TOO_FAR)
        }
        // A crossing, service road or parallel lane with a different OSM way
        // cannot be guessed. Refuse if any other aligned tagged way is nearby.
        val ambiguous = tagged.any {
            it.first.wayId != nearest.first.wayId &&
                it.first.distanceMeters <= nearest.first.distanceMeters + 12.0
        }
        if (ambiguous) {
            return SpeedLimitMatch(null, SpeedLimitDiagnostic.GEOMETRY_AMBIGUOUS)
        }
        return SpeedLimitMatch(nearest.second, SpeedLimitDiagnostic.VERIFIED_GEOMETRY)
    }
}
