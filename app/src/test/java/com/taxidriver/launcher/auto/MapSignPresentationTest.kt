package com.taxidriver.launcher.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSignPresentationTest {
    private fun node(id: Long, x: Double, y: Double, distance: Int) =
        ProjectedMapSign(
            NearbyMapSign(id, RoadSignKind.OTHER, RoadCoordinate(21.0, 106.0), distance),
            x, y,
        )

    @Test fun onlyRealProjectedSignsInsideDisplayAreEligible() {
        val candidates = listOf(
            node(1, 250.0, 260.0, 90),
            node(2, -100.0, 200.0, 100),
            node(3, 320.0, 690.0, 110),
            node(4, Double.NaN, 230.0, 120),
        )
        val inView = MapSignPresentation.inView(candidates, 720.0, 800.0)
        assertEquals(listOf(1L), inView.map { it.sign.id })
    }

    @Test fun prioritizeNearestFiveButDoNotMoveCoordinates() {
        val candidates = (1L..8L).map {
            node(it, 120.0 + it * 110.0, 220.0, (it * 40).toInt())
        }
        val chosen = MapSignPresentation.display(candidates)
        assertEquals((1L..5L).toList(), chosen.map { it.sign.id })
        assertEquals(candidates[0].x, chosen.first().x, 0.0)
    }

    @Test fun nearbySymbolsDoNotPileUp() {
        val chosen = MapSignPresentation.display(listOf(
            node(1, 200.0, 240.0, 50),
            node(2, 220.0, 240.0, 70),
            node(3, 370.0, 250.0, 100),
        ))
        assertEquals(listOf(1L, 3L), chosen.map { it.sign.id })
    }

    @Test fun emptyMapOrInvalidSizeShowsNoIcons() {
        assertTrue(MapSignPresentation.inView(listOf(node(1, 200.0, 200.0, 10)), 0.0, 400.0).isEmpty())
        assertTrue(MapSignPresentation.display(emptyList()).isEmpty())
    }
}
