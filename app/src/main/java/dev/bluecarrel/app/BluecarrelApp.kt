package dev.bluecarrel.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.bluecarrel.app.sync.ReaderSyncService

/**
 * Owns the sync engine for the life of the PROCESS, not of a screen.
 *
 * MainViewModel is the whole engine: the BLE link, the sync runner, the
 * heartbeat kosync writes. Scoped to an Activity it would be cleared when the
 * app closed, and onCleared() disconnects the reader, leaving nothing for a
 * background service to run. Held in an Application-owned store, the Activity,
 * [ReaderSyncService] and the presence receiver all reach the SAME instance,
 * and closing the UI does not end the link.
 */
class BluecarrelApp : Application(), ViewModelStoreOwner {

    override val viewModelStore: ViewModelStore = ViewModelStore()

    val engine: MainViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[MainViewModel::class.java]
    }

    override fun onCreate() {
        super.onCreate()
        ReaderSyncService.ensureChannel(this)
    }
}
