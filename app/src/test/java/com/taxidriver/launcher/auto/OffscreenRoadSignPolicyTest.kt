package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OffscreenRoadSignPolicyTest {
    private val car = RoadCoordinate(21.0, 106.0)
    private fun sign(id: Long, lat: Double, lon: Double, distance: Int = 150) =
        NearbyMapSign(id, RoadSignKind.OTHER, RoadCoordinate(lat, lon), distance)

    @Test fun offscreenSignsDisplayEvenWithoutMatchedRoad() {
        val signs = listOf(sign(1, 21.002, 106.0), sign(2, 21.0, 106.003))
        val result = OffscreenRoadSignPolicy.suggest(car, 0.0, signs, emptySet())
        assertEquals(2, result.size)
        assertEquals("Trước", result[0].relativeDirection)
        assertEquals("Phải", result[1].relativeDirection)
    }

    @Test fun signAlreadyVisibleOnMapIsExcludedFromExtraHud() {
        val result = OffscreenRoadSignPolicy.suggest(
            car, 0.0, listOf(sign(1, 21.002, 106.0)), setOf(1L),
        )
        assertTrue(result.isEmpty())
    }

    @Test fun signsOutside500mNeverShow() {
        val result = OffscreenRoadSignPolicy.suggest(
            car, null, listOf(sign(1, 21.01, 106.0, 501)), emptySet(),
        )
        assertTrue(result.isEmpty())
    }

    @Test fun explicitSpeedSignShowsExactValueAsReference() {
        val sign = NearbyMapSign(
            1, RoadSignKind.SPEED_LIMIT, RoadCoordinate(21.001, 106.0), 100, 60,
        )
        val result = OffscreenRoadSignPolicy.suggest(car, null, listOf(sign), emptySet())
        assertEquals("Biển 60 km/h", result[0].title)
        assertEquals("Bắc", result[0].relativeDirection)
    }

    @Test fun roadSignsNeverInventSpeedFromAnUnknownOsmCode() {
        val sign = NearbyMapSign(
            1, RoadSignKind.OTHER, RoadCoordinate(21.001, 106.0), 100,
        )
        assertEquals("Biển báo OSM",
            OffscreenRoadSignPolicy.suggest(car, null, listOf(sign), emptySet())[0].title)
    }
}
