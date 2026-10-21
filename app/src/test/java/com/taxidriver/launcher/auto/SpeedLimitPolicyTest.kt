package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitPolicyTest {
    @Test fun matchesKnownVietnamSpeedLimit() {
        val policy = SpeedLimitPolicy()
        policy.onMatchedLimit(60, RoadSpeedUnit.KMH, true, true, 1000L)
        assertEquals(60, policy.limitAt(1300L))
    }

    @Test fun mphIsConvertedToKmh() {
        val policy = SpeedLimitPolicy()
        policy.onMatchedLimit(30, RoadSpeedUnit.MPH, true, true, 1000L)
        assertEquals(48, policy.limitAt(1001L))
    }

    @Test fun unsupportedAndInvalidDataStayUnknown() {
        val policy = SpeedLimitPolicy()
        policy.onMatchedLimit(null, RoadSpeedUnit.KMH, true, true, 1000L)
        assertNull(policy.limitAt(1001L))
        policy.onMatchedLimit(60, RoadSpeedUnit.UNKNOWN, true, true, 2000L)
        assertNull(policy.limitAt(2001L))
        policy.onMatchedLimit(0, RoadSpeedUnit.KMH, true, true, 3000L)
        assertNull(policy.limitAt(3001L))
    }

    @Test fun offRoadOrDegradedMatchingClearsLimit() {
        val policy = SpeedLimitPolicy()
        policy.onMatchedLimit(60, RoadSpeedUnit.KMH, true, true, 1000L)
        policy.onMatchedLimit(60, RoadSpeedUnit.KMH, false, true, 2000L)
        assertNull(policy.limitAt(2001L))
        policy.onMatchedLimit(60, RoadSpeedUnit.KMH, true, false, 3000L)
        assertNull(policy.limitAt(3001L))
    }

    @Test fun speedLimitExpiresWithoutRecentMatching() {
        val policy = SpeedLimitPolicy()
        policy.onMatchedLimit(50, RoadSpeedUnit.KMH, true, true, 1000L)
        assertEquals(50, policy.limitAt(7000L))
        assertNull(policy.limitAt(7001L))
    }

    @Test fun overLimitNeedsBothValidSpeedAndKnownRoadLimit() {
        val policy = SpeedLimitPolicy()
        assertFalse(policy.isOverLimit(70, 1000L))
        policy.onMatchedLimit(50, RoadSpeedUnit.KMH, true, true, 1000L)
        assertFalse(policy.isOverLimit(null, 1001L))
        assertFalse(policy.isOverLimit(53, 1001L))
        assertTrue(policy.isOverLimit(55, 1001L))
        policy.clear()
        assertNull(policy.limitAt(1001L))
        assertFalse(policy.isOverLimit(80, 1001L))
    }
}
