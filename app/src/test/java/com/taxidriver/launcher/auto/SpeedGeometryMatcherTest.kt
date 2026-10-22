package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedGeometryMatcherTest {
    private fun tagged(
        id: Long = 1L, distance: Double = 4.0,
        heading: Double = 90.0, limit: Int = 60,
        name: String = "Đường Lê An",
    ) = OsmSpeedCandidate(id, distance, heading, name, null, null, limit)

    @Test fun reliableNamedRoadWorksWithModerateGpsAccuracy() {
        val r = SpeedGeometryMatcher.match(
            listOf(tagged()), listOf("Lê An"), 90.0, 17f,
        )
        assertEquals(60, r.limitKmh)
        assertEquals(SpeedLimitDiagnostic.VERIFIED, r.reason)
    }

    @Test fun unnamedRoadNeedsPreciseUnambiguousGpsFix() {
        val r = SpeedGeometryMatcher.match(
            listOf(tagged()), emptyList(), 90.0, 5f,
        )
        assertEquals(60, r.limitKmh)
        assertEquals(SpeedLimitDiagnostic.VERIFIED_GEOMETRY, r.reason)
    }

    @Test fun parallelNearbyWayIsAmbiguousAndRejected() {
        val r = SpeedGeometryMatcher.match(
            listOf(tagged(1, 4.0), tagged(2, 7.0, limit = 50)),
            emptyList(), 90.0, 4f,
        )
        assertNull(r.limitKmh)
        assertEquals(SpeedLimitDiagnostic.GEOMETRY_AMBIGUOUS, r.reason)
    }

    @Test fun inaccurateGpsCannotBypassNameMatching() {
        val r = SpeedGeometryMatcher.match(
            listOf(tagged()), emptyList(), 90.0, 21f,
        )
        assertNull(r.limitKmh)
    }

    @Test fun farOrOppositeHeadingCannotBeGuessed() {
        assertNull(SpeedGeometryMatcher.match(
            listOf(tagged(distance = 15.0)), emptyList(), 90.0, 5f,
        ).limitKmh)
        assertNull(SpeedGeometryMatcher.match(
            listOf(tagged(heading = 0.0)), emptyList(), 90.0, 5f,
        ).limitKmh)
    }

    @Test fun namedConflictingLimitsNeverSelectOneArbitrarily() {
        val r = SpeedGeometryMatcher.match(
            listOf(tagged(1, 4.0), tagged(2, 5.0, limit = 80)),
            listOf("Lê An"), 90.0, 5f,
        )
        assertEquals(SpeedLimitDiagnostic.CONFLICT, r.reason)
        assertNull(r.limitKmh)
    }

    @Test fun directionalWayTagsTakePrecedenceOverCommonTag() {
        val c = tagged().copy(
            forwardLimit = 50, backwardLimit = 70, undirectedLimit = 60,
        )
        assertEquals(50, SpeedGeometryMatcher.match(
            listOf(c), listOf("Lê An"), 90.0, 4f,
        ).limitKmh)
        assertEquals(70, SpeedGeometryMatcher.match(
            listOf(c), listOf("Lê An"), 270.0, 4f,
        ).limitKmh)
    }
    @Test fun contradictoryMapboxRoadNameBlocksGeometryFallback() {
        val r = SpeedGeometryMatcher.match(
            listOf(tagged(name = "Lê An")),
            listOf("Nguyễn Công Hãng"), 90.0, 4f,
        )
        assertNull(r.limitKmh)
        assertEquals(SpeedLimitDiagnostic.ROAD_NAME_MISMATCH, r.reason)
    }

}
