package com.kompressorlink.app.health

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingStatsTest {
    @Test
    fun matchesKnownValues() {
        val s = StreamingStats()
        listOf(3f, 4f, 5f).forEach(s::add)
        assertEquals(3, s.count)
        assertEquals(4f, s.meanValue, 1e-5f)
        assertEquals(3f, s.min, 0f)
        assertEquals(5f, s.max, 0f)
        // population stddev of [3,4,5] = sqrt(2/3)
        assertEquals(0.8165f, s.stdDev, 1e-3f)
    }

    @Test
    fun emptyAndSingleAreSafe() {
        val s = StreamingStats()
        assertEquals(0f, s.meanValue, 0f)
        assertEquals(0f, s.stdDev, 0f)
        s.add(7f)
        assertEquals(7f, s.meanValue, 0f)
        assertEquals(0f, s.stdDev, 0f)
    }
}
