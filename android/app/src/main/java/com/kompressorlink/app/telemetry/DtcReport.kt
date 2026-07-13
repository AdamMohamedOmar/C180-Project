package com.kompressorlink.app.telemetry

data class DtcReport(
    val stored: List<String>,   // e.g. ["P0171"], decoded from SAE J2012 bytes
    val pending: List<String>,
)
