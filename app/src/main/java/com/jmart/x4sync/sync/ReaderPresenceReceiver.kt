package com.jmart.x4sync.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jmart.x4sync.X4SyncApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The reader turned up. Start the background service, which connects.
 *
 * Delivered by the Bluetooth stack from the scan [ReaderPresence] registers, so
 * it needs no running process: Android starts one to deliver it.
 */
class ReaderPresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Deliberately not inspecting the scan results. The only decision they
        // support is "worth connecting now", and the engine re-checks the rest.
        if (!ReaderSyncService.start(app)) {
            // Android refused a background service start (Android 12+ with
            // battery optimisation on). Try anyway in whatever time the process
            // is given; the next foreground open catches up regardless.
            (app as? X4SyncApp)?.engine?.onReaderNearby()
        }
    }
}

/** Re-arms the reader scan after a reboot or an app update, both of which drop it. */
class ReaderScanRegistrar : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // The paired address is in DataStore, which is read off the main thread.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ReaderPresence.register(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
