package com.kompressorlink.app.sync

import com.kompressorlink.app.telemetry.Signal

// Parser for the firmware's long-format ride CSV (ride_logger.cpp is the
// writer; this stays tolerant where that is strict). Torn tails and junk
// rows are DROPPED, never fatal — a power cut can end a file mid-line and
// the grad project's malformed-CSV incident is why tolerance is a rule
// (PLAN.md §2).
object RideCsv {

    data class Row(val tMs: Long, val signal: Signal, val value: Float)

    data class Parsed(
        val fwVersion: String?,
        val initMode: String?,
        val storedDtcs: List<String>,
        val pendingDtcs: List<String>,
        /** t_ms-at-marker to epoch_ms-at-marker, if the phone ever synced time. */
        val timeSync: Pair<Long, Long>?,
        val rows: List<Row>,
    )

    fun parse(bytes: ByteArray): Parsed {
        var fw: String? = null
        var init: String? = null
        var stored = emptyList<String>()
        var pending = emptyList<String>()
        var timeSync: Pair<Long, Long>? = null
        val rows = ArrayList<Row>()
        val names = Signal.entries.associateBy { it.name }

        for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#")) {
                val body = l.substring(1)
                val eq = body.indexOf('=')
                if (eq <= 0) continue
                val key = body.substring(0, eq)
                val value = body.substring(eq + 1)
                when (key) {
                    "fw_version" -> fw = value
                    "init_mode" -> init = value
                    "dtc_stored" -> stored = splitCodes(value)
                    "dtc_pending" -> pending = splitCodes(value)
                    "time_sync" -> {
                        val parts = value.split(':')
                        if (parts.size == 2) {
                            val t = parts[0].toLongOrNull()
                            val epoch = parts[1].toLongOrNull()
                            if (t != null && epoch != null) timeSync = t to epoch
                        }
                    }
                    // "dropped" and future markers: ignored by design
                }
                continue
            }
            val parts = l.split(',')
            if (parts.size != 3) continue          // junk, torn tail
            val t = parts[0].toLongOrNull() ?: continue   // also drops the "t_ms,signal,value" column-header line (non-numeric first field)
            val signal = names[parts[1]] ?: continue
            val v = parts[2].toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
            rows.add(Row(t, signal, v))
        }
        return Parsed(fw, init, stored, pending, timeSync, rows)
    }

    private fun splitCodes(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
