package dev.bluecarrel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.bluecarrel.app.data.BlePermissions
import dev.bluecarrel.app.sync.BackgroundPermissions
import dev.bluecarrel.app.sync.BackgroundRequirement
import dev.bluecarrel.app.ui.AppScaffold

class MainActivity : ComponentActivity() {

    /** The process-wide engine, NOT an Activity-scoped one -- see [BluecarrelApp]. */
    private val vm: MainViewModel get() = (application as BluecarrelApp).engine

    /** A background-sync grant that only a system screen can give, still missing. */
    private var backgroundAsk by mutableStateOf<BackgroundRequirement.SettingsToggle?>(null)

    /**
     * Nearby-devices (API 31+) or fine location (29/30), plus notifications
     * (33+). Registered at construction time, which the Activity Result API
     * requires -- registering inside a click handler throws.
     */
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Judged on the Bluetooth permissions alone: declining notifications
        // must not read as "Bluetooth denied" and park the link.
        vm.onPermissionsResult(BlePermissions.granted(this))
        offerBackgroundSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    // Ask once on first composition. Denial is handled by the
                    // ViewModel: the banner explains it and the connect button
                    // asks again rather than the app being dead.
                    LaunchedEffect(Unit) {
                        val missing = (BlePermissions.missing(this@MainActivity) +
                            BackgroundPermissions.runtimePermissions(this@MainActivity)).distinct()
                        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
                        else offerBackgroundSetup()
                    }
                    AppScaffold(vm = vm)
                    backgroundAsk?.let { ask ->
                        AlertDialog(
                            onDismissRequest = { backgroundAsk = null },
                            title = { Text("Sync with the app closed") },
                            text = { Text(ask.why + "\n\nAndroid will ask you to confirm.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    backgroundAsk = null
                                    BackgroundPermissions.openSettings(this@MainActivity, ask)
                                }) { Text("Allow") }
                            },
                            dismissButton = {
                                TextButton(onClick = { backgroundAsk = null }) { Text("Not now") }
                            },
                        )
                    }
                }
            }
        }
    }

    /** "Not now" is for this launch only; the next launch asks again. */
    private fun offerBackgroundSetup() {
        backgroundAsk = BackgroundPermissions.missing(this)
            .filterIsInstance<BackgroundRequirement.SettingsToggle>()
            .firstOrNull()
    }
}
