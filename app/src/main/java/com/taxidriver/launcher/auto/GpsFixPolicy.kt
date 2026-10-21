package com.taxidriver.launcher.auto

/** Pure validation, shared by the Android location callback and JVM tests. */
internal object GpsFixPolicy {
    fun isAcceptable(
        accuracyMeters: Float?,
        latitude: Double,
        longitude: Double,
        ageMillis: Long,
        deltaSeconds: Double?,
        distanceMeters: Float?,
    ): Boolean {
        if (accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters < 0f ||
            accuracyMeters > 100f
        ) return false
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) return false
        if (ageMillis !in 0..15_000L) return false
        if (deltaSeconds != null) {
            if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0) return false
            if (distanceMeters == null || distanceMeters / deltaSeconds >= 75.0) return false
        }
        return true
    }
}
