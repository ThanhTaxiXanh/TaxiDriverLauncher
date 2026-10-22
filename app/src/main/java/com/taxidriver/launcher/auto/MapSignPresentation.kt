package com.taxidriver.launcher.auto

import kotlin.math.hypot

/** Screen-space choices only; positions are projected from actual OSM coordinates. */
internal data class ProjectedMapSign(
    val sign: NearbyMapSign,
    val x: Double,
    val y: Double,
)

internal object MapSignPresentation {
    /** On-screen candidates, excluding the lower HUD edge and upper header. */
    fun inView(
        candidates: List<ProjectedMapSign>,
        width: Double,
        height: Double,
    ): List<ProjectedMapSign> {
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) {
            return emptyList()
        }
        return candidates.filter {
            it.x.isFinite() && it.y.isFinite() &&
                it.x >= 24.0 && it.x <= width - 24.0 &&
                it.y >= 110.0 && it.y <= height - 115.0
        }
    }

    /** Prevent icon pileups: prefer nearby real signs; never move their pins. */
    fun display(
        visible: List<ProjectedMapSign>,
        maxItems: Int = 5,
        separationPx: Double = 100.0,
        preferredId: Long? = null,
    ): List<ProjectedMapSign> {
        if (maxItems <= 0) return emptyList()
        val chosen = mutableListOf<ProjectedMapSign>()
        for (candidate in visible.sortedWith(
            compareBy<ProjectedMapSign> { it.sign.id != preferredId }
                .thenBy { it.sign.distanceMeters }.thenBy { it.sign.id },
        )) {
            if (chosen.any { hypot(it.x - candidate.x, it.y - candidate.y) < separationPx }) {
                continue
            }
            chosen.add(candidate)
            if (chosen.size == maxItems) break
        }
        return chosen
    }
}
