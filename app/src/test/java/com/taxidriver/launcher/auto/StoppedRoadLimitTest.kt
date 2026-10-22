package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoppedRoadLimitTest {
    private val policy = StoppedRoadLimit()

    private fun remember() {
        policy.remember(50, 21.0, 106.0, listOf("Đường Lê An"), true, true, 1_000L)
    }

    private fun held(now: Long = 20_000L, speed: Int? = 0,
                     latitude: Double = 21.0, roads: List<String> = emptyList(),
                     reliable: Boolean = false, age: Long = 500L) =
        policy.atStop(latitude, 106.0, speed, 5f, roads, reliable, age, now)

    @Test fun holdsVerifiedRoadLimitAtSamePositionWhenStopped() {
        remember()
        assertEquals(50, held())
    }

    @Test fun doesNotInferNeverVerifiedLimit() {
        assertNull(held())
        policy.remember(60, 21.0, 106.0, emptyList(), true, true, 1000L)
        assertNull(held())
    }

    @Test fun expiresAndDoesNotKeepLimitWhenVehicleMoves() {
        remember()
        assertNull(held(now = 92_000L))
        assertNull(held(speed = 20))
        assertNull(held(latitude = 21.001))
    }

    @Test fun mismatchedReliableRoadClearsPreviouslyHeldLimit() {
        remember()
        assertNull(held(roads = listOf("Phố Trần Phú"), reliable = true))
        assertNull(held())
    }

    @Test fun sameRoadWithVariantPrefixIsAccepted() {
        remember()
        assertEquals(50, held(roads = listOf("Lê An"), reliable = true))
    }

    @Test fun GPSAccuracyAndStaleFixCannotMaintainLimit() {
        remember()
        assertNull(policy.atStop(21.0, 106.0, 0, 40f, emptyList(), false, 500, 20_000L))
        assertNull(held(age = 4_000L))
    }

    @Test fun invalidSourceIsNotMemorizedAndClearWorks() {
        policy.remember(50, 21.0, 106.0, listOf("Lê An"), false, true, 1_000L)
        assertNull(held())
        remember()
        policy.clear()
        assertNull(held())
    }
}
