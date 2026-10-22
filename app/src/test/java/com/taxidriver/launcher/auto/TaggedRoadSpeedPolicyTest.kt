package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaggedRoadSpeedPolicyTest {
    private val car = RoadCoordinate(21.0, 106.0)
    private val geometry = listOf(
        RoadCoordinate(21.0, 105.999),
        RoadCoordinate(21.0, 106.002),
    )

    @Test fun untaggedRoadNeverDisplaysNumericLimit() {
        assertNull(TaggedRoadSpeedPolicy.commonLimit(null, null, null))
    }

    @Test fun unequalDirectionalLimitsAreNotFlattened() {
        assertNull(TaggedRoadSpeedPolicy.commonLimit(60, 50, 80))
        assertNull(TaggedRoadSpeedPolicy.commonLimit(60, 50, null))
    }

    @Test fun sameBothDirectionsAndExplicitMainAreAllowed() {
        assertEquals(60, TaggedRoadSpeedPolicy.commonLimit(60, null, null))
        assertEquals(50, TaggedRoadSpeedPolicy.commonLimit(60, 50, 50))
        assertNull(TaggedRoadSpeedPolicy.commonLimit(500, null, null))
    }

    @Test fun nearestPointIsOnTheOsmlTaggedRoad() {
        val result = TaggedRoadSpeedPolicy.nearestRoadPoint(
            RoadCoordinate(21.0001, 106.0004), geometry,
        )!!
        assertEquals(21.0, result.first.latitude, 0.000001)
        assertEquals(106.0004, result.first.longitude, 0.000001)
        assertTrue(result.second in 9.0..13.0)
    }

    @Test fun filterOutFarRoadsAndDeduplicateWays() {
        val near = TaggedRoadSpeed(42, "Lê An", 50, car, geometry)
        val far = TaggedRoadSpeed(43, "Xa", 60, car, listOf(
            RoadCoordinate(21.02, 106.0), RoadCoordinate(21.021, 106.0),
        ))
        val selected = TaggedRoadSpeedPolicy.bounded(car, listOf(near, far, near))
        assertEquals(listOf(42L), selected.map { it.wayId })
    }
}
