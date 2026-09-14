package com.jmart.x4sync.sync

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import com.jmart.x4sync.data.BleClient
import com.jmart.x4sync.data.BlePermissions
import com.jmart.x4sync.data.PairingStore

/**
 * Asks Android to watch for the reader on this app's behalf.
 *
 * A scan registered with a PendingIntent is held by the Bluetooth stack, not by
 * this process: when the reader wakes and advertises, the system delivers the
 * result to [ReaderPresenceReceiver] even if the app was closed or killed. The
 * filter is the paired reader's Bluetooth address plus the service UUID, so no
 * other advertiser wakes the app, and LOW_POWER lets the controller do the
 * filtering.
 *
 * The registration does not survive a reboot, an app update or Bluetooth being
 * switched off, so it is renewed from each of those ([ReaderScanRegistrar]),
 * whenever the engine starts with a paired reader, and whenever the background
 * service stops. Registering is idempotent: any previous scan is stopped first.
 */
object ReaderPresence {
    private const val ACTION_FOUND = "com.jmart.x4sync.READER_FOUND"
    private const val REQUEST_CODE = 4202

    /** Registers for the paired reader's address. Unpaired: stops the scan and returns false. */
    suspend fun register(context: Context): Boolean {
        val app = context.applicationContext
        if (!BlePermissions.granted(app)) return false
        val address = runCatching { PairingStore(app).load()?.address }.getOrNull()
        if (address.isNullOrBlank()) {
            unregister(app)
            return false
        }
        return registerFor(app, address)
    }

    @SuppressLint("MissingPermission")
    private fun registerFor(app: Context, address: String): Boolean {
        val scanner = scanner(app) ?: return false
        val pi = pendingIntent(app)
        runCatching { scanner.stopScan(pi) }
        return runCatching {
            scanner.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setDeviceAddress(address)
                        .setServiceUuid(ParcelUuid(BleClient.SERVICE))
                        .build()
                ),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                pi,
            ) == 0
        }.getOrDefault(false)
    }

    /** After forgetting the pairing: an unpaired reader is nothing to wake up for. */
    @SuppressLint("MissingPermission")
    fun unregister(context: Context) {
        val app = context.applicationContext
        if (!BlePermissions.granted(app)) return
        runCatching { scanner(app)?.stopScan(pendingIntent(app)) }
    }

    private fun scanner(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }
            ?.bluetoothLeScanner

    private fun pendingIntent(context: Context): PendingIntent {
        // MUTABLE is required, not careless: the system writes the scan results
        // into this intent. The intent itself is explicit, to our own receiver.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ReaderPresenceReceiver::class.java).setAction(ACTION_FOUND),
            flags,
        )
    }
}
