package com.taxidriver.launcher.testfixtures

import com.taxidriver.launcher.data.calendar.CalendarInfo

internal fun fakeCalendarInfo(
    id: Long = 1L,
    displayName: String = "Personal",
    accountName: String = "me@example.com",
    color: Int = 0xFF4285F4.toInt(),
): CalendarInfo = CalendarInfo(id, displayName, accountName, color)
