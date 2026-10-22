package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedLimitDiagnosticTest {
    @Test fun dataAbsentIsNeverTreatedAsKnownLimit() {
        val state = SpeedLimitDiagnosticPolicy.classify(0, 0, 0, 0, emptySet())
        assertNull(state.limitKmh)
        assertEquals(SpeedLimitDiagnostic.NO_TAGGED_WAY, state.reason)
    }

    @Test fun roadNameAndHeadingMustMatch() {
        assertEquals(
            SpeedLimitDiagnostic.ROAD_NAME_MISMATCH,
            SpeedLimitDiagnosticPolicy.classify(3, 0, 0, 0, emptySet()).reason,
        )
        assertEquals(
            SpeedLimitDiagnostic.TOO_FAR,
            SpeedLimitDiagnosticPolicy.classify(3, 1, 0, 0, emptySet()).reason,
        )
        assertEquals(
            SpeedLimitDiagnostic.DIRECTION_UNCERTAIN,
            SpeedLimitDiagnosticPolicy.classify(3, 1, 1, 0, emptySet()).reason,
        )
    }

    @Test fun conflictingOsmLimitsAreSuppressed() {
        val state = SpeedLimitDiagnosticPolicy.classify(3, 3, 2, 2, setOf(50, 60))
        assertNull(state.limitKmh)
        assertEquals(SpeedLimitDiagnostic.CONFLICT, state.reason)
    }

    @Test fun oneVerifiedValueMayBeUsed() {
        val state = SpeedLimitDiagnosticPolicy.classify(3, 2, 2, 1, setOf(60))
        assertEquals(60, state.limitKmh)
        assertEquals(SpeedLimitDiagnostic.VERIFIED, state.reason)
    }
}
