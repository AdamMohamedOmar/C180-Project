package com.kompressorlink.app.ui.components

import com.kompressorlink.app.telemetry.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentsLogicTest {
    @Test
    fun bandBar_zoneOccupiesMiddleHalf() {
        // Band 3..5 (w=1): track 2..6; zone = 0.25..0.75.
        val f = bandBarFractions(value = 4f, lo = 3f, hi = 5f)
        assertEquals(0.25f, f.zoneStart, 1e-4f)
        assertEquals(0.75f, f.zoneEnd, 1e-4f)
        assertEquals(0.5f, f.marker, 1e-4f)
    }

    @Test
    fun bandBar_markerClampsAtTrackEnds() {
        assertEquals(1f, bandBarFractions(99f, 3f, 5f).marker, 0f)
        assertEquals(0f, bandBarFractions(-99f, 3f, 5f).marker, 0f)
    }

    @Test
    fun bandBar_degenerateBand_doesNotProduceNaN() {
        val f = bandBarFractions(value = 5f, lo = 5f, hi = 5f)
        assertEquals(0.5f, f.zoneStart, 0f)
        assertEquals(0.5f, f.zoneEnd, 0f)
        assertEquals(0.5f, f.marker, 0f)
    }

    @Test
    fun bannerText_coversEveryState() {
        assertEquals("Simulated · healthy",
            connectionBannerText(ConnectionState.Simulated("healthy")))
        assertEquals("Connected · K-line up",
            connectionBannerText(ConnectionState.Ready(demo = false, klineConnected = true)))
        assertEquals("Connected · demo device",
            connectionBannerText(ConnectionState.Ready(demo = true, klineConnected = false)))
        assertEquals("Disconnected", connectionBannerText(ConnectionState.Disconnected))
        assertEquals("No device paired yet", connectionBannerText(ConnectionState.NotAssociated))
        assertEquals("Connecting…", connectionBannerText(ConnectionState.Connecting))
    }
}
