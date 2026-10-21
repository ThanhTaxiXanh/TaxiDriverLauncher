package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsSpeedFilterTest {
    @Test fun firstValidFixUsesKilometersPerHour() {
        val filter = GpsSpeedFilter()
        filter.accept(10f, 0.7f, 1_000L)
        assertEquals(36, filter.kmh(1_100L))
    }

    @Test fun stoppedVehicleSnapsToZero() {
        val filter = GpsSpeedFilter()
        filter.accept(0.6f, null, 1_000L)
        assertEquals(0, filter.kmh(1_100L))
    }

    @Test fun movingSpeedIsSmoothed() {
        val filter = GpsSpeedFilter()
        filter.accept(10f, null, 1_000L)
        filter.accept(20f, null, 2_000L)
        assertEquals(49, filter.kmh(2_100L))
    }

    @Test fun oldSpeedIsUnknownNotZero() {
        val filter = GpsSpeedFilter()
        filter.accept(10f, null, 1_000L)
        assertNull(filter.kmh(4_501L))
        assertNull(filter.kmh(0L))
    }

    @Test fun missingOrInaccurateSpeedDoesNotRenewLastFix() {
        val filter = GpsSpeedFilter()
        filter.accept(10f, null, 1_000L)
        filter.accept(null, null, 2_000L)
        filter.accept(20f, 15f, 3_000L)
        assertEquals(36, filter.kmh(4_000L))
        assertNull(filter.kmh(4_501L))
    }

    @Test fun rejectsOutOfOrderAndImplausibleSamples() {
        val filter = GpsSpeedFilter()
        filter.accept(10f, null, 2_000L)
        filter.accept(20f, null, 1_000L)
        filter.accept(90f, null, 3_000L)
        filter.accept(Float.NaN, null, 4_000L)
        assertEquals(36, filter.kmh(4_001L))
    }

    @Test fun resetClearsPreviousSpeed() {
        val filter = GpsSpeedFilter()
        filter.accept(10f, null, 1_000L)
        filter.reset()
        assertNull(filter.kmh(1_200L))
    }

    @Test fun roadInfoIsUnknownUntilVerified() {
        val info = VerifiedRoadInformation()
        assertNull(info.speedLimitKmh)
        assertNull(info.upcomingSign)
    }
}
