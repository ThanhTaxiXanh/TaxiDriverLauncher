package com.taxidriver.launcher.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFixPolicyTest {
    @Test fun acceptsRecentPrecisePosition() {
        assertTrue(GpsFixPolicy.isAcceptable(8f, 21.28111, 106.19255, 600, null, null))
    }

    @Test fun rejectsInvalidAndStalePosition() {
        assertFalse(GpsFixPolicy.isAcceptable(8f, 21.0, 106.0, 16_000, null, null))
        assertFalse(GpsFixPolicy.isAcceptable(120f, 21.0, 106.0, 500, null, null))
        assertFalse(GpsFixPolicy.isAcceptable(8f, 91.0, 106.0, 500, null, null))
        assertFalse(GpsFixPolicy.isAcceptable(8f, Double.NaN, 106.0, 500, null, null))
    }

    @Test fun rejectsOutOfOrderAndImpossibleJumps() {
        assertFalse(GpsFixPolicy.isAcceptable(8f, 21.0, 106.0, 500, 0.0, 1f))
        assertFalse(GpsFixPolicy.isAcceptable(8f, 21.0, 106.0, 500, 1.0, 100f))
        assertTrue(GpsFixPolicy.isAcceptable(8f, 21.0, 106.0, 500, 1.0, 10f))
    }
}
