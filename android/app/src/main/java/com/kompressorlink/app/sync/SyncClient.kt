package com.kompressorlink.app.sync

import com.kompressorlink.app.data.db.RideFileDao
import com.kompressorlink.app.data.db.RideFileEntity
import com.kompressorlink.app.data.db.RideFileStatus
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// docs/wifi_sync_protocol.md v1 client. Resume-safe by construction: the
// Room row's downloadedBytes advances only after bytes hit disk, so any
// interruption (WiFi drop, app death) resumes at the exact byte. CRC-32
// verification uses java.util.zip.CRC32 — bit-identical to the firmware's
// CRC-32/ISO-HDLC.
class SyncClient(
    private val baseUrl: String,                        // e.g. "http://192.168.4.1"
    private val open: (URL) -> HttpURLConnection,       // prod: network.openConnection
    private val dao: RideFileDao,
    private val ridesDir: File,
    private val nowEpochMs: () -> Long,
) {
    companion object {
        const val CHUNK_BYTES = 4096      // the firmware's kSyncMaxChunk
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000
    }

    data class ManifestEntry(val name: String, val size: Long, val crc32: String)

    // Names that have already been wiped once after a CRC mismatch, for the
    // lifetime of this SyncClient instance. A second mismatch for a name
    // already in this set is terminal (FAILED) rather than another PENDING
    // retry. Deliberately in-memory only (matches the entity schema, which
    // has no persistent mismatch counter): a process restart forgets it,
    // so a file that was wiped-and-PENDING right before a crash gets one
    // more free retry after restart. Acceptable — the schema has no field
    // to persist this, and it only widens the retry window, never loses data.
    // Synchronized because nothing today stops a future caller from running
    // download() for two different names concurrently.
    private val wasRetried = Collections.synchronizedSet(HashSet<String>())

    suspend fun fetchManifest(): List<ManifestEntry> = withContext(Dispatchers.IO) {
        val json = JSONObject(String(get("$baseUrl/rides"), Charsets.UTF_8))
        val rides = json.getJSONArray("rides")
        (0 until rides.length()).map { i ->
            val o = rides.getJSONObject(i)
            ManifestEntry(o.getString("name"), o.getLong("size"), o.getString("crc32"))
        }
    }

    /** Registers manifest entries, skipping names already VERIFIED+. */
    suspend fun reconcile(manifest: List<ManifestEntry>) {
        for (m in manifest) {
            // Path-traversal guard: download() uses the name almost
            // directly as a filesystem path segment (File(ridesDir,
            // name)). Never trust a device-supplied name enough to let it
            // resolve outside ridesDir — reject it here, before it ever
            // reaches the DAO or the filesystem.
            if (m.name.contains('/') || m.name.contains('\\') || m.name.contains("..")) {
                continue
            }
            val existing = dao.byName(m.name)
            if (existing != null && existing.status !in listOf(
                    RideFileStatus.PENDING.name, RideFileStatus.DOWNLOADING.name,
                    RideFileStatus.FAILED.name)
            ) continue  // done or in a terminal reviewed state
            // A manifest entry whose size/crc32 changed under an
            // in-progress name is a different file wearing the same name
            // (firmware ride numbers are never reused, but don't trust
            // that blindly here) — docs/wifi_sync_protocol.md's Client
            // resume rules treat this as fresh, not a continuation of the
            // old bytes.
            val stale = existing != null &&
                (existing.sizeBytes != m.size || existing.crc32 != m.crc32)
            val carryOverBytes = if (stale) 0L else (existing?.downloadedBytes ?: 0)
            dao.upsert(
                RideFileEntity(
                    name = m.name, sizeBytes = m.size, crc32 = m.crc32,
                    downloadedBytes = carryOverBytes,
                    status = if (carryOverBytes > 0)
                        RideFileStatus.DOWNLOADING.name else RideFileStatus.PENDING.name,
                    sessionId = null, updatedAtEpochMs = nowEpochMs(),
                )
            )
        }
    }

    /**
     * Downloads one file to completion (resuming) and CRC-verifies it.
     * Returns the local file on success (status VERIFIED), null on failure
     * (status DOWNLOADING for retryable network errors, FAILED for a
     * second CRC mismatch). onProgress receives downloadedBytes.
     */
    suspend fun download(
        name: String,
        onProgress: (Long) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val row = dao.byName(name) ?: return@withContext null
        ridesDir.mkdirs()
        val local = File(ridesDir, name)
        var downloaded = row.downloadedBytes
        if (local.length() != downloaded) {
            // Disk truth wins over DB truth (an append can land without its
            // DB update on process death) — but only downward:
            downloaded = minOf(local.length(), downloaded)
            if (local.length() > downloaded) {
                // Torn append: shrink to the last DB-confirmed byte, keeping
                // the confirmed prefix intact. Must NOT use a plain
                // File/FileOutputStream here — opening one truncates the
                // file to zero length as a side effect of opening it (even
                // before any write), which would erase the good prefix too.
                // RandomAccessFile("rw") opens without truncating, so
                // channel.truncate(downloaded) only discards the torn tail.
                RandomAccessFile(local, "rw").channel.use { it.truncate(downloaded) }
            }
        }
        try {
            while (downloaded < row.sizeBytes) {
                val want = minOf(CHUNK_BYTES.toLong(), row.sizeBytes - downloaded).toInt()
                val encoded = URLEncoder.encode(name, "UTF-8")
                val chunk = get("$baseUrl/data?name=$encoded&offset=$downloaded&length=$want")
                if (chunk.isEmpty()) {
                    // server says EOF before the manifest size — device-side
                    // anomaly; surface as retryable, next manifest decides
                    dao.upsert(row.copy(downloadedBytes = downloaded,
                        status = RideFileStatus.DOWNLOADING.name,
                        updatedAtEpochMs = nowEpochMs()))
                    return@withContext null
                }
                local.appendBytes(chunk)
                downloaded += chunk.size
                dao.upsert(row.copy(downloadedBytes = downloaded,
                    status = RideFileStatus.DOWNLOADING.name,
                    updatedAtEpochMs = nowEpochMs()))
                onProgress(downloaded)
            }
        } catch (e: CancellationException) {
            // Must propagate, never be treated as a retryable network
            // failure — swallowing it here would stop coroutine
            // cancellation from working correctly through the caller's
            // hierarchy (e.g. a lifecycleScope cancelled on screen exit).
            throw e
        } catch (e: Exception) {
            dao.upsert(row.copy(downloadedBytes = downloaded,
                status = RideFileStatus.DOWNLOADING.name,
                updatedAtEpochMs = nowEpochMs()))
            return@withContext null   // resume point persisted; caller retries
        }

        // Complete — verify. One mismatch = wipe and mark PENDING (single
        // automatic retry via the next download call); a mismatch on a
        // file that was already retried once = FAILED. wasRetried.add(name)
        // returns true only the first time a given name mismatches (for the
        // lifetime of this client), which is exactly the "first vs second
        // attempt" distinction the contract needs — no extra bookkeeping.
        val crc = CRC32()
        local.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        val hex = String.format("%08X", crc.value)
        return@withContext if (hex.equals(row.crc32, ignoreCase = true)) {
            dao.upsert(row.copy(downloadedBytes = downloaded,
                status = RideFileStatus.VERIFIED.name, updatedAtEpochMs = nowEpochMs()))
            local
        } else {
            local.delete()
            if (wasRetried.add(name)) {
                dao.upsert(row.copy(downloadedBytes = 0,
                    status = RideFileStatus.PENDING.name, updatedAtEpochMs = nowEpochMs()))
            } else {
                dao.upsert(row.copy(downloadedBytes = 0,
                    status = RideFileStatus.FAILED.name, updatedAtEpochMs = nowEpochMs()))
            }
            null
        }
    }

    private fun get(url: String): ByteArray {
        val conn = open(URL(url))
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
