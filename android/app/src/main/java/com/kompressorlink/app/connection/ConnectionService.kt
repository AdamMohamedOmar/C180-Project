package com.kompressorlink.app.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kompressorlink.app.KompressorLinkApp
import com.kompressorlink.app.telemetry.ConnectionState
import com.kompressorlink.app.telemetry.SourceChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Foreground service holding the app alive while the device is in range.
// Connection logic itself lives in BleSession (always running, but gated
// on choice+association) — this service's jobs are the foreground
// notification and flipping the source to REAL_BLE on appearance.
class ConnectionService : LifecycleService() {

    private var notificationJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_APPEARED -> onAppeared()
            ACTION_DISAPPEARED -> {
                notificationJob?.cancel()
                notificationJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun onAppeared() {
        createChannel()
        // targetSdk 34: the typed startForeground overload + the
        // FOREGROUND_SERVICE_CONNECTED_DEVICE manifest permission are both
        // REQUIRED — missing either is a SecurityException at runtime.
        val notification = buildNotification("Device in range — connecting…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val container = (application as KompressorLinkApp).container
        // The device physically appeared => live data is what the user
        // wants: flip the source so BleSession connects (cold-start gate).
        lifecycleScope.launch { container.choiceStore.set(SourceChoice.REAL_BLE) }
        notificationJob?.cancel()
        notificationJob = lifecycleScope.launch {
            container.bleSession.connectionState.collect { state ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(stateText(state)))
            }
        }
    }

    private fun stateText(state: ConnectionState): String = when (state) {
        is ConnectionState.Ready ->
            if (state.demo) "Streaming (demo device)" else "Streaming live data"
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Disconnected -> "Disconnected — retrying"
        ConnectionState.NotAssociated -> "No device associated"
        ConnectionState.ProtocolMismatch -> "Protocol mismatch — update app/firmware"
        is ConnectionState.Simulated -> "Simulated data active"
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KompressorLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "kl_connection"
        private const val NOTIFICATION_ID = 1

        const val ACTION_APPEARED = "com.kompressorlink.app.DEVICE_APPEARED"
        const val ACTION_DISAPPEARED = "com.kompressorlink.app.DEVICE_DISAPPEARED"

        fun start(context: Context, appeared: Boolean) {
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(if (appeared) ACTION_APPEARED else ACTION_DISAPPEARED)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
