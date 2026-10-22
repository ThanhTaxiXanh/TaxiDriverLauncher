package com.taxidriver.launcher.auto

/** Explains why tagged OSM speed data cannot be applied to this vehicle. */
internal enum class SpeedLimitDiagnostic(val vietnamese: String) {
    CACHE_PENDING("CHƯA NẠP DỮ LIỆU"),
    ROAD_UNMATCHED("CHƯA KHỚP ĐƯỜNG"),
    HEADING_MISSING("CHƯA CÓ HƯỚNG"),
    NO_TAGGED_WAY("OSM: THIẾU MAXSPEED"),
    ROAD_NAME_MISMATCH("KHÔNG KHỚP TÊN ĐƯỜNG"),
    TOO_FAR("ĐƯỜNG CÁCH XA"),
    DIRECTION_UNCERTAIN("HƯỚNG CHƯA RÕ"),
    CONFLICT("GIỚI HẠN MÂU THUẪN"),
    VERIFIED("OSM ĐÃ XÁC MINH"),
}

internal data class SpeedLimitMatch(
    val limitKmh: Int?,
    val reason: SpeedLimitDiagnostic,
)

internal object SpeedLimitDiagnosticPolicy {
    fun classify(
        taggedSegments: Int,
        namedSegments: Int,
        closeSegments: Int,
        alignedSegments: Int,
        distinctLimits: Set<Int>,
    ): SpeedLimitMatch {
        return when {
            taggedSegments == 0 -> SpeedLimitMatch(null, SpeedLimitDiagnostic.NO_TAGGED_WAY)
            namedSegments == 0 -> SpeedLimitMatch(null, SpeedLimitDiagnostic.ROAD_NAME_MISMATCH)
            closeSegments == 0 -> SpeedLimitMatch(null, SpeedLimitDiagnostic.TOO_FAR)
            alignedSegments == 0 -> SpeedLimitMatch(null, SpeedLimitDiagnostic.DIRECTION_UNCERTAIN)
            distinctLimits.size != 1 -> SpeedLimitMatch(null, SpeedLimitDiagnostic.CONFLICT)
            else -> SpeedLimitMatch(distinctLimits.first(), SpeedLimitDiagnostic.VERIFIED)
        }
    }
}
