package com.kompressorlink.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// Joins the logger's SoftAP as a local-only network (no internet routing —
// Android keeps mobile data up) and yields a Network whose openConnection
// pins sockets to the AP. Interface-first so RidesViewModel tests use a fake.
interface SyncNetwork {
    val opener: (URL) -> HttpURLConnection
    fun close()
}

interface WifiSyncConnector {
    /** Null = join failed/timed out. */
    suspend fun connect(): SyncNetwork?
}

// docs/wifi_sync_protocol.md v1: static SoftAP SSID/PSK — a personal,
// single-user device, [Best estimate: adequate]. Thin OS glue over
// ConnectivityManager/WifiNetworkSpecifier: no dedicated unit test (not
// meaningfully unit-testable — this is exactly why WifiSyncConnector is an
// interface, so RidesViewModelTest can pass a trivial fake instead); reviewed
// by reading, and exercised for real during the Task 13 phone acceptance pass.
class RealWifiSyncConnector(
    context: Context,
    private val ssid: String = "KompressorLink",
    private val psk: String = "kompressor-link",   // docs/wifi_sync_protocol.md
    private val timeoutMs: Long = 30_000,
) : WifiSyncConnector {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override suspend fun connect(): SyncNetwork? {
        // WifiNetworkSpecifier.Builder requires API 29 (Q); this app's
        // minSdk is 26. Fail the same honest way a timed-out/refused join
        // would, instead of letting an API 26-28 device hit a
        // NoClassDefFoundError the moment this method runs.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(
                        WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(psk).build()
                    )
                    .build()
                // `callback` needs to be readable from inside its own
                // onAvailable() body (the returned SyncNetwork.close()
                // unregisters it) — but a plain `val callback = object : ... {
                // ... callback ... }` does NOT compile: Kotlin resolves names
                // lexically, and a local val's initializer expression cannot
                // see the val itself, even from inside a nested lambda/object
                // body that only runs later (confirmed empirically — that
                // naive version fails with "Unresolved reference: callback").
                // A `lateinit var`, declared and only READ later, sidesteps
                // this: it's a genuine pre-declared symbol by the time the
                // object expression below is written, so the reference
                // resolves; it's only ever READ from onAvailable/onUnavailable,
                // which the platform invokes asynchronously, well after the
                // `callback = object : ...` assignment two lines down has
                // completed — so there's no UninitializedPropertyAccessException
                // risk either.
                lateinit var callback: ConnectivityManager.NetworkCallback
                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) {
                            cont.resume(object : SyncNetwork {
                                override val opener: (URL) -> HttpURLConnection =
                                    { url -> network.openConnection(url) as HttpURLConnection }
                                override fun close() = cm.unregisterNetworkCallback(callback)
                            })
                        }
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cm.requestNetwork(request, callback)
                cont.invokeOnCancellation { cm.unregisterNetworkCallback(callback) }
            }
        }
    }
}
