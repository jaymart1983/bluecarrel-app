package dev.bluecarrel.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.bluecarrel.app.MainActivity
import dev.bluecarrel.app.R
import dev.bluecarrel.app.UiState
import dev.bluecarrel.app.BluecarrelApp
import dev.bluecarrel.app.data.BleConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app's process alive while the reader is connected, so syncing and
 * the heartbeat kosync writes carry on with the app closed.
 *
 * It holds no logic of its own. The engine is the process-wide MainViewModel
 * ([BluecarrelApp.engine]); this only exists because Android freezes or kills a
 * process with no visible work, and a foreground service is how an app says
 * "this is visible work". The notification is the price of that and is not
 * optional: from Android 8 background work is a foreground service, and from
 * Android 14 the service must declare a type -- `connectedDevice`.
 *
 * Starts when the link comes up (from the engine) or when the reader is seen
 * advertising ([ReaderPresenceReceiver]). Stops itself [STOP_GRACE_MS] after
 * the link drops, once nothing is owed -- so the notification is there while
 * the reader is awake and connected, and gone while it sleeps.
 */
class ReaderSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false
    private var shownText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, always: a startForegroundService() that is not answered with
        // startForeground() within seconds is a crash.
        if (!showForeground(shownText.ifEmpty { "Looking for your reader" })) {
            stopSelf()
            return START_NOT_STICKY
        }
        val engine = (application as BluecarrelApp).engine
        engine.onReaderNearby()
        if (!watching) {
            watching = true
            watch()
        }
        // Not sticky: a restart with no reader around would only show a
        // notification for nothing. The presence scan is what brings it back.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun watch() {
        val engine = (application as BluecarrelApp).engine

        scope.launch {
            if (!engine.isPaired()) stopSelf()
        }

        // The notification says what the engine is doing, nothing more.
        scope.launch {
            engine.state.map { describe(it) }.distinctUntilChanged().collect { showForeground(it) }
        }

        // Keyed on CONNECTED alone. The reconnect loop flips between IDLE,
        // SCANNING and CONNECTING every few seconds; counting those as "up"
        // would restart the grace timer forever and the service would never stop.
        scope.launch {
            engine.state.map { it.connection == BleConnection.CONNECTED }
                .distinctUntilChanged()
                .collectLatest { up ->
                    if (up) return@collectLatest
                    delay(STOP_GRACE_MS)
                    // The sleep-time position write, a sync pass, a transfer:
                    // let them land before the process loses its protection.
                    while (engine.hasBackgroundWork()) delay(WORK_POLL_MS)
                    ReaderPresence.register(this@ReaderSyncService)
                    stopSelf()
                }
        }
    }

    private fun describe(s: UiState): String {
        val status = s.syncStatus
        val transfer = s.transfer
        return when {
            // A firmware image or a book on its way: the one case worth a number,
            // since it can run for minutes with the app closed.
            transfer != null && transfer.total > 0 -> transfer.label + " " + (transfer.fraction * 100).toInt() + "%"
            status != null -> status
            s.connection == BleConnection.CONNECTED -> "Connected to " + s.deviceName.ifBlank { "your reader" }
            else -> "Looking for your reader"
        }
    }

    /** Also how the text is UPDATED: startForeground() again replaces the notification. */
    private fun showForeground(text: String): Boolean = runCatching {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluecarrel")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            // LOW, not DEFAULT: visible, never buzzes.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        shownText = text
        true
    }.getOrDefault(false)

    companion object {
        private const val CHANNEL_ID = "reader_sync"
        private const val NOTIFICATION_ID = 4201
        /** Long enough to ride out a reconnect and the sleep-time kosync write. */
        private const val STOP_GRACE_MS = 90_000L
        private const val WORK_POLL_MS = 5_000L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Reader sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the app is connected to your reader in the background."
                    setShowBadge(false)
                }
            )
        }

        /**
         * Starts the service; false when Android will not allow it right now.
         *
         * A refused background start throws ForegroundServiceStartNotAllowedException.
         * That is a normal outcome, not a bug -- the app may simply not be
         * permitted to run -- and crashing a receiver over it would be worse
         * than missing one sync.
         */
        fun start(context: Context): Boolean = runCatching {
            val intent = Intent(context, ReaderSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            true
        }.getOrDefault(false)
    }
}
