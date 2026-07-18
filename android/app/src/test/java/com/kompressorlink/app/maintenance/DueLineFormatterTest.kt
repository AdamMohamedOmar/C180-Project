package com.kompressorlink.app.maintenance

import org.junit.Assert.assertEquals
import org.junit.Test

class DueLineFormatterTest {
    @Test
    fun formats_everyState() {
        assertEquals("When did you last do this?",
            formatDueLine(DueInfo(DueState.NEVER_LOGGED, null, null, kmUntracked = true)))
        assertEquals("Enter your odometer to activate",
            formatDueLine(DueInfo(DueState.KM_UNTRACKED, null, null, kmUntracked = true)))
        assertEquals("Overdue by 1000 km",
            formatDueLine(DueInfo(DueState.OVERDUE, -1000, null, kmUntracked = false)))
        assertEquals("Overdue by 1000 km · 45 days",
            formatDueLine(DueInfo(DueState.OVERDUE, -1000, -45, kmUntracked = false)))
        assertEquals("450 km or 19 days left",
            formatDueLine(DueInfo(DueState.DUE_SOON, 450, 19, kmUntracked = false)))
        assertEquals("5500 km left",
            formatDueLine(DueInfo(DueState.OK, 5500, null, kmUntracked = false)))
    }

    @Test
    fun mixedSigns_showOnlyTheOverdueDimension() {
        // km overdue but date still fine: state OVERDUE, only km listed.
        assertEquals("Overdue by 200 km",
            formatDueLine(DueInfo(DueState.OVERDUE, -200, 90, kmUntracked = false)))
    }
}
