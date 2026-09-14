package com.jmart.x4sync.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions BLE needs, which changed shape in API 31.
 *
 * Before 31 there is no BLUETOOTH_SCAN, and the platform treats a BLE scan as
 * a location capability: without ACCESS_FINE_LOCATION granted *and* location
 * services switched on, scans silently return zero results rather than
 * failing. That is why the old permissions are still here at all — this app's
 * minSdk is 29.
 */
object BlePermissions {

    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun missing(context: Context): List<String> = required.filterNot {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** What to tell the user when they say no. */
    val denialMessage: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            "Bluetooth permission is required to find and talk to the reader. " +
                "Grant \"Nearby devices\" in Settings > Apps > X4 Pro Sync > Permissions."
        else
            "Location permission is required on Android 11 and older to scan for " +
                "Bluetooth devices. Location services must also be switched on."
}
