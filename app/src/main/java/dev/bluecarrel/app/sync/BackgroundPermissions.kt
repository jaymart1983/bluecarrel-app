package dev.bluecarrel.app.sync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * What background sync needs, whether it has it, and how to ask.
 *
 * Split into two kinds deliberately, because they are asked for in completely
 * different ways and conflating them is why "grant permissions" buttons so often
 * do nothing:
 *
 *  - [Runtime] permissions have a system dialog. The app can ask directly.
 *  - [SettingsToggle] entries have no dialog at all -- battery optimisation
 *    exemption and per-OEM background limits can only be changed by the user on
 *    a Settings screen. The best the app can do is explain and open the exact
 *    page, which is why each carries its own intent.
 */
sealed interface BackgroundRequirement {
    val title: String
    val why: String

    data class Runtime(
        override val title: String,
        override val why: String,
        val permission: String,
    ) : BackgroundRequirement

    data class SettingsToggle(
        override val title: String,
        override val why: String,
        val intent: (Context) -> Intent,
    ) : BackgroundRequirement
}

object BackgroundPermissions {

    /**
     * Everything still missing, in the order worth asking for it.
     *
     * Runtime permissions first: they are one tap each and the Settings trips
     * are the ones that lose people, so nothing is worth sending the user out of
     * the app for until the cheap asks are done.
     */
    fun missing(context: Context): List<BackgroundRequirement> {
        val out = mutableListOf<BackgroundRequirement>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
                out += BackgroundRequirement.Runtime(
                    title = "Nearby devices",
                    why = "Needed to talk to the reader at all.",
                    permission = Manifest.permission.BLUETOOTH_CONNECT,
                )
            }
            if (!granted(context, Manifest.permission.BLUETOOTH_SCAN)) {
                out += BackgroundRequirement.Runtime(
                    title = "Find nearby devices",
                    why = "Needed to notice the reader waking up.",
                    permission = Manifest.permission.BLUETOOTH_SCAN,
                )
            }
        } else if (!granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Not optional and not really about location: on 29/30 a BLE scan
            // without it returns zero results and reports no error at all.
            out += BackgroundRequirement.Runtime(
                title = "Location",
                why = "Android 10 and 11 return no Bluetooth scan results without it. " +
                    "This app never uses your position.",
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            out += BackgroundRequirement.Runtime(
                title = "Notifications",
                why = "Background syncing runs as a foreground service, which Android " +
                    "requires to show a notification while it works.",
                permission = Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        if (!isIgnoringBatteryOptimizations(context)) {
            out += BackgroundRequirement.SettingsToggle(
                title = "Unrestricted battery use",
                why = "Lets the app connect to your reader when it wakes, even with the app " +
                    "closed, so positions and new books sync without opening it. Android " +
                    "will not start a background sync for a battery-optimised app.",
                intent = { ctx ->
                    // The direct "let this app always run in the background?"
                    // dialog: one tap, and the user sees exactly what is granted.
                    // Needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest;
                    // openSettings() falls back to the app's details page on an
                    // OEM build without it.
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + ctx.packageName))
                },
            )
        }
        return out
    }

    /** The runtime half, ready to hand to a permission launcher. */
    fun runtimePermissions(context: Context): Array<String> =
        missing(context).filterIsInstance<BackgroundRequirement.Runtime>()
            .map { it.permission }
            .toTypedArray()

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }.getOrDefault(false)

    /**
     * Opens a Settings page, falling back to this app's own details screen.
     *
     * Some OEMs ship without the specific screen the intent names, and an
     * ActivityNotFoundException on a "fix this" button is worse than landing one
     * level up.
     */
    fun openSettings(activity: Activity, requirement: BackgroundRequirement.SettingsToggle) {
        val intent = runCatching { requirement.intent(activity) }.getOrNull()
        val ok = intent != null && runCatching {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
        if (!ok) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", activity.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
