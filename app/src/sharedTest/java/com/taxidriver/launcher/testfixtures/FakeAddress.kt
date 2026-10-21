package com.taxidriver.launcher.testfixtures

import com.taxidriver.launcher.data.geocoding.ShortAddress

internal fun fakeAddress(
    locality: String = "Shibuya",
    region: String? = "Tokyo",
    line: String = "",
): ShortAddress = ShortAddress(locality, region, line)
