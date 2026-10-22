package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadSignPolicyTest {
    private val coordinates = (0..5).map {
        RoadCoordinate(21.0, 105.0 + it * 0.001)
    }
    private val sign = RoadSign(
        13L, RoadSignKind.STOP, coordinates[3], "Đường Trần Phú",
        coordinates, listOf(10L, 11L, 12L, 13L, 14L, 15L),
        "forward",
    )
    private val car = RoadCoordinate(21.0, 105.0015)

    private fun find(
        pos: RoadCoordinate = car,
        heading: Double? = 90.0,
        names: List<String> = listOf("Đường Trần Phú"),
        matched: Boolean = true,
        signs: List<RoadSign> = listOf(sign),
    ) = RoadSignPolicy.findAhead(pos, heading, names, matched, signs)

    @Test fun signAheadOnMatchedWayWithCorrectDirection() {
        val result = find()
        assertEquals(13L, result?.id)
        assertEquals(RoadSignKind.STOP, result?.kind)
        // Longitude spacing at latitude 21 degrees is about 104m.
        assertEquals(true, result!!.distanceMeters in 130..170)
    }

    @Test fun oppositeOrMissingHeadingNeverWarns() {
        assertNull(find(heading = 270.0))
        assertNull(find(heading = null))
    }

    @Test fun unmatchedRoadOrUnreliableMapMatchNeverWarns() {
        assertNull(find(names = listOf("Đường Lê Lợi")))
        assertNull(find(matched = false))
        assertNull(find(names = emptyList()))
    }

    @Test fun signAlreadyPassedIsNotShown() {
        assertNull(find(pos = RoadCoordinate(21.0, 105.004)))
    }

    @Test fun signWithoutExplicitDirectionIsNotShown() {
        assertNull(find(signs = listOf(sign.copy(direction = ""))))
        assertNull(find(signs = listOf(sign.copy(direction = "both"))))
    }

    @Test fun signWithoutRoadMembershipIsNotShown() {
        assertNull(find(signs = listOf(sign.copy(id = 99L))))
    }

    @Test fun nearbyParallelRoadIsNotEnough() {
        assertNull(find(pos = RoadCoordinate(21.00025, 105.0015)))
    }

    @Test fun reverseDirectionCanBeMatchedOnlyWhenExplicitlyTagged() {
        val backwards = sign.copy(
            id = 11L, position = coordinates[1], direction = "backward",
        )
        assertEquals(11L, find(pos = car, heading = 270.0, signs = listOf(backwards))?.id)
    }

    @Test fun signFartherThan400MetersIsNotShown() {
        val longer = (0..9).map { RoadCoordinate(21.0, 105.0 + it * 0.001) }
        val farther = sign.copy(
            id = 19L, position = longer[9],
            roadPoints = longer,
            roadNodeIds = (10L..19L).toList(),
        )
        assertNull(find(signs = listOf(farther)))
    }
    @Test fun mappedSignWithin500MetersDoesNotNeedHeadingOrRoadMatch() {
        val node = OsmMapSign(99L, RoadSignKind.STOP, coordinates[4])
        val visible = RoadSignPolicy.visibleWithinRadius(car, listOf(node))
        assertEquals(1, visible.size)
        assertEquals(99L, visible.first().id)
        assertEquals(true, visible.first().distanceMeters in 240..280)
    }

    @Test fun mappedSignBeyond500MetersOrInvalidCoordinateIsExcluded() {
        val far = OsmMapSign(99L, RoadSignKind.STOP, coordinates[9.coerceAtMost(5)].copy(
            longitude = 105.01,
        ))
        val invalid = OsmMapSign(100L, RoadSignKind.STOP, RoadCoordinate(Double.NaN, 105.0))
        assertEquals(0, RoadSignPolicy.visibleWithinRadius(car, listOf(far, invalid)).size)
    }

    @Test fun pinsUseUniqueOsmIdsAndNearestFirst() {
        val nodes = listOf(
            OsmMapSign(42L, RoadSignKind.STOP, coordinates[4]),
            OsmMapSign(41L, RoadSignKind.RAILWAY, coordinates[2]),
            OsmMapSign(42L, RoadSignKind.STOP, coordinates[4]),
        )
        val visible = RoadSignPolicy.visibleWithinRadius(car, nodes)
        assertEquals(listOf(41L, 42L), visible.map { it.id })
    }

    @Test fun malformedRadiusHasNoPins() {
        val node = OsmMapSign(5L, RoadSignKind.GIVE_WAY, car)
        assertEquals(0, RoadSignPolicy.visibleWithinRadius(car, listOf(node), -1.0).size)
        assertEquals(0, RoadSignPolicy.visibleWithinRadius(car, listOf(node), Double.NaN).size)
    }

}
