package dev.bluecarrel.app

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import dev.bluecarrel.app.sync.ReaderPresence
import dev.bluecarrel.app.sync.ReaderSyncService
import androidx.lifecycle.viewModelScope
import dev.bluecarrel.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app's two surfaces.
 *
 * STORE is the whole Calibre library, searched rather than listed: a large
 * library is not something to scroll, and it is not something to mirror onto a
 * reader either. LIBRARY is what has been saved offline — the finite set the
 * reader's own library mirrors.
 */
enum class AppTab { LIBRARY, STORE }

/** A transfer in flight, for the progress bar. BLE is slow enough to need one. */
data class TransferProgress(
    val label: String,
    val sent: Long,
    val total: Long,
    /** Transfer rate, shown beside the percentage when above 0. */
    val kbps: Int = 0,
    /**
     * Who started it, e.g. "firmware" or "book:<filename>". Only the owner ends
     * it, so one job finishing does not clear the bar out from under another.
     */
    val owner: String,
) {
    val fraction: Float get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
    val percent: Int get() = if (total > 0) (sent * 100 / total).toInt().coerceIn(0, 100) else 0
}

/** "31 KB/s", or "1.2 MB/s" from 1000 KB/s. */
fun transferRate(kbps: Int): String =
    if (kbps >= 1000) String.format(java.util.Locale.getDefault(), "%.1f MB/s", kbps / 1024.0)
    else "$kbps KB/s"

/**
 * Lets progress reach the screen about four times a second, plus the end.
 * A fast download reports hundreds of times a second; every one of those was
 * a recomposition of the whole scaffold.
 */
class ProgressGate(private val everyMs: Long = 250L) {
    private var lastAt = 0L
    private var opened = false

    fun due(sent: Long, total: Long): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (opened && now - lastAt < everyMs && !(total > 0 && sent >= total)) return false
        opened = true
        lastAt = now
        return true
    }
}

/**
 * The rate a transfer bar shows: bytes over about the last two seconds, not the
 * whole-transfer average, so a slow patch shows. Nothing for the first half
 * second, and the number changes at most twice a second so it can be read.
 */
class RateMeter {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var firstAt = 0L
    private var shownAt = 0L
    private var shown = 0

    /** Records [bytes] moved so far; returns the KB/s to show, 0 for none. */
    fun kbps(bytes: Long): Int {
        val now = System.currentTimeMillis()
        if (firstAt == 0L) firstAt = now
        if (samples.isEmpty() || now - samples.last().first >= SAMPLE_MS) samples.addLast(now to bytes)
        // One sample from before the window stays as its baseline.
        while (samples.size > 2 && now - samples[1].first >= WINDOW_MS) samples.removeFirst()
        if (now - firstAt < HIDDEN_MS) return 0
        if (now - shownAt < HOLD_MS) return shown
        val (since, base) = samples.first()
        if (now <= since) return shown
        shown = ((bytes - base) * 1000 / (now - since) / 1024).toInt()
        shownAt = now
        return shown
    }

    private companion object {
        const val WINDOW_MS = 2_000L
        const val SAMPLE_MS = 100L
        const val HIDDEN_MS = 500L
        const val HOLD_MS = 500L
    }
}

/** The phone is in dark mode. */
private fun isNight(config: Configuration): Boolean =
    (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** What one run of the sync did to the reader, for [UiState.lastSync]. */
private class SyncTally {
    val startedAt = System.currentTimeMillis()
    /** A pass found the reader linked; without that nothing here was a sync of it. */
    var linked = false
    var booksSent = 0
    var booksFailed = 0
    var removed = 0
    var removeFailed = 0
    var positions = 0
    /** Short reasons something was left undone, e.g. "book open on reader". */
    val problems = linkedSetOf<String>()
    /** The run itself threw. */
    var error: String? = null
}

/**
 * The pairing picker's scan. [readers] is live while [scanning]; [error] says
 * why a scan could not run. Separate from the link: looking is not connecting.
 */
data class ReaderScan(
    val scanning: Boolean = false,
    val readers: List<DiscoveredReader> = emptyList(),
    val error: String? = null,
)

/** Where a firmware update started from this app has got to. */
enum class FirmwarePhase { DOWNLOADING, SENDING, INSTALLING, SCHEDULED }

/**
 * A firmware update in flight. It ends only when the READER reports [version]
 * as its running build (or the watch times out) -- not when the upload finishes,
 * and not when the prompt is answered.
 */
data class FirmwareProgress(
    val version: String,
    val phase: FirmwarePhase,
    val percent: Int = 0,
    /** Bluetooth rate while SENDING; 0 until bytes are moving. */
    val kbps: Int = 0,
)

/**
 * "Link to Calibre book" for a book only on the reader: the reader's [filename],
 * the search box and what it found.
 */
data class LinkPicker(
    val filename: String,
    val title: String,
    val query: String,
    val searching: Boolean = false,
    val results: List<Book> = emptyList(),
    val note: String? = null,
)

/** A one-line outcome shown on the reader-settings screen. */
data class SettingsNotice(val text: String, val isError: Boolean)

/**
 * The reader-settings screen's whole state.
 *
 * [values] is what the reader last told us; [edits] is what the user has
 * changed and not yet sent. Keeping them apart is what lets the screen show
 * which rows are pending and lets Save go back to being disabled once the
 * write lands, without a second download.
 */
data class DeviceSettingsUi(
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** Set when the download failed; the screen offers Retry. */
    val loadError: String? = null,
    val values: Map<String, Int> = emptyMap(),
    val edits: Map<String, Int> = emptyMap(),
    /** String-valued settings, kept apart from [values]; see DeviceSetting.Text. */
    val textValues: Map<String, String> = emptyMap(),
    val textEdits: Map<String, String> = emptyMap(),
    val notice: SettingsNotice? = null,
) {
    val loaded: Boolean get() = values.isNotEmpty() || textValues.isNotEmpty()
    val dirty: Boolean get() = edits.isNotEmpty() || textEdits.isNotEmpty()

    /** The value a row should display: the pending edit, else the reader's. */
    fun valueOf(key: String): Int? = edits[key] ?: values[key]
    fun textValueOf(key: String): String = textEdits[key] ?: textValues[key] ?: ""
    fun isChanged(key: String): Boolean = edits.containsKey(key) || textEdits.containsKey(key)
}

data class UiState(
    val config: Config = Config(),
    val rows: List<BookRow> = emptyList(),
    /**
     * The offline shelf, built from the books directory.
     *
     * Deliberately a field of its own rather than a filter over [rows].
     * [rows] is the STORE's current contents — one page of `/new`, or the last
     * search's results — so filtering it by `cached` made the Library a view of
     * whatever the Store happened to be showing: a search for anything emptied
     * it, and a book saved before ten newer ones arrived fell off the end of
     * `/new` and disappeared. The shelf is what is on disk, and nothing else.
     */
    val library: List<BookRow> = emptyList(),
    /** Which surface is showing. The Library is the default: it is the shelf. */
    val tab: AppTab = AppTab.LIBRARY,
    /** The Store's search box. Empty means "browse the newest". */
    val query: String = "",
    val searching: Boolean = false,
    val loading: Boolean = false,
    /**
     * Books with work in flight, by catalogue id.
     *
     * A set rather than one id: with a single id a second removal would
     * overwrite the first, and its row would show a tick while the removal was
     * still running. Several books can be on their way out at once and each row
     * has to be able to say so for itself.
     */
    val busyBookIds: Set<String> = emptySet(),
    /**
     * Books already committed to removal, still being taken off the reader.
     *
     * Kept apart from [busyBookIds] because the row means something different:
     * this book is going, so grey it out and stop offering it, but do not drop
     * it from the list until the reader has actually let go of it.
     */
    val removingBookIds: Set<String> = emptySet(),
    /**
     * Downloaded here, not yet confirmed on the reader.
     *
     * Explicit state rather than inferred from `cached && !sentFromThisApp`.
     * That inference is right in principle but can already be false at the
     * moment the row first draws: `sentFromThisApp` is persisted bookkeeping,
     * and a book re-saved after an earlier send still carries its old entry, so
     * the row renders as "already there" the instant it appears. This set is
     * written by the code that actually does the work, so it cannot disagree
     * with it.
     */
    val pendingTransferIds: Set<String> = emptySet(),
    /** A position sync is running. Gates the menu item so it cannot be re-entered. */
    val syncingProgress: Boolean = false,
    /**
     * The library mirror is running: sending what is missing, pruning what is not.
     *
     * Surfaced because the mirror runs on every connection, and without it a
     * connection that is busy catching up looks identical to one that has
     * decided to do nothing.
     */
    val syncingLibrary: Boolean = false,
    /** What the mirror is doing right now, for the status bar. Null when idle. */
    val syncStatus: String? = null,
    /** How the last sync ended; shown in the status bar once it is idle. */
    val lastSync: SyncSummary? = null,
    /**
     * Pending "resume or restart?" question for a book just saved offline.
     *
     * Non-null blocks the save mid-flight: the book is on the phone but has not
     * been handed to the mirror yet, because where it should open is the user's
     * call and sending it first would mean answering that question for them.
     */
    val resumePrompt: ResumePrompt? = null,
    val message: String? = null,

    // --- reader link (BLE) ---
    val connection: BleConnection = BleConnection.IDLE,
    val link: LinkStatus = LinkStatus(),
    val device: DeviceStatus? = null,
    /** Filename of the book open on the reader right now, or null. */
    val readerOpenBook: String? = null,
    /** Filenames in the reader's last library listing. Store rows use it to say "On the reader". */
    val readerBookNames: Set<String> = emptySet(),
    /** A removal asked for a book that is open on the reader; waiting on the user. */
    val removeOpenPrompt: BookRow? = null,
    /**
     * What this reader calls itself, as last read from its settings.
     *
     * Cached rather than read live because the pill that shows it is on screen
     * from launch, long before anyone opens the settings sheet -- and a pill
     * that says "Reader" until you visit settings, then changes, reads as a bug.
     */
    val deviceName: String = "",
    val pairing: PairingState = PairingState.UNPAIRED,
    val authorized: Boolean = false,
    /** The advertised name of the reader being paired, while a pairing runs; null otherwise. */
    val pairingReaderName: String? = null,
    /** The pairing picker's scan, while the picker is open; null when it is closed. */
    val readerScan: ReaderScan? = null,
    /** The reader is showing its "allow this phone?" screen for a pairing in flight. */
    val pairingConfirm: Boolean = false,
    /** `features` from the reader's last `about` document. Empty until read. */
    val readerFeatures: Set<String> = emptySet(),
    /** The "Link to Calibre book" picker, while open. */
    val linkPicker: LinkPicker? = null,
    val permissionsGranted: Boolean = true,
    val transfer: TransferProgress? = null,

    /** What the reader's Store screen is asking us for, if anything. */
    val storeActivity: String? = null,

    /** The reader's own settings, while its screen is open. */
    val deviceSettings: DeviceSettingsUi = DeviceSettingsUi(),

    /** The build the reader runs, from its `about` download; null when unknown. */
    val readerFirmware: String? = null,
    /** The newest firmware on the update page, from its firmware.json. */
    val latestFirmware: FirmwareManifest? = null,
    /** Why the update page could not be read, when it could not. */
    val firmwareCheckNote: String? = null,
    /** The reader refused the `about` download: its firmware predates the version report. */
    val readerFirmwareTooOld: Boolean = false,
    /** The update page has a newer build than the reader runs. Drives the pill badge. */
    val firmwareUpdateAvailable: Boolean = false,
    /** The reader already holds an image, waiting on Update now / Later / Cancel. */
    val readerUpdateStaged: Boolean = false,
    /** The reader will install its staged image the next time it sleeps. */
    val readerInstallAtSleep: Boolean = false,
    /** A firmware update this app started, until the reader confirms the new build. */
    val firmwareProgress: FirmwareProgress? = null,
    /** A firmware check is running; the Firmware screen waits on it before offering Install. */
    val firmwareChecking: Boolean = false,

    /**
     * A trusted-host secret exists in DataStore. Deliberately NOT derived from
     * [pairing]: a failed connection resets session state to UNPAIRED while the
     * secret is still on disk, and gating "Forget pairing" on session state made
     * the one control that clears it unreachable exactly when it was needed --
     * the reader answering "unknown trusted host" is precisely that case.
     */
    val hasStoredPairing: Boolean = false,
    /** Diagnostics for the pairing bug: what the last auth attempt actually did. */
    val authTrace: String = "no attempt yet",
    /** The last auth_error the reader reported. Diagnostics. */
    val lastAuthError: String? = null,
    val storedHostId: String? = null,
    /** Store diagnostics: what the app has seen and done about reader requests. */
    val storeTrace: String = "no request seen",
    /** The last sync, one line per step. Diagnostics. */
    val syncTrace: List<String> = emptyList(),
    /** The sync before it. */
    val previousSyncTrace: List<String> = emptyList(),
    /** MTU, PHY and connection priority as last reported. Diagnostics. */
    val linkInfo: String = "",
    /** The last firmware or book upload, with where its time went. Diagnostics. */
    val lastTransfer: String? = null,
) {
    val connected: Boolean get() = connection == BleConnection.CONNECTED

    /**
     * The reader is asking, on its own screen, whether to remember this phone.
     * It only ever asks after a completed upload — that is the whole reason
     * pairing is not permanent until then.
     */
    val awaitingSaveHostPrompt: Boolean get() = device?.state == "save_host_prompt"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** [TransferProgress.owner]s with a fixed name. Books are "book:<filename>". */
        const val OWNER_FIRMWARE = "firmware"
        const val OWNER_STORE = "store"
        const val OWNER_CALIBRE = "calibre"
        const val OWNER_SETTINGS = "settings"
        const val OWNER_CRASH = "crash"

        /** A sync that found nothing to do this soon after one that did leaves that one on the bar. */
        const val SYNC_SUMMARY_HOLD_MS = 60_000L

        /** How often the link is verified against the reader rather than assumed. */
        const val LINK_CHECK_MS = 10_000L
        const val RECONNECT_MIN_MS = 3_000L
        const val RECONNECT_MAX_MS = 60_000L

        /**
         * The OPDS view both the app's own list and the reader's Store browse.
         * Calibre-Web's `/new` is the whole library, newest first, paginated —
         * not a "recently added" subset — so it is a complete view to page.
         */
        const val FEED_PATH = "/new"
        /** At most one heartbeat-driven kosync write per this interval. */
        const val HEARTBEAT_WRITE_MS = 60_000L
        /** A connect refreshes the catalogue (in the background) only when older than this. */
        const val CATALOGUE_STALE_MS = 60 * 60_000L
        /** kosync lookups in flight at once during a position pass. */
        const val PREFETCH_PARALLEL = 8
        /** A book whose reader save is unchanged is looked up in kosync again after this. */
        const val KOSYNC_RECHECK_MS = 5 * 60_000L
        /** Positions in a kept listing are re-read after this even with no heartbeat. */
        const val LISTING_POSITIONS_TTL_MS = 5 * 60_000L
        /** A new library fingerprint this soon after this app's own send or removal is that change. */
        const val LIBRARY_ECHO_MS = 15_000L
        /** A connect re-checks the update page at most this often. */
        const val FIRMWARE_CHECK_MS = 10 * 60_000L
        /** While connected, the update page is re-checked this often (network only). */
        const val FIRMWARE_POLL_MS = 10 * 60_000L
        /** Presence broadcasts start a connect attempt at most this often. */
        const val NEARBY_RETRY_MS = 10_000L
        /** Link-level connect failures in a row before the pill says to restart Bluetooth. */
        const val LINK_FAIL_HINT_AFTER = 3
        /** Following an install: how often the reader is asked, and for how long. */
        const val FIRMWARE_WATCH_POLL_MS = 20_000L
        const val FIRMWARE_INSTALL_TIMEOUT_MS = 15 * 60_000L
        /** What to do to pair: the reader opens its pairing window on its Settings screen. */
        const val PAIR_HINT = "On the reader, open Settings"
        /** The reader's pairing window is closed. */
        const val PAIR_CLOSED_HINT = "On the reader open Settings and tap Pair new phone. " +
            "If no passkey appears, remove the reader in Bluetooth settings and try again."
        /** The reader no longer knows this phone. */
        const val FORGOTTEN_HINT = "Remove the reader in Bluetooth settings, then pair again"
        /** Reader auth_error codes that mean it has no record of this phone. */
        val FORGOTTEN_CODES = setOf("unknown trusted host", "invalid trusted host auth")

        /** Book ids of Library rows for books only on the reader: this plus the reader filename. */
        const val READER_ROW = "reader:"
        /** `about` features: the reader takes and lists `calibre_uuid`; it sends a book back. */
        const val BOOK_UUID = "book_uuid"
        const val BOOK_DOWNLOAD = "book_download"
        /** Calibre downloads at once while relinking reader books. */
        const val RELINK_PARALLEL = 2
        /** After an upload to Calibre, how often and how long to look for the imported book. */
        const val ADD_POLL_MS = 20_000L
        const val ADD_WAIT_MS = 5 * 60_000L
        /** A bonded reader seen by the picker is paired straight away once no second one shows in this time. */
        const val AUTO_PAIR_SETTLE_MS = 1_500L
        const val CONFIRM_ON_READER_TEXT = "Confirm on the reader"
        const val PAIRING_DECLINED_TEXT = "Pairing was declined on the reader"
    }

    private val settings = SettingsStore(app)
    private val pairingStore = PairingStore(app)
    private val sentBooks = SentBooksStore(app)
    private val pendingRemovals = PendingRemovalStore(app)
    private val startFresh = StartFreshStore(app)
    private val owedResumes = OwedResumeStore(app)
    private val replaceOnSend = ReplaceOnSendStore(app)
    private val lastSyncStore = LastSyncStore(app)
    private val darkModeSent = DarkModeSentStore(app)

    /** Completed by [answerResume]; awaited by the save that raised the prompt. */
    private var resumeAnswer: CompletableDeferred<Boolean>? = null
    private val books = BookStore(app)
    val covers by lazy { CoverCache(http, app.cacheDir) { _state.value.config.base } }

    // OPDS, kosync and the update page are reached over HTTP; only the reader is on BLE.
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val ble = BleClient(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Answers the reader's Store requests. Attached for the ViewModel's whole
     * life, not per connection: the reader can open its Store screen at any
     * point after the gate opens, and the subscription has to be there already.
     */
    private val store = StoreResponder(
        ble = ble,
        covers = covers,
        books = books,
        context = { storeContext() },
        onEvent = { event -> onStoreEvent(event) },
    )

    /**
     * Renders the reader's thumbnails before it asks for them. See
     * [CatalogPrefetcher]: answering a catalog_page cold means downloading six
     * covers from Calibre-Web and dithering each one inside the reader's request
     * budget, which is what made it give up and report that the app had not
     * answered.
     */
    private val prefetcher = CatalogPrefetcher(covers, viewModelScope)

    /** The connect attempt in flight, if any. Declared ahead of init: init connects. */
    private var connectJob: Job? = null
    /** When a reader-presence broadcast last started an attempt. */
    private var lastNearbyAttemptAt = 0L
    /** Consecutive connect attempts that failed at the Bluetooth link itself. */
    private var linkFailStreak = 0
    /** A firmware send the link cut off; resumed when the reader reconnects. */
    private var resumeFirmwareVersion: String? = null
    /**
     * A pairing with a picked reader is running. Its link reaches CONNECTED
     * before anything is stored, and everything that watches the link --
     * reauthentication, the watchdog, presence reconnects, the background
     * service -- would otherwise treat that as a reconnect of an unpaired app.
     */
    private var pairingInProgress = false
    /** The picker's scan, and a counter so a superseded scan cannot write state. */
    private var scanJob: Job? = null
    private var scanGeneration = 0
    /** The reader already told, this process, that its unknown books were kept. */
    private var keptAnnouncedFor: String? = null

    // --- sync trace -------------------------------------------------------------
    // Declared ahead of init: init connects, and a connect records into these.

    /** The sync being recorded; null between syncs. */
    private var activeTrace: SyncTrace? = null
    /** The traces whose lines [UiState.syncTrace] and [UiState.previousSyncTrace] show. */
    private var shownTrace: SyncTrace? = null
    private var previousTrace: SyncTrace? = null
    /** Network work a sync started and does not wait for; its trace closes after this. */
    private val traceWork = mutableListOf<Job>()

    // --- phone dark mode --------------------------------------------------------
    // Also ahead of init, which registers the watcher.

    /** The phone's dark mode as last seen. */
    private var phoneDark = isNight(app.resources.configuration)
    /** One set_dark_mode decision at a time: a connect and a phone change can race. */
    private val darkModeLock = Mutex()

    /** Registered on the Application, so it hears changes with no Activity open. */
    private val configWatcher = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val dark = isNight(newConfig)
            if (dark == phoneDark) return
            phoneDark = dark
            // At once and on its own, not as a sync pass.
            viewModelScope.launch { matchPhoneDarkMode() }
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    // Declared before init: init starts collectors that run at once (the connection
    // flow emits IDLE and clears reader transfers), and a property declared below
    // init is still null when they do.
    /**
     * Transfers in flight, by owner, in the order they started. [UiState.transfer]
     * is one of them. Main thread only.
     *
     * One slot written by everyone was what made the bar jump: a firmware
     * download beside a sync had its bar replaced by each book's progress and
     * then CLEARED when the book ended (or when a Store request went idle), so
     * the bar fell back to the sync line until the next download step put it
     * back.
     */
    private val transfers = LinkedHashMap<String, TransferProgress>()
    private val calibreGate = ProgressGate()

    // --- reader books without a shelf copy ----------------------------------------
    // Above init like the rest: loadLibrary (called from init) reads bookLinks, and
    // the status collector can reach a sync pass, which starts a relink.

    /** Reader filename -> Calibre UUID, and the reader's last listing. */
    private val bookLinks = BookLinkStore(app)
    /** The catalogue as the last refresh() loaded it, before any shelf aliasing. Relinks match against it. */
    private var catalogueBooks: List<Book> = emptyList()
    private var relinkJob: Job? = null
    private var relinkAgain = false
    /** Reader filenames being fetched from Calibre onto the shelf. */
    private val relinking = mutableSetOf<String>()
    /** Reader filenames already looked up by a Calibre search this process, so a miss is searched once. */
    private val relinkSearched = mutableSetOf<String>()
    private val relinkLimit = Semaphore(RELINK_PARALLEL)
    /** Reader filenames being pulled off the reader and uploaded to Calibre. */
    private val addingToCalibre = mutableSetOf<String>()
    /** Uploaded, not yet seen in the catalogue: reader filename -> (title, author). */
    private val calibreAddsPending = mutableMapOf<String, Pair<String, String>>()
    /** Bonded readers the picker already paired with on its own this process; never twice. */
    private val autoPairTried = mutableSetOf<String>()
    private var autoPairJob: Job? = null

    init {
        app.registerComponentCallbacks(configWatcher)
        // PHY and priority reports arrive on Bluetooth threads.
        ble.onLinkEvent = { text ->
            viewModelScope.launch {
                traceNote("link", text)
                _state.value = _state.value.copy(
                    linkInfo = "mtu ${ble.negotiatedMtu}, phy ${ble.phy ?: "?"}, " +
                        "priority ${if (ble.fastLink) "high" else "balanced"}, " +
                        "l2cap ${if (ble.l2capOpen) "open" else "-"}",
                )
            }
        }
        // Every upload's timing: into the running sync's trace, and the big ones
        // into Diagnostics even when no sync is running (a firmware send waits for
        // the sync to end, so its trace has closed by then).
        ble.onUploadTimed = { t -> viewModelScope.launch { recordUpload(t) } }
        store.attach(viewModelScope)
        superviseLink()
        pollFirmware()

        viewModelScope.launch {
            val identity = pairingStore.load()
            // Read first, THEN copy. `_state.value.copy(x = suspendingCall())` takes
            // its snapshot before the call suspends, and writing it back would undo
            // whatever changed in the meantime.
            val config = settings.config.first()
            val storedName = runCatching { pairingStore.deviceName.first() }.getOrDefault("")
            _state.value = _state.value.copy(
                config = config,
                deviceName = storedName,
                permissionsGranted = BlePermissions.granted(getApplication()),
                pairing = if (identity != null) PairingState.TRUSTED else PairingState.UNPAIRED,
                hasStoredPairing = identity != null,
                storedHostId = identity?.hostId,
            )
            // Unless a sync has already finished since launch.
            runCatching { lastSyncStore.load() }.getOrNull()?.let { saved ->
                if (_state.value.lastSync == null) _state.value = _state.value.copy(lastSync = saved)
            }
            // Before the catalogue, and separately from it: the shelf is on
            // disk, so it must render on a launch with no network at all --
            // which is precisely the launch where an offline library is the
            // point. refresh() also calls this, to pick up reading progress.
            loadLibrary()
            refresh()
            // A phone the reader already trusts reconnects silently. Asking for
            // the six-digit code again on every launch would make the trusted
            // host pointless, so this is attempted before the user touches
            // anything — and it stays quiet if it fails, because failing to
            // find a reader that is simply switched off is not an error worth
            // a snackbar on launch.
            if (identity != null && BlePermissions.granted(getApplication())) {
                // Hand the watching to the system as well, so the reader waking
                // later wakes this app even if it has been closed by then.
                ReaderPresence.register(getApplication())
                connectReader(silent = true)
            }
        }

        viewModelScope.launch {
            ble.connection.collect { c ->
                if (c != BleConnection.CONNECTED) dropReaderTransfers()
                val s = _state.value
                _state.value = s.copy(
                    connection = c,
                    authorized = if (c == BleConnection.CONNECTED) s.authorized else false,
                    storeActivity = if (c == BleConnection.CONNECTED) s.storeActivity else null,
                    readerOpenBook = if (c == BleConnection.CONNECTED) s.readerOpenBook else null,
                    link = when (c) {
                        BleConnection.SCANNING -> LinkStatus(LinkStage.SCANNING)
                        BleConnection.CONNECTING -> LinkStatus(LinkStage.CONNECTING)
                        BleConnection.BONDING -> LinkStatus(
                            LinkStage.BONDING,
                            hint = "Enter the passkey shown on the reader",
                        )
                        // CONNECTED and IDLE are decided by the authorisation
                        // step, not by the transport, so they are left to
                        // connectReader() rather than guessed at here.
                        else -> s.link
                    },
                )
                // A link that is up but not authenticated is a dead end: the
                // reader shows no BLE, the Store says it needs a phone, and every
                // feature stays greyed out, while the app reports "connected"
                // because the transport genuinely is. Nothing retried, because
                // authorisation was only ever attempted from connectReader() --
                // and connectReader() short-circuits when the transport is
                // already up. So recover here, where the condition is visible.
                // Not during a pairing: that link is unauthorised on purpose until
                // pair and hello finish, and nothing is stored to authenticate with.
                if (c == BleConnection.CONNECTED && !_state.value.authorized && !pairingInProgress) {
                    reauthenticate()
                }
                // Keep the process alive for as long as the link is: closing the
                // app must not end a sync or the heartbeat kosync writes.
                // Not during a pairing either: nothing is stored yet, so the service
                // would find the app unpaired and stop itself. pairReader() starts it.
                if (c == BleConnection.CONNECTED && !pairingInProgress) ReaderSyncService.start(getApplication())
            }
        }

        viewModelScope.launch {
            ble.status.filterNotNull().collect { s ->
                _state.value = _state.value.copy(
                    device = s,
                    // A trimmed notification can drop the name while the book stays
                    // open, so the last name is kept for as long as `open` holds.
                    readerOpenBook = if (s.bookOpen) s.bookFilename ?: _state.value.readerOpenBook else null,
                    authTrace = ble.lastAuthTrace,
                    lastAuthError = ble.lastAuthError,
                    storeTrace = s.pending?.let { p ->
                        "saw req=${p.req} op=${p.op} off=${p.offset} lim=${p.limit} " +
                            "thumb=${p.thumbWidth}x${p.thumbHeight}"
                    } ?: _state.value.storeTrace,
                )
                // Follow the reader around the library. Every request names the
                // offset it is on, so the covers worth having ready are the ones
                // near it -- not the first sixty in the feed, which is all the
                // catalogue-load warm knows about.
                s.pending?.takeIf { it.isPage }?.let { p ->
                    val c = _state.value.config
                    prefetcher.warm(
                        _state.value.rows.map { it.book },
                        c.username,
                        c.password,
                        focus = p.offset,
                    )
                }
                // --- the heartbeat ------------------------------------------
                //
                // The reader volunteers where it is and what it holds, so most
                // of what the app needs arrives without asking. Two things come
                // of that:

                // 1. Position, free. Updating the row from the notification
                //    costs nothing -- no hash, no kosync GET, no BLE read --
                //    and it is the same number the reader shows, so the two
                //    cannot drift while the app waits for a sync to run.
                s.bookFilename?.let { filename ->
                    s.bookPercent?.let { pct ->
                        applyReaderPosition(filename, pct)
                        // A kept listing's position for this book is no longer current.
                        noteHeartbeatForListing(filename, pct)
                        // ...and published: the same ping is what keeps kosync
                        // current while reading, not just the row on screen.
                        queueHeartbeatPosition(filename, pct)
                    }
                }
                // Sleep ends a reading session. Publish now rather than leave the
                // last pages waiting out the throttle -- the reader goes quiet
                // after this notify and may not ping again for hours.
                if (s.sleeping) flushHeartbeatPositions()

                // 2. A full mirror only when the library actually differs.
                //    The fingerprint is name+size+mtime over /Books; if it
                //    matches what we last saw, nothing has been added, removed
                //    or replaced and the whole listing download can be skipped.
                s.libraryFingerprint?.let { fp ->
                    if (fp != lastLibraryFingerprint) {
                        val first = lastLibraryFingerprint == null
                        lastLibraryFingerprint = fp
                        // Not on the first sighting: connecting already runs a
                        // mirror, and firing a second one on the status that
                        // arrives moments later would double every connection.
                        //
                        // Nor for this app's own send or removal coming back: the
                        // kept listing already says so, and a mirror for it would
                        // be one more pass and one more listing download.
                        val echo = System.currentTimeMillis() < libraryEchoUntil
                        if (!first && !echo) {
                            invalidateReaderListing()
                            if (_state.value.connected && _state.value.authorized) mirrorToDevice()
                        }
                    }
                }

                // A replace refused with "book open" needs something to retry it.
                // Closing a book saves its position, and the reader notifies on
                // that, so the next status after the book is closed retries the send.
                // Throttled: while the book stays open, every page turn notifies.
                //
                // An owed resume refused the same way retries the same way, but at
                // once on the status that shows the book closing: the user may
                // reopen it within seconds and the position has to be there first.
                // Firmware that never reports `open` keeps the throttle only.
                val closedNow = lastStatusBookOpen && !s.bookOpen
                lastStatusBookOpen = s.bookOpen
                if ((bookOpenDeferredAt != 0L || owedResumeBlocked) &&
                    _state.value.connected && _state.value.authorized
                ) {
                    val now = System.currentTimeMillis()
                    if ((owedResumeBlocked && closedNow) || now - lastBookOpenRetryAt >= BOOK_OPEN_RETRY_MS) {
                        lastBookOpenRetryAt = now
                        // A positions pass runs the mirror too, so it covers both.
                        if (owedResumeBlocked) requestSync(positions = true) else mirrorToDevice()
                    }
                }
            }
        }
    }

    /**
     * Everything the refresh control should mean.
     *
     * The catalogue and the reader both, so the one obvious "bring things up to
     * date" button does the whole job.
     *
     * Order matters. The catalogue first, because it is the only step that can
     * work with the reader in a drawer and it is what the user is looking at.
     * Then the clock, because the reader has no network and every timestamp
     * written afterwards depends on it. Then books, then positions -- a
     * position is meaningless for a book that has not arrived yet.
     *
     * Reader settings are PULLED, never pushed. Writing them back costs a flash
     * erase on the device (see saveDeviceSettings), and a refresh must not
     * spend the device's flash to tell it what it already knows.
     */
    fun refreshEverything(): Job {
        // An explicit refresh is the user saying they do not trust the cached
        // answer, so the reader's library fingerprint is forgotten first.
        lastLibraryFingerprint = null
        invalidateReaderListing()
        kosyncSettled.clear()
        // No settings: the reader-settings screen loads them when it opens, and
        // nothing else reads them.
        return requestSync(catalogue = true)
    }

    /**
     * Pull to refresh, and the refresh button: Calibre, the firmware page and the
     * reader in one gesture. A paired reader that is not connected is connected
     * first; connecting runs its own sync and firmware check.
     */
    fun pullToRefresh(): Job {
        val s = _state.value
        if (s.hasStoredPairing && !s.connected && !s.link.busy) connectReader()
        checkFirmware()
        return refreshEverything()
    }

    /**
     * The reading position already known for a book, by filename.
     *
     * Free and always available: the Library rows carry it, and a book in the
     * Store that is also on the shelf is the same book. Without it a book you
     * are halfway through would show nothing in the Store while the Library
     * shows 43%.
     *
     * Only ever a FALLBACK for a freshly fetched value -- never an override --
     * so a real kosync answer still wins where one was fetched.
     */
    private fun knownProgressFor(filename: String): Progress? =
        _state.value.library.firstOrNull { it.book.filename == filename }?.progress
            ?: _state.value.rows.firstOrNull { it.book.filename == filename }?.progress

    // ------------------------------------------------------------- settings

    fun saveConfig(c: Config) = viewModelScope.launch {
        c.urlProblem?.let {
            _state.value = _state.value.copy(message = it)
            return@launch
        }
        settings.save(c)
        _state.value = _state.value.copy(config = c, message = "Settings saved")
        refresh()
    }

    fun dismissRemoveOpenPrompt() {
        _state.value = _state.value.copy(removeOpenPrompt = null)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun onPermissionsResult(granted: Boolean) {
        _state.value = _state.value.copy(
            permissionsGranted = granted,
            link = if (granted) _state.value.link
            else LinkStatus(
                LinkStage.NEEDS_PERMISSION,
                reason = "Bluetooth permission is not granted",
                hint = BlePermissions.denialMessage,
            ),
        )
        if (granted) {
            viewModelScope.launch { ReaderPresence.register(getApplication()) }
            connectReader()
        }
    }

    // ---------------------------------------------------------- reader link

    /**
     * Scan, connect and authorise the paired reader: its stored address only,
     * then a v2 hello whose reader_proof must verify. Unpaired, nothing is
     * scanned and the UI asks the user to pair ([pairReader]).
     *
     * [silent] suppresses the snackbar on failure. Used for the automatic
     * attempt at launch, where "no reader found" means "the reader is in a
     * drawer", not "something went wrong".
     */
    fun connectReader(silent: Boolean = false): Job {
        // One attempt at a time. Startup, the link watchdog, the permission result and
        // every presence broadcast can all ask at once, and BleClient.connect() begins by
        // tearing down whatever link exists -- so a second attempt killed the first, the
        // first's cleanup killed the second, and every retry started another scan until
        // Android throttled this app's scans and the reader could not be found at all.
        connectJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
        if (!linkPreflight()) return@launch

        val identity = pairingStore.load()
        val address = identity?.address
        if (identity == null || address.isNullOrBlank()) {
            _state.value = _state.value.copy(
                pairing = PairingState.UNPAIRED,
                link = LinkStatus(LinkStage.NEEDS_PAIRING, reason = null, hint = PAIR_HINT),
            )
            return@launch
        }

        _state.value = _state.value.copy(
            permissionsGranted = true,
            message = null,
            link = LinkStatus(LinkStage.SCANNING),
        )

        // Never bonds here: a background reconnect must not raise a pairing dialog.
        val connectStarted = System.currentTimeMillis()
        val outcome = runCatching { ble.connect(address, allowBond = false) }
        val status = outcome.getOrElse { e ->
            failLink(e, silent)
            return@launch
        }
        // Recorded only once the link is up: the watchdog retries every few seconds
        // while the reader is away, and those attempts are not syncs.
        val trace = beginTrace(startedAt = connectStarted)
        trace.add(
            "connect", connectStarted, System.currentTimeMillis() - connectStarted,
            note = "mtu ${ble.negotiatedMtu}",
        )
        publishTrace(trace)

        if (identity.deviceId != null && status.deviceId != identity.deviceId) {
            closeTraceWhenIdle(trace)
            ble.disconnect()
            _state.value = _state.value.copy(
                authorized = false,
                link = LinkStatus(
                    LinkStage.FAILED,
                    reason = "A different reader",
                    hint = "Forget pairing to pair it",
                ),
            )
            return@launch
        }

        _state.value = _state.value.copy(link = LinkStatus(LinkStage.PAIRING))
        val ok = runCatching {
            traced<Boolean>("auth", note = { if (it) "ok" else "refused" }) { ble.authenticate(identity) }
        }.getOrDefault(false)
        if (!ok) closeTraceWhenIdle(trace)
        if (ok) {
            onAuthorized(silent)
        } else if (readerForgotPhone()) {
            onReaderForgotPhone(silent)
        } else {
            _state.value = _state.value.copy(
                authorized = false,
                authTrace = ble.lastAuthTrace,
                lastAuthError = ble.lastAuthError,
                link = LinkStatus(
                    LinkStage.FAILED,
                    reason = "Authorisation failed",
                    hint = "Forget pairing, then pair again",
                ),
            )
        }
        }.also { connectJob = it }
    }

    /**
     * The last hello was refused because the reader has no record of this phone.
     * Only the reader's explicit auth_error counts; link errors never do.
     */
    private fun readerForgotPhone(): Boolean = ble.lastAuthFailure?.let { it in FORGOTTEN_CODES } == true

    /**
     * The reader forgot this phone: the stored pairing is useless, so clear it
     * and stop watching for the reader. The Android bond is left for the user.
     */
    private suspend fun onReaderForgotPhone(silent: Boolean) {
        // Before the pairing is cleared: deviceKey() falls back to it.
        dropOwedRemovals(deviceKey())
        pairingStore.clear()
        ReaderPresence.unregister(getApplication())
        ble.disconnect()
        invalidateReaderListing()
        loadLibrary(withProgress = false)
        val link = LinkStatus(LinkStage.NEEDS_PAIRING, "The reader forgot this phone", FORGOTTEN_HINT)
        _state.value = _state.value.copy(
            hasStoredPairing = false,
            storedHostId = null,
            authorized = false,
            pairing = PairingState.UNPAIRED,
            readerBookNames = emptySet(),
            authTrace = ble.lastAuthTrace,
            lastAuthError = ble.lastAuthError,
            link = link,
            message = if (silent) null else link.reason,
        )
    }

    /**
     * Starts the pairing picker's scan: readers advertising the transfer service,
     * listed live for [BleClient.DISCOVERY_MS]. Calling it again scans afresh.
     *
     * Leaves the link and its stage alone on purpose. Looking is not connecting,
     * and the reader pill must not appear until a reader has been chosen.
     */
    fun startReaderScan() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        if (!BlePermissions.granted(getApplication())) {
            _state.value = _state.value.copy(
                permissionsGranted = false,
                readerScan = ReaderScan(error = BlePermissions.denialMessage),
            )
            return
        }
        if (!ble.bluetoothEnabled()) {
            _state.value = _state.value.copy(
                readerScan = ReaderScan(error = "Bluetooth is off. Turn it on, then scan again."),
            )
            return
        }
        _state.value = _state.value.copy(readerScan = ReaderScan(scanning = true))
        // Android keeps its bond through an app reinstall. A reader it is bonded with
        // that is advertising now is the one this phone paired with before.
        val bonded = ble.bondedAddresses()
        scanJob = viewModelScope.launch {
            var error: String? = null
            try {
                ble.discoverReaders().collect { readers ->
                    val scan = _state.value.readerScan
                    if (generation == scanGeneration && scan != null) {
                        _state.value = _state.value.copy(readerScan = scan.copy(readers = readers))
                        if (bonded.isNotEmpty()) pairBondedReader(readers, bonded, generation)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = (e as? BleClient.BleException)?.message ?: "Bluetooth would not scan"
            } finally {
                val scan = _state.value.readerScan
                if (generation == scanGeneration && scan != null) {
                    _state.value = _state.value.copy(readerScan = scan.copy(scanning = false, error = error))
                }
            }
        }
    }

    /**
     * Skips the picker when the scan finds exactly one reader this phone is still
     * bonded with: pairs with it directly. Waits [AUTO_PAIR_SETTLE_MS] for a second
     * bonded reader first, and with two leaves the choice to the user. A reader is
     * tried this way once per process, so a failed attempt does not repeat when the
     * picker opens again.
     */
    private fun pairBondedReader(readers: List<DiscoveredReader>, bonded: Set<String>, generation: Int) {
        if (autoPairJob?.isActive == true) return
        if (readers.none { it.address in bonded && it.address !in autoPairTried }) return
        autoPairJob = viewModelScope.launch {
            delay(AUTO_PAIR_SETTLE_MS)
            if (generation != scanGeneration) return@launch
            val only = _state.value.readerScan?.readers.orEmpty()
                .filter { it.address in bonded && it.address !in autoPairTried }
                .singleOrNull() ?: return@launch
            autoPairTried += only.address
            pairReader(only.address, only.name)
        }
    }

    /** Stops the picker's scan and forgets its list. Safe when none is running. */
    fun stopReaderScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        if (_state.value.readerScan != null) _state.value = _state.value.copy(readerScan = null)
    }

    /**
     * Pairs with the reader the user picked, at [address], whose Settings screen
     * is open (security v2).
     *
     * Connects to that address and no other, bonds (Android's dialog takes the
     * passkey the reader shows), sends `pair` only on a bonded link while the
     * reader's pairing window is open, then a hello. The pairing -- identity, the
     * reader's device_id and Bluetooth address -- is stored only once
     * reader_proof verifies, and the paired state is then published in ONE write.
     *
     * [advertisedName] names the pill while this runs, and the reader afterwards
     * until its own settings name it.
     */
    fun pairReader(address: String, advertisedName: String? = null): Job {
        connectJob?.takeIf { it.isActive }?.let { return it }
        stopReaderScan()
        val name = advertisedName?.trim()?.take(16)?.ifBlank { null } ?: "Reader"
        pairingInProgress = true
        return viewModelScope.launch {
            try {
                if (!linkPreflight()) return@launch
                if (pairingStore.load() != null) {
                    _state.value = _state.value.copy(message = "Forget the current pairing first")
                    return@launch
                }
                _state.value = _state.value.copy(
                    permissionsGranted = true,
                    message = null,
                    pairingReaderName = name,
                    link = LinkStatus(LinkStage.CONNECTING),
                )

                val status = runCatching { ble.connect(address, allowBond = true) }.getOrElse { e ->
                    failLink(e, silent = false)
                    return@launch
                }
                val connectedTo = ble.connectedAddress()
                val deviceId = status.deviceId
                if (connectedTo == null || !connectedTo.equals(address, ignoreCase = true) || deviceId == null) {
                    ble.disconnect()
                    failLink(
                        BleClient.BleException("The reader did not identify itself", reason = BleClient.Reason.NOT_A_READER),
                        silent = false,
                    )
                    return@launch
                }
                _state.value = _state.value.copy(link = LinkStatus(LinkStage.PAIRING))
                val identity = pairingStore.mint().copy(deviceId = deviceId, address = connectedTo)
                // Sent with the pairing window closed too: a reader with `pair_prompt`
                // then asks its user, and older firmware refuses with "pairing window
                // closed", which reads exactly as before.
                // pair() returns only when the reader confirmed paired == true; anything
                // else throws, and no hello is sent after an unconfirmed pair.
                val paired = try {
                    ble.pair(identity, onConfirmOnReader = {
                        _state.value = _state.value.copy(
                            pairingConfirm = true,
                            link = LinkStatus(LinkStage.PAIRING, reason = CONFIRM_ON_READER_TEXT),
                        )
                    })
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e
                }
                if (_state.value.pairingConfirm) _state.value = _state.value.copy(pairingConfirm = false)
                paired?.let { e ->
                    ble.disconnect()
                    when ((e as? BleClient.BleException)?.code) {
                        "pairing window closed" -> _state.value = _state.value.copy(
                            link = LinkStatus(LinkStage.NEEDS_PAIRING, reason = "Pairing is closed on the reader", hint = PAIR_CLOSED_HINT),
                            lastAuthError = ble.lastAuthError,
                        )
                        BleClient.PAIRING_DENIED -> _state.value = _state.value.copy(
                            authorized = false,
                            authTrace = ble.lastAuthTrace,
                            lastAuthError = ble.lastAuthError,
                            link = LinkStatus(LinkStage.FAILED, reason = PAIRING_DECLINED_TEXT, hint = PAIR_HINT),
                            message = PAIRING_DECLINED_TEXT,
                        )
                        else -> failLink(e, silent = false)
                    }
                    return@launch
                }
                val ok = runCatching { ble.authenticate(identity) }.getOrDefault(false)
                if (!ok) {
                    ble.disconnect()
                    val why = ble.lastAuthFailure?.let { "Pairing failed: $it" } ?: "Pairing failed"
                    _state.value = _state.value.copy(
                        authorized = false,
                        authTrace = ble.lastAuthTrace,
                        lastAuthError = ble.lastAuthError,
                        link = LinkStatus(LinkStage.FAILED, reason = why, hint = PAIR_CLOSED_HINT),
                        message = why,
                    )
                    return@launch
                }

                pairingStore.save(identity)
                runCatching { pairingStore.setDeviceName(name) }
                linkFailStreak = 0
                // Everything the UI reads as "paired and connected", in one write. The
                // link watchers stay held off by pairingInProgress until this job ends,
                // so nothing can slip a stale or half-paired state in behind it.
                _state.value = _state.value.copy(
                    hasStoredPairing = true,
                    storedHostId = identity.hostId,
                    pairing = PairingState.TRUSTED,
                    authorized = true,
                    connection = ble.connection.value,
                    link = LinkStatus(LinkStage.CONNECTED),
                    deviceName = name,
                    pairingReaderName = null,
                    authTrace = ble.lastAuthTrace,
                    lastAuthError = ble.lastAuthError,
                    message = "Paired",
                )
                ReaderPresence.register(getApplication())
                // The CONNECTED this pairing caused did not start the service (nothing
                // was stored then), and the link will not change again to start it now.
                ReaderSyncService.start(getApplication())
                startConnectedWork()
            } finally {
                pairingInProgress = false
                if (_state.value.pairingReaderName != null || _state.value.pairingConfirm) {
                    _state.value = _state.value.copy(pairingReaderName = null, pairingConfirm = false)
                }
            }
        }.also { connectJob = it }
    }

    /** Cancel on the "Confirm on the reader" dialog: stop waiting and drop the link. */
    fun cancelPairing() {
        if (!pairingInProgress) return
        connectJob?.cancel()
        ble.disconnect()
        _state.value = _state.value.copy(
            pairingConfirm = false,
            pairingReaderName = null,
            link = LinkStatus(LinkStage.IDLE),
        )
    }

    /** Permission and radio checks before any scan. False when the link cannot start. */
    private fun linkPreflight(): Boolean {
        if (!BlePermissions.granted(getApplication())) {
            _state.value = _state.value.copy(
                permissionsGranted = false,
                link = LinkStatus(
                    LinkStage.NEEDS_PERMISSION,
                    reason = "Bluetooth permission is not granted",
                    hint = BlePermissions.denialMessage,
                ),
            )
            return false
        }
        if (!ble.bluetoothEnabled()) {
            _state.value = _state.value.copy(
                link = LinkStatus(
                    LinkStage.BLUETOOTH_OFF,
                    reason = "Bluetooth is off",
                    hint = "Turn Bluetooth on, then connect.",
                ),
            )
            return false
        }
        return true
    }

    /** A session whose reader_proof verified: mark it and start what a connection does. */
    private fun onAuthorized(silent: Boolean) {
        linkFailStreak = 0
        // A new session may be a different reader or new firmware.
        aboutFeatures = null
        aboutDoc = null
        ble.setDownloadCapabilities(null, windowed = false)
        invalidateReaderListing()
        _state.value = _state.value.copy(
            authorized = true,
            pairing = PairingState.TRUSTED,
            link = LinkStatus(LinkStage.CONNECTED),
            authTrace = ble.lastAuthTrace,
            message = if (silent) null
            else "Connected to ${_state.value.deviceName.ifBlank { "the reader" }}",
        )
        startConnectedWork()
    }

    /** What every newly authorised session does: clock, shelf, catalogue, firmware. */
    private fun startConnectedWork() {
        beginTrace()
        viewModelScope.launch {
            // The reader has no clock of its own worth trusting; tell it the
            // time and the zone before anything else uses a timestamp.
            runCatching { traced("set time") { ble.setDeviceTime() } }
            // The shelf is the contract: whatever is saved offline belongs on the
            // reader, so a fresh connection is the moment to make that true.
            requestSync(positions = true)
            // The phone may have switched while the app was closed.
            trackWork(viewModelScope.launch { matchPhoneDarkMode() })
            refreshCatalogueIfStale()
            checkFirmwareOnConnect()
            resumeInterruptedFirmware()
        }
    }

    // --- transfer bar ------------------------------------------------------------


    /**
     * The one to show: a firmware image once it has a size holds the bar for its
     * whole download and send; a Calibre refresh and a firmware send still waiting
     * on the sync give way to anything else; otherwise the one that started first,
     * so a later job does not push a running bar aside.
     */
    private fun shownTransfer(): TransferProgress? = transfers.values.maxByOrNull { p ->
        when {
            p.owner == OWNER_FIRMWARE -> if (p.total > 0) 2 else 0
            p.owner == OWNER_CALIBRE -> 0
            else -> 1
        }
    }

    /**
     * Shows or updates [p] under its owner; [also] rides in the same state write.
     * Locked because book and Calibre downloads report from an IO thread; their
     * reports are synchronous, so none can land after the owner's [endTransfer].
     */
    private fun showTransfer(p: TransferProgress, also: (UiState) -> UiState = { it }) = synchronized(transfers) {
        transfers[p.owner] = p
        _state.value = also(_state.value.copy(transfer = shownTransfer()))
    }

    /** Ends [owner]'s transfer and only that one; the bar goes back to whatever else is running. */
    private fun endTransfer(owner: String, also: (UiState) -> UiState = { it }) = synchronized(transfers) {
        transfers.remove(owner)
        _state.value = also(_state.value.copy(transfer = shownTransfer()))
    }

    /**
     * The link is gone: Bluetooth-only transfers cannot still be running. Each
     * ends itself as it fails; this is the backstop. Phone-side downloads (a
     * firmware image, a book, Calibre) carry on without the reader.
     */
    private fun dropReaderTransfers() = synchronized(transfers) {
        transfers.keys.removeAll { it == OWNER_STORE || it == OWNER_SETTINGS || it == OWNER_CRASH || it.startsWith("book:") || it.startsWith("pull:") }
        _state.value = _state.value.copy(transfer = shownTransfer())
    }

    /** [BleClient.onUploadTimed]: a trace step, and the Diagnostics line for a book or firmware. */
    private fun recordUpload(t: BleClient.UploadTiming) {
        val name = "upload " + t.kind
        activeTrace?.let {
            it.add(name, t.startedAt, t.totalMs, t.bytes, t.note())
            publishTrace(it)
        }
        if (t.kind == "firmware" || t.kind == "book") {
            val line = SyncTrace.clockLine(t.startedAt, name, t.bytes, t.totalMs, t.note())
            _state.value = _state.value.copy(lastTransfer = line)
            // The reader's own measurements of the same upload, read while the link is up.
            if (t.error == null && _state.value.connected && _state.value.authorized) {
                viewModelScope.launch { appendReaderUploadStats(line, t) }
            }
        }
    }

    /** Appends the reader's `about` link and last_upload numbers to [line] while it is still the one shown. */
    private suspend fun appendReaderUploadStats(line: String, t: BleClient.UploadTiming) {
        val about = readAbout(fresh = true).getOrNull() ?: return
        val note = readerUploadNote(about, t) ?: return
        if (_state.value.lastTransfer == line) {
            _state.value = _state.value.copy(lastTransfer = "$line · reader: $note")
        }
    }

    /**
     * "itvl 7.5-15 ms (req 7.5 ms accepted), dl 251/251, phy 2M, msys min 2, acl min 6, queue 40,
     * sd 3.1 s, loop 4.0 s, gap 180 ms, 62/s avg 70/s max, …" from `about`. The interval is
     * the range seen during the upload (`last_upload`), or the one in force now on firmware
     * that does not report it. `last_upload` counts only when it is this upload (same kind
     * and bytes). Null when the firmware reports neither.
     */
    private fun readerUploadNote(about: JSONObject, t: BleClient.UploadTiming): String? {
        val parts = mutableListOf<String>()
        val l = about.optJSONObject("link")
        val u = about.optJSONObject("last_upload")
            ?.takeIf { it.optString("kind") == t.kind && it.optLong("bytes", -1L) == t.bytes }
        fun msText(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        fun rangeText(o: JSONObject, min: String, max: String): String {
            val lo = o.optDouble(min)
            val hi = o.optDouble(max)
            return if (lo == hi) msText(lo) else "${msText(lo)}-${msText(hi)}"
        }
        val itvl = when {
            u != null && u.has("itvl_min_ms") && u.has("itvl_max_ms") -> rangeText(u, "itvl_min_ms", "itvl_max_ms")
            l != null && l.has("interval_ms") -> msText(l.optDouble("interval_ms"))
            else -> null
        }
        val requested = (u?.optJSONObject("requested") ?: l?.optJSONObject("requested"))?.let { r ->
            "req ${rangeText(r, "min_ms", "max_ms")} ms ${r.optString("result")}"
        }
        if (itvl != null) parts += "itvl $itvl ms" + (requested?.let { " ($it)" } ?: "")
        if (l != null) {
            if (l.has("tx_octets") && l.has("rx_octets")) {
                parts += "dl ${l.optInt("tx_octets")}/${l.optInt("rx_octets")}" +
                    if (l.optBoolean("dl_reported", true)) "" else " (default)"
            }
            l.optString("phy").ifBlank { null }?.let { parts += "phy $it" }
        }
        if (u != null) {
            fun ms(key: String) = SyncTrace.duration(u.optLong(key))
            // The reader's own view of which transport carried it. Shown beside
            // the app's so a disagreement is visible rather than guessed at.
            u.optString("transport").ifBlank { null }?.let { parts += "transport $it" }
            if (u.has("min_msys_free")) parts += "msys min ${u.optInt("min_msys_free")}"
            if (u.has("min_acl_free")) parts += "acl min ${u.optInt("min_acl_free")}"
            if (u.has("max_queue")) parts += "queue ${u.optInt("max_queue")}"
            if (u.has("sd_ms")) parts += "sd ${ms("sd_ms")}"
            if (u.has("loop_ms")) parts += "loop ${ms("loop_ms")}"
            if (u.has("max_gap_ms")) parts += "gap ${ms("max_gap_ms")}"
            if (u.has("frames_per_s_avg") && u.has("frames_per_s_max")) {
                parts += "${u.optInt("frames_per_s_avg")}/s avg ${u.optInt("frames_per_s_max")}/s max"
            }
            if (u.has("ack_notify_avg_ms")) {
                parts += "ack→notify avg ${ms("ack_notify_avg_ms")} max ${ms("ack_notify_max_ms")}"
            }
            if (u.has("tick_gap_max_ms")) parts += "tick gap ${ms("tick_gap_max_ms")}"
            if (u.has("renders")) parts += "renders ${u.optInt("renders")} (${ms("render_ms")})"
            val failed = u.optInt("notify_failed")
            val shed = u.optInt("ack_shed")
            if (failed > 0 || shed > 0) parts += "notify failed $failed, shed $shed"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    // --- sync trace -------------------------------------------------------------

    /** What was shown before the running trace took over; put back if it records nothing. */
    private var shownBefore: Pair<SyncTrace?, SyncTrace?> = null to null

    /** Starts recording a sync, or returns the one already recording. */
    private fun beginTrace(startedAt: Long = System.currentTimeMillis()): SyncTrace {
        activeTrace?.let { return it }
        val t = SyncTrace(startedAt)
        activeTrace = t
        traceWork.clear()
        shownBefore = shownTrace to previousTrace
        if (shownTrace != null) previousTrace = shownTrace
        shownTrace = t
        showTraces()
        return t
    }

    private fun showTraces() {
        _state.value = _state.value.copy(
            syncTrace = shownTrace?.lines().orEmpty(),
            previousSyncTrace = previousTrace?.lines().orEmpty(),
        )
    }

    private fun publishTrace(t: SyncTrace) {
        when {
            t === shownTrace -> _state.value = _state.value.copy(syncTrace = t.lines())
            t === previousTrace -> _state.value = _state.value.copy(previousSyncTrace = t.lines())
        }
    }

    private fun traceNote(name: String, note: String) {
        val t = activeTrace ?: return
        t.event(name, note)
        publishTrace(t)
    }

    /** Runs [block] as one step of the sync being recorded; just runs it when none is. */
    private suspend fun <T> traced(
        name: String,
        bytes: (T) -> Long = { 0L },
        note: (T) -> String = { "" },
        block: suspend () -> T,
    ): T {
        val t = activeTrace ?: return block()
        val started = System.currentTimeMillis()
        try {
            val result = block()
            t.add(name, started, System.currentTimeMillis() - started, bytes(result), note(result))
            publishTrace(t)
            return result
        } catch (e: Throwable) {
            val why = if (e is CancellationException) "cancelled"
            else "failed: " + (e.message ?: e.javaClass.simpleName).take(60)
            t.add(name, started, System.currentTimeMillis() - started, 0L, why)
            publishTrace(t)
            throw e
        }
    }

    /** Network work the sync being recorded started and does not wait for. */
    private fun trackWork(job: Job?) {
        if (job != null && activeTrace != null) traceWork += job
    }

    /** Closes [t] once the sync and the work it started have finished. */
    private fun closeTraceWhenIdle(t: SyncTrace) {
        viewModelScope.launch {
            while (activeTrace === t) {
                val waiting = traceWork.filter { it.isActive } + listOfNotNull(syncJob?.takeIf { it.isActive })
                if (waiting.isEmpty()) break
                waiting.joinAll()
            }
            if (activeTrace === t) {
                t.finish()
                activeTrace = null
                traceWork.clear()
                // A pass that did nothing (a heartbeat with nothing to send) does not
                // push the last real sync out of view.
                if (t.isEmpty() && shownTrace === t) {
                    shownTrace = shownBefore.first
                    previousTrace = shownBefore.second
                    showTraces()
                } else {
                    publishTrace(t)
                }
            }
        }
    }

    /**
     * Turns a connection failure into a stage plus something to do about it.
     *
     * Each case is genuinely different: an off radio needs the user's settings,
     * a missing reader needs waking, a wrong device needs a different device.
     * Collapsing them into "could not connect" is what makes a first run feel broken.
     */
    private fun failLink(e: Throwable, silent: Boolean) {
        val reason = (e as? BleClient.BleException)?.reason ?: BleClient.Reason.OTHER
        val repairHint = if (_state.value.hasStoredPairing) "Forget pairing, then pair again" else PAIR_HINT
        val baseStatus = when (reason) {
            BleClient.Reason.NO_ADAPTER -> LinkStatus(
                LinkStage.FAILED,
                "This phone has no Bluetooth",
                "Bluetooth LE is required.",
            )
            BleClient.Reason.BLUETOOTH_OFF -> LinkStatus(
                LinkStage.BLUETOOTH_OFF,
                "Bluetooth is off",
                "Turn Bluetooth on, then connect.",
            )
            BleClient.Reason.SCAN_FAILED -> LinkStatus(
                LinkStage.FAILED,
                "Bluetooth would not scan",
                "Wait a moment and retry.",
            )
            BleClient.Reason.NOT_FOUND -> LinkStatus(
                LinkStage.FAILED,
                "Reader not found",
                "Make sure it's awake and nearby.",
            )
            BleClient.Reason.NOT_A_READER -> LinkStatus(
                LinkStage.FAILED,
                "That device is not a Bluecarrel reader",
                "Move closer to the reader and retry.",
            )
            BleClient.Reason.AUTH ->
                if ((e as? BleClient.BleException)?.code == "pairing window closed") {
                    LinkStatus(LinkStage.NEEDS_PAIRING, "Pairing is closed on the reader", PAIR_CLOSED_HINT)
                } else {
                    LinkStatus(LinkStage.FAILED, e.message ?: "The reader refused this phone", repairHint)
                }
            BleClient.Reason.BOND -> LinkStatus(
                LinkStage.FAILED,
                e.message ?: "Pairing did not finish",
                repairHint,
            )
            BleClient.Reason.OLD_FIRMWARE -> LinkStatus(
                LinkStage.FAILED,
                "This reader needs Bluecarrel firmware",
                "Install it from github.com/jaymart1983/bluecarrel-firmware",
            )
            else -> LinkStatus(
                LinkStage.FAILED,
                e.message ?: "Could not reach the reader",
                "Make sure the reader is awake.",
            )
        }
        // The phone's Bluetooth stack can wedge (after an app update, typically): the
        // reader is found and the link opens, then drops within two seconds, every
        // time, until Bluetooth is turned off and on. Retrying cannot fix that, so after
        // a few link-level failures in a row, say what will.
        val linkLevel = reason == BleClient.Reason.LINK || reason == BleClient.Reason.OTHER ||
            reason == BleClient.Reason.SCAN_FAILED
        linkFailStreak = if (linkLevel) linkFailStreak + 1 else 0
        val status = if (linkFailStreak >= LINK_FAIL_HINT_AFTER) {
            LinkStatus(LinkStage.FAILED, "Bluetooth isn't connecting", "Turn Bluetooth off and on")
        } else {
            baseStatus
        }
        _state.value = _state.value.copy(
            link = status,
            authorized = false,
            authTrace = ble.lastAuthTrace,
            lastAuthError = ble.lastAuthError,
            // With no reader pill (unpaired), this message is the only place the
            // failure shows, so it carries the what-to-do line too.
            message = if (silent) null
            else listOfNotNull(status.reason, status.hint?.takeIf { it.isNotBlank() }).joinToString(". "),
        )
    }

    fun disconnectReader() = viewModelScope.launch {
        ble.disconnect()
        dropReaderTransfers()
        _state.value = _state.value.copy(
            authorized = false,
            storeActivity = null,
            link = LinkStatus(LinkStage.IDLE),
        )
    }

    /**
     * Clears any stored pairing, stops watching for the reader, disconnects and
     * opens Bluetooth settings. Works with or without a stored pairing: a pairing
     * that failed halfway leaves nothing stored but an Android bond. The bond
     * itself stays -- removing it takes a hidden API -- so the user removes it there.
     */
    fun forgetPairing() = viewModelScope.launch {
        connectJob?.cancel()
        // Before the pairing is cleared: deviceKey() falls back to it.
        dropOwedRemovals(deviceKey())
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        runCatching { pairingStore.clear() }
        ReaderPresence.unregister(getApplication())
        ble.disconnect()
        invalidateReaderListing()
        loadLibrary(withProgress = false)
        linkFailStreak = 0
        _state.value = _state.value.copy(
            hasStoredPairing = false,
            storedHostId = null,
            authorized = false,
            pairing = PairingState.UNPAIRED,
            link = LinkStatus(LinkStage.IDLE),
            pairingReaderName = null,
            readerBookNames = emptySet(),
            message = "Remove the reader here, then pair again",
        )
    }

    /**
     * Which reader the "sent" history belongs to. Prefers the live status, but
     * falls back to the paired identity so the flags are right before the
     * first connection of a session rather than reading an "unknown" bucket.
     */
    private suspend fun deviceKey(): String? =
        _state.value.device?.deviceId ?: pairingStore.load()?.deviceId

    /**
     * The reader-facing half of a position sync, and nothing that waits on the
     * network: resumes the user chose that the reader has not confirmed, and
     * server positions an earlier kosync pass found for it, in one `progress`
     * batch. Then, when [runKosync], the kosync half ([runKosyncPass]) starts
     * beside whatever the sync does next.
     */
    private suspend fun positionsPass(runKosync: Boolean) {
        if (!_state.value.connected || !_state.value.authorized) return
        if (_state.value.syncingProgress) return
        _state.value = _state.value.copy(syncingProgress = true)
        try {
            val entries = readerLibrary(forPositions = true)
            if (entries == null) {
                tally?.problems?.add("library not read")
                _state.value = _state.value.copy(message = "Could not read the reader's library")
                return
            }

            // Resumes the user chose that the reader has not confirmed. See OwedResumeStore.
            val readerKey = deviceKey()
            val owed = owedResumes.load(readerKey)
            val freshStarts = startFresh.load(readerKey)
            val nowSeconds = System.currentTimeMillis() / 1000
            val toDevice = mutableListOf<JSONObject>()
            // filename -> the timestamp its owed resume went out with in this batch.
            val owedSent = mutableMapOf<String, Long>()
            // Entries the owed-resume rules leave to the kosync pass.
            val forKosync = mutableListOf<JSONObject>()

            for (entry in entries) {
                val filename = entry.optString("filename").ifBlank { null } ?: continue

                // OWED RESUME, ahead of everything that needs a saved position: a book
                // sent and never opened has no `location` or `timestamp` at all, and it
                // is exactly the book a resume is owed to.
                //
                // Sent only while the reader is below OWED_RESUME_UNREAD (2%). Below
                // that the book has at most been opened, and the user's explicit answer
                // outranks it. At or above it the user has read on the reader since, so
                // the choice is dropped and the ordinary rules apply.
                //
                // Stamped past the reader's own save, because the reader keeps a
                // position only when its timestamp is strictly newer. The server row's
                // set_at is days old and loses to a save made by merely opening the
                // book -- which is how "resume at 89.5%" stayed at 0.1%.
                val owedHere = owed[filename]
                if (owedHere != null) {
                    val readerPct = entry.optDouble("percent", 0.0).toFloat()
                    val readerSavedAt = entry.optLong("timestamp", 0L)
                    if (owedHere.appliedAt > 0L) {
                        // Delivered. While the reader's save is still the one this app
                        // wrote, it is the server's own position coming back: never
                        // publish it as a new reading event. Any other save is the
                        // user's, and from then on the ordinary rules apply.
                        if (readerSavedAt == owedHere.appliedAt) continue
                        owedResumes.forget(readerKey, filename)
                    } else if (readerPct < OWED_RESUME_UNREAD) {
                        val stamp = maxOf(nowSeconds, readerSavedAt + 1)
                        toDevice += owedHere.positionJson(stamp).put("filename", filename)
                        owedSent[filename] = stamp
                        continue
                    } else {
                        owedResumes.forget(readerKey, filename)
                    }
                }
                forKosync += entry
            }

            // Server positions the last kosync pass found for the reader. Not for a
            // book owed a resume or restarted since; the reader keeps a position only
            // when it is newer than its own, so a late one cannot drag it back.
            val pulled = serverPositionsForReader.toMap()
            serverPositionsForReader.clear()
            val listed = entries.mapNotNull { it.optString("filename").ifBlank { null } }.toSet()
            for ((filename, position) in pulled) {
                if (filename in owedSent || filename in freshStarts || filename !in listed) continue
                toDevice += position
            }

            if (toDevice.isNotEmpty()) {
                val batch = JSONArray().apply { toDevice.forEach { put(it) } }.toString().toByteArray(Charsets.UTF_8)
                lastProgressResults = null
                val upload = runCatching {
                    traced<BleClient.UploadResult>("positions", bytes = { it.bytes }, note = { "${toDevice.size} books" }) {
                        ble.uploadBytes(batch, kind = "progress")
                    }
                }
                if (upload.isFailure) {
                    pulled.forEach { (f, p) -> if (f !in serverPositionsForReader) serverPositionsForReader[f] = p }
                }
                noteSyncPositions(toDevice.size, upload.exceptionOrNull())
                var owedNotice: String? = null
                if (owedSent.isNotEmpty()) {
                    val shelf = withContext(Dispatchers.IO) { books.cachedBooks() }.associateBy { it.filename }
                    owedNotice = settleOwedResumes(readerKey, owedSent, owed, upload.exceptionOrNull(), shelf)
                }
                afterPositionBatch(owedSent, owed, othersSent = toDevice.size > owedSent.size, failed = upload.isFailure)
                // Quiet inside a sync: one sync, one bar, no trailing report. The one
                // exception is a resume waiting on the user to close the book.
                if (owedNotice != null) _state.value = _state.value.copy(message = owedNotice)
            }
            if (owedSent.isEmpty()) {
                // Nothing owed was eligible: none left, or each one was dropped above.
                owedResumeDue = false
                owedResumeBlocked = false
            }
            if (runKosync) startKosyncPass(forKosync, readerKey)
        } finally {
            _state.value = _state.value.copy(syncingProgress = false)
        }
    }

    /** A position batch of [count] entries, for the sync bar's summary. */
    private fun noteSyncPositions(count: Int, failure: Throwable?) {
        val run = tally ?: return
        when {
            failure == null -> run.positions += count
            (failure as? BleClient.BleException)?.code == "book open" -> run.problems += "book open on reader"
            else -> run.problems += "positions not sent"
        }
    }

    /**
     * A resume the user chose, sent the moment its book has committed: before
     * the removals, the positions step and any network work, so the first open
     * on the reader lands on it. Not after a replace -- the reader may hold real
     * reading there, and the positions step's 2% rule decides.
     */
    private suspend fun deliverOwedResumeNow(filename: String) {
        if (!_state.value.connected || !_state.value.authorized) return
        val readerKey = deviceKey()
        val owed = owedResumes.load(readerKey)
        val owedHere = owed[filename]?.takeIf { it.appliedAt == 0L } ?: return
        val entry = readerEntries?.firstOrNull { it.optString("filename") == filename }
        if ((entry?.optDouble("percent", 0.0) ?: 0.0).toFloat() >= OWED_RESUME_UNREAD) return
        val stamp = maxOf(System.currentTimeMillis() / 1000, (entry?.optLong("timestamp", 0L) ?: 0L) + 1)
        val batch = JSONArray().put(owedHere.positionJson(stamp).put("filename", filename))
            .toString().toByteArray(Charsets.UTF_8)
        lastProgressResults = null
        val upload = runCatching {
            traced<BleClient.UploadResult>("position", bytes = { it.bytes }, note = { "resume" }) {
                ble.uploadBytes(batch, kind = "progress")
            }
        }
        noteSyncPositions(1, upload.exceptionOrNull())
        val shelf = withContext(Dispatchers.IO) { books.cachedBooks() }.associateBy { it.filename }
        val sent = mapOf(filename to stamp)
        val notice = settleOwedResumes(readerKey, sent, owed, upload.exceptionOrNull(), shelf)
        afterPositionBatch(sent, owed, othersSent = false, failed = upload.isFailure)
        if (notice != null) _state.value = _state.value.copy(message = notice)
    }

    /**
     * Keeps the listing current after a position batch without downloading it
     * again: an owed resume the reader reports `applied` is written into its
     * entry, which is what the owed-resume rules compare against next time.
     * Anything the app cannot account for marks positions stale instead, so the
     * next positions step reads the listing afresh.
     */
    private fun afterPositionBatch(
        owedSent: Map<String, Long>,
        owed: Map<String, OwedResume>,
        othersSent: Boolean,
        failed: Boolean,
    ) {
        val results = lastProgressResults
        if (othersSent || failed || (owedSent.isNotEmpty() && results == null)) readerPositionsStale = true
        if (failed || results == null) return
        for ((name, stamp) in owedSent) {
            if (results[name] != "applied") continue
            val pct = owed[name]?.percentage ?: continue
            listingSetPosition(name, stamp, pct)
        }
    }

    /** Server positions a kosync pass found that the reader should get; sent by the next positions step. */
    private val serverPositionsForReader = mutableMapOf<String, JSONObject>()
    private var kosyncJob: Job? = null
    /**
     * "reader/filename" -> the reader save a kosync pass last settled for that
     * book, and when. A book whose reader save is unchanged is not looked up again
     * within [KOSYNC_RECHECK_MS]. The server row has no cheap check, so the window
     * is what bounds how late another client's newer position is seen; Refresh
     * clears it.
     */
    private val kosyncSettled = mutableMapOf<String, Pair<String, Long>>()

    /** Starts [runKosyncPass] beside the sync, unless one is already running. */
    private fun startKosyncPass(entries: List<JSONObject>, readerKey: String?) {
        lastPositionSyncAt = System.currentTimeMillis()
        val c = _state.value.config
        if (c.username.isBlank() || !c.serverConfigured) return
        if (kosyncJob?.isActive == true) return
        val job = viewModelScope.launch { runKosyncPass(entries, readerKey) }
        kosyncJob = job
        trackWork(job)
    }

    /**
     * Publishes the reader's reading positions to kosync. Network only: the
     * reader is never waited on here, and nothing on the reader waits on this.
     *
     * ## Where the numbers come from
     *
     * The reader's `library` listing already carries, per book: `location` (the
     * saved position byte for byte), `timestamp` (the UTC epoch that position
     * was written, from the device's own progress.time sidecar) and `percent`.
     * Nothing is derived or guessed here; this walks that listing.
     *
     * ## The conflict rule, and why it is not the obvious one
     *
     * The obvious rule -- write if the reader's save is newer than the row's
     * timestamp -- is WRONG against CWA. `kosync.py:665` stamps every row with
     * `datetime.now(timezone.utc)` on receipt and throws away whatever the
     * client sent, so a bulk import lands hundreds of rows stamped "now" that
     * are really years old. Compared against those, a fresh reader save looks
     * older and would be suppressed on every book.
     *
     * So age comes from `set_at` inside the progress payload, which both this
     * app and any other well-behaved writer set for exactly this reason, and
     * falls back to the receive time only when the payload has none. See
     * [Progress.ageStamp].
     *
     * ## What it will not do
     *
     * It never moves a position BACKWARDS. A newer save that is earlier in the
     * book is not published: the timestamp rule answers "who wrote last", which
     * is the wrong question when a sync jams and a stale position arrives late.
     * See the forward-only check below.
     *
     * It never writes a position the device could not timestamp. An unstamped
     * save means the reader's clock was unset, and a position of unknown age
     * cannot be shown to be newer than anything -- writing it would be a
     * last-writer-wins overwrite dressed up as a decision.
     */
    private suspend fun runKosyncPass(entries: List<JSONObject>, readerKey: String?) {
        val c = _state.value.config
        val kosync = KosyncClient(http, c.kosyncUrl, c.username, c.password)
        val deviceId = readerKey ?: "x4pro"
        val deviceName = _state.value.deviceName.ifBlank { "X4 Pro" }
        // Read once for the whole pass rather than per book: a DataStore round trip.
        val freshStarts = startFresh.load(readerKey)
        val cachedShelf = withContext(Dispatchers.IO) { books.cachedBooks() }.associateBy { it.filename }
        val startedAt = System.currentTimeMillis()

        class Candidate(
            val filename: String,
            val local: Book,
            val hash: String,
            val location: String,
            val savedAt: Long,
            val percent: Float,
        ) {
            val memo: String get() = "$savedAt|$percent|$location"
        }

        var skippedNoClock = 0
        var sideLoaded = 0
        var missingFile = 0
        var unchanged = 0
        val candidates = mutableListOf<Candidate>()
        for (entry in entries) {
            val filename = entry.optString("filename").ifBlank { null } ?: continue
            val location = entry.optString("location").ifBlank { null } ?: continue
            // Omitted rather than zeroed by the device when unknown -- see
            // BookLibraryIndex::writeEntry. Absent means the clock was unset.
            val savedAt = entry.optLong("timestamp", 0L)
            if (savedAt < Progress.PLAUSIBLE_EPOCH) {
                skippedNoClock++
                continue
            }
            // Side-loaded books keep their progress to themselves.
            //
            // `fromApp` is the reader's own answer, from whether the phone's
            // metadata sidecar sits beside the book -- not a guess from what
            // this phone happens to be holding today. A book copied on over USB
            // has no Calibre original behind it, so there is nothing on the
            // server for its position to belong to.
            //
            // Older firmware omits the field. Absent is treated as "from the
            // app", because that was the only way a book could arrive before
            // USB Drive existed.
            if (!entry.optBoolean("fromApp", true)) {
                sideLoaded++
                continue
            }
            // The kosync key is the partial-MD5 of the FILE, so the bytes have
            // to be here too. `fromApp` says a Calibre original exists; it does
            // not say this phone still has the copy.
            val local = cachedShelf[filename]
            if (local == null) {
                missingFile++
                continue
            }
            val hash = heartbeatHash(local)
            if (hash == null) {
                missingFile++
                continue
            }
            val candidate = Candidate(
                filename, local, hash, location, savedAt, entry.optDouble("percent", 0.0).toFloat(),
            )
            val settled = kosyncSettled["$readerKey/$filename"]
            if (settled != null && settled.first == candidate.memo && startedAt - settled.second < KOSYNC_RECHECK_MS) {
                unchanged++
                continue
            }
            candidates += candidate
        }

        // Every lookup at once, PREFETCH_PARALLEL in flight: two round trips per
        // book through Cloudflare, one after another, was most of a sync.
        val remotes: Map<String, Progress?> = if (candidates.isEmpty()) emptyMap() else {
            traced<Map<String, Progress?>>("kosync lookups", note = { "${candidates.size} books, $unchanged unchanged" }) {
                coroutineScope {
                    candidates.chunked(PREFETCH_PARALLEL).flatMap { chunk ->
                        chunk.map { cand ->
                            async { cand.filename to kosync.progressFor(cand.local.progressKey, cand.hash, deviceName, deviceId) }
                        }.awaitAll()
                    }.toMap()
                }
            }
        }

        var skippedOlder = 0
        var skippedNotAhead = 0
        val puts = mutableListOf<Candidate>()
        val pulls = mutableMapOf<String, JSONObject>()
        val settledNow = mutableListOf<Candidate>()
        for (cand in candidates) {
            val filename = cand.filename
            val savedAt = cand.savedAt
            val percent = cand.percent
            val remote = remotes[filename]
            if (remote != null && remote.ageStamp >= savedAt) {
                skippedOlder++
                settledNow += cand
                continue
            }

            // FORWARD ONLY. Never publish a position earlier than the one on the
            // server, even when this device's save is genuinely newer.
            //
            // The timestamp rule alone decides who wrote last, not who is
            // further on, and "last" is the wrong question when a sync jams: a
            // stale position that arrives late is still newer by the clock and
            // would drag every other client back to it.
            //
            // The reverse direction, decided with the same two facts. The reader
            // gets a position only when the server's is NEWER and NOT BEHIND.
            // No `location` is sent: another reading system's position encoding
            // indexes a file this reader will never hold. The spine fields are the
            // part that means anything here, and the reader ignores them unless its
            // own spine count matches `spine_n`.
            //
            // A book the user chose to restart is not pushed back to its old
            // position. The instruction is spent as soon as the reader has a
            // position of its own.
            if (filename in freshStarts) {
                if (percent > 0f) startFresh.forget(readerKey, filename)
            } else if (remote != null && remote.ageStamp > savedAt) {
                val payload = remote.payloadJson()
                val spine = payload?.optInt("spine", -1) ?: -1
                val spineN = payload?.optInt("spine_n", 0) ?: 0
                val remotePct = remote.percentage
                if (remotePct + 0.00005f >= percent) {
                    pulls[filename] = JSONObject().apply {
                        put("filename", filename)
                        put("timestamp", remote.ageStamp)
                        // Sent with the position, not left to the device: the
                        // reader's library screen reads its percentage out of the
                        // sidecar written here.
                        put("pct", remotePct.coerceIn(0f, 1f).toDouble())
                        if (spine >= 0 && spineN > 0) {
                            put("spine", spine)
                            put("spine_frac", payload?.optDouble("spine_frac", 0.0) ?: 0.0)
                            put("spine_n", spineN)
                        }
                    }
                }
            }

            // Compared at STORAGE precision, and blocking only a strictly lower
            // value. `percent` is the device's own figure, rounded to four
            // decimals; `remote.percentage` has been through CWA's 0-100
            // conversion and back, so raw floats would let noise decide.
            // Strictly-lower, because reaching here already means this device's
            // save is NEWER: rewriting an equal percentage with a corrected
            // timestamp is how a stale row gets repaired.
            val mine = Math.round(percent * 10_000f) / 10_000f
            val theirs = Math.round(remote?.percentage?.times(10_000f) ?: 0f) / 10_000f
            if (remote != null && mine < theirs) {
                skippedNotAhead++
                settledNow += cand
                continue
            }
            puts += cand
        }

        var written = 0
        if (puts.isNotEmpty()) {
            val landed = traced<List<Candidate>>("kosync puts", note = { "${it.size} of ${puts.size} written" }) {
                coroutineScope {
                    puts.chunked(PREFETCH_PARALLEL).flatMap { chunk ->
                        chunk.map { cand ->
                            async {
                                cand.takeIf {
                                    kosync.putProgressFor(
                                        stableKey = cand.local.progressKey,
                                        contentHash = cand.hash,
                                        position = cand.location,
                                        percentage = cand.percent,
                                        setAt = cand.savedAt,
                                        deviceName = deviceName,
                                        deviceId = deviceId,
                                    )
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
            }
            written = landed.size
            // A failed write is not settled, so the next pass tries it again.
            settledNow += landed
        }
        for (cand in settledNow) kosyncSettled["$readerKey/${cand.filename}"] = cand.memo to startedAt
        lastPositionSyncAt = System.currentTimeMillis()
        traceNote(
            "kosync",
            "written $written, current $skippedOlder, not ahead $skippedNotAhead, unchanged $unchanged, " +
                "unstamped $skippedNoClock, not on phone $missingFile, side-loaded $sideLoaded",
        )

        // ---------------------------------------------------------------- pull
        //
        // What the SERVER knows and the reader does not goes to the reader in the
        // next positions step, which this asks for.
        if (pulls.isNotEmpty() && _state.value.connected && _state.value.authorized) {
            serverPositionsForReader.putAll(pulls)
            requestSync(positions = true)
        }

        // Positions moved, so the percentages on the shelf are stale.
        loadLibrary().join()
    }

    // ---------------------------------------------------------------- store

    /**
     * What the Store responder needs to answer one request, or null when
     * Calibre is not configured — in which case it declines with
     * `catalog_error` instead of letting the reader time out.
     */
    private fun storeContext(): StoreContext? {
        val c = _state.value.config
        if (!c.serverConfigured) return null
        return StoreContext(
            opds = OpdsClient(http, c.opdsUrl, c.username, c.password),
            baseUrl = c.opdsUrl,
            user = c.username,
            pass = c.password,
            feedPath = FEED_PATH,
        )
    }

    private fun onStoreEvent(event: StoreEvent) {
        _state.value = _state.value.copy(
            storeTrace = _state.value.storeTrace + "\n-> " + event.toString().take(120),
        )
        when (event) {
            is StoreEvent.Idle ->
                endTransfer(OWNER_STORE) { it.copy(storeActivity = null) }

            is StoreEvent.Serving ->
                _state.value = _state.value.copy(
                    storeActivity = "Reader asked for ${event.detail}"
                )

            is StoreEvent.Progress ->
                showTransfer(TransferProgress(event.label, event.sent, event.total, owner = OWNER_STORE))

            is StoreEvent.Answered ->
                endTransfer(OWNER_STORE) {
                    it.copy(storeActivity = null, message = "Sent ${event.detail} to the reader's Store")
                }

            is StoreEvent.Declined ->
                endTransfer(OWNER_STORE) {
                    it.copy(storeActivity = null, message = "Told the reader we could not answer: ${event.reason}")
                }
        }
    }

    // -------------------------------------------------------------- catalog

    /**
     * Loads the catalog and decorates each book with reading progress and
     * local state.
     *
     * Progress lookup uses CONTENT hashing, which is the only id CWA's sync
     * server can match, and which needs the file itself. Books that have not
     * been downloaded therefore show no progress rather than wrong progress.
     *
     * There is deliberately no "what is on the reader" step. The BLE protocol
     * has no list-files operation, so the only truthful answer available is
     * "what this app has sent", which is what [BookRow.sentFromThisApp] says.
     */
    fun refresh() = viewModelScope.launch {
        val c = _state.value.config
        if (!c.serverConfigured) {
            // Not configured: nothing to load. Never covers a message already showing.
            _state.value = _state.value.copy(
                loading = false,
                message = _state.value.message
                    ?: if (c.serverUrl.isBlank()) "Set the server URL in Settings" else HTTPS_REQUIRED,
            )
            return@launch
        }
        _state.value = _state.value.copy(loading = true, message = null)

        val result = runCatching {
            traced<List<Book>>("catalogue", note = { "${it.size} books" }) {
                OpdsClient(http, c.opdsUrl, c.username, c.password).feed(FEED_PATH)
            }
        }

        result.onSuccess { fetched ->
            catalogueBooks = fetched
            // A book the shelf holds under another name (a relinked reader file, or a
            // Calibre rename) is the same book: it is shown and updated under that name.
            val feedBooks = aliasToShelf(fetched)
            val kosync = KosyncClient(http, c.kosyncUrl, c.username, c.password)
            val cachedNames = books.cachedNames()
            val sent = sentBooks.load(deviceKey())
            val canSync = c.username.isNotBlank()
            val deviceName = kosyncDeviceName()
            val deviceId = kosyncDeviceId()
            val cachedCount = feedBooks.count { cachedNames.contains(it.filename) }
            // PREFETCH_PARALLEL lookups at a time, not one book after another.
            val rows = traced<List<BookRow>>("catalogue progress", note = { "$cachedCount books" }) {
                coroutineScope {
                    feedBooks.chunked(PREFETCH_PARALLEL).flatMap { chunk ->
                        chunk.map { book ->
                            async {
                                val cached = cachedNames.contains(book.filename)
                                // CONTENT hashing needs the bytes, so only a downloaded book
                                // has an id the server can match. See [KoreaderHash].
                                val hash = if (cached) heartbeatHash(book) else null
                                BookRow(
                                    book = book,
                                    progress = (
                                        if (canSync && hash != null) {
                                            kosync.progressFor(book.progressKey, hash, deviceName, deviceId)
                                        } else null
                                        ) ?: knownProgressFor(book.filename),
                                    sentFromThisApp = book.filename in sent,
                                    cached = cached,
                                )
                            }
                        }.awaitAll()
                    }
                }
            }
            _state.value = _state.value.copy(rows = rows, loading = false)
            lastCatalogueAt = System.currentTimeMillis()
            // Did Calibre change a book this phone already holds?
            //
            // Compared against what was remembered when the book was saved: the
            // entry's <updated> time and its size. Either moving means the file
            // on the server is no longer the file on this phone or the reader.
            // Launched, not awaited: an update is a download, and the catalogue
            // the user just asked for should not wait behind it.
            val stored = withContext(Dispatchers.IO) { books.cachedBooks() }.associateBy { it.filename }
            val changed = feedBooks.filter { feed -> stored[feed.filename]?.let { calibreChanged(it, feed) } == true }
            backfillCalibreStamps(stored, feedBooks, changed)
            if (changed.isNotEmpty()) {
                changed.filter { c -> calibreQueue.none { it.filename == c.filename } }
                    .let { calibreQueue.addAll(it) }
                // Nothing waits on this refresh: a running sync earns one more
                // lap for the changes, and otherwise this starts one.
                requestSync()
            }

            // The shelf does not come from the feed, but a catalogue refresh is
            // a good moment to pick up reading progress the reader has synced.
            loadLibrary()
            // Reader books with no shelf copy, matched against what just loaded.
            settleCalibreAdds(fetched)
            startRelink()
            // Warm the reader's two geometries now, off the critical path. Not
            // gated on the reader being connected: the point is to be ready
            // before it asks, and it may ask seconds after it connects.
            prefetcher.warm(rows.map { it.book }, c.username, c.password)
        }.onFailure { e ->
            _state.value = _state.value.copy(
                loading = false,
                message = "Catalog failed: ${e.message}",
            )
        }
    }

    fun setTab(tab: AppTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    /**
     * Searches the whole Calibre library, or reloads the newest when blank.
     *
     * Search rather than scroll is the point of the Store: a large library is
     * not something to page through on a phone, and definitely not something to
     * mirror onto the reader. Only what is saved offline reaches the device.
     */
    fun runSearch() = viewModelScope.launch {
        val c = _state.value.config
        val q = _state.value.query.trim()
        if (q.isEmpty()) {
            refresh()
            return@launch
        }
        if (!c.serverConfigured) {
            _state.value = _state.value.copy(
                message = if (c.serverUrl.isBlank()) "Set the server URL in Settings" else HTTPS_REQUIRED,
            )
            return@launch
        }
        _state.value = _state.value.copy(searching = true, message = null)
        val found = runCatching {
            OpdsClient(http, c.opdsUrl, c.username, c.password).search(q)
        }
        _state.value = found.fold(
            onSuccess = { list ->
                val cachedNames = books.cachedNames()
                val sent = sentBooks.load(deviceKey())
                _state.value.copy(
                    searching = false,
                    rows = aliasToShelf(list).map { book ->
                        BookRow(
                            book = book,
                            // Hashing every search hit would mean opening each
                            // file; the shelf already knows, so ask it.
                            progress = knownProgressFor(book.filename),
                            sentFromThisApp = book.filename in sent,
                            cached = cachedNames.contains(book.filename),
                        )
                    },
                    message = if (list.isEmpty()) "Nothing matched \"$q\"" else null,
                )
            },
            onFailure = { _state.value.copy(searching = false, message = "Search failed: ${it.message}") },
        )
    }

    /**
     * The offline shelf, as last read off the disk.
     *
     * Cheap and synchronous because the work happened in [loadLibrary]; the UI
     * and [mirrorToDevice] both read this rather than recomputing, which would
     * mean hitting the filesystem from inside composition.
     */
    fun libraryRows(): List<BookRow> = _state.value.library

    /**
     * Rebuilds the offline shelf from the books directory.
     *
     * Not a filter of [UiState.rows] — the Store's current contents — which
     * would empty the Library on every search. The shelf is a property of the
     * disk, so it is read from the disk.
     *
     * Ordering: currently reading first — a book with real progress that is not
     * finished is the one you are most likely to want — then most recently
     * saved. That is the order the reader shows, so the two read the same way
     * round.
     *
     * @param withProgress fetch reading progress as well as the shelf. Each book
     *        costs a full-content hash (it reads the whole file) plus a kosync
     *        round trip, so a shelf of any size takes seconds. Pass false when
     *        the caller only needs to know WHICH books are on the shelf --
     *        notably the mirror, which should not pay a hash and a network call
     *        per book before sending the one book the user just saved.
     *        Progress already known is carried forward, so the rows do not
     *        visibly lose their percentages.
     */
    fun loadLibrary(withProgress: Boolean = true) = viewModelScope.launch {
        val c = _state.value.config
        val sent = sentBooks.load(deviceKey())
        // Books whose local copy is only still here so the row can say it is
        // waiting on the reader. See PendingRemovalStore.
        val owed = pendingRemovals.load(deviceKey())
        val canSync = c.username.isNotBlank() && c.serverConfigured && withProgress
        val kosync = KosyncClient(http, c.kosyncUrl, c.username, c.password)
        val known = _state.value.library.associate { it.book.filename to it.progress }

        val saved = withContext(Dispatchers.IO) { books.cachedBooks() }
        // filename -> the server's position, for each book that could be hashed.
        // PREFETCH_PARALLEL lookups at a time, not one book after another.
        val fetched: Map<String, Progress?> = if (!canSync || saved.isEmpty()) emptyMap() else {
            val deviceName = kosyncDeviceName()
            val deviceId = kosyncDeviceId()
            traced<Map<String, Progress?>>("shelf progress", note = { "${saved.size} books" }) {
                coroutineScope {
                    saved.chunked(PREFETCH_PARALLEL).flatMap { chunk ->
                        chunk.map { book ->
                            async {
                                // CONTENT hashing needs the bytes, which by definition we have.
                                heartbeatHash(book)?.let { hash ->
                                    book.filename to kosync.progressFor(book.progressKey, hash, deviceName, deviceId)
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }.toMap()
                }
            }
        }
        // Books on the reader with no copy here: from this session's listing, or the
        // one kept from the last, so they show before the reader has connected.
        val shelfNames = saved.map { it.filename }.toSet()
        val listing = readerEntries?.map { JSONObject(it.toString()) }
            ?: runCatching { bookLinks.listing(deviceKey()) }.getOrDefault(emptyList())
        val onlyOnReader = listing.mapNotNull { e ->
            val name = e.optString("filename").ifBlank { null } ?: return@mapNotNull null
            if (name in shelfNames) null else readerOnlyRow(e, name, name in owed, known[name])
        }.distinctBy { it.book.filename }
        val rows = (saved.map { book ->
            BookRow(
                book = book,
                progress = if (fetched.containsKey(book.filename)) fetched[book.filename] else known[book.filename],
                sentFromThisApp = book.filename in sent,
                cached = true,
                pendingRemoval = book.filename in owed,
            )
        } + onlyOnReader).sortedWith(
            // Books on their way out sink to the bottom -- they are leaving, so
            // they should not sit above books the user is actually keeping.
            compareBy<BookRow> { row -> row.pendingRemoval }.thenByDescending { row ->
                val p = row.progress?.percentage ?: 0f
                p > 0f && p < 0.99f
            }.thenByDescending { row ->
                row.progress?.takeIf { it.percentage > 0f }?.timestamp ?: 0L
            }.thenByDescending { row -> books.cachedAt(row.book) }
        )
        _state.value = _state.value.copy(library = rows)
    }

    /**
     * Re-stamps both lists' `cached` / `sent` flags from what is now on disk.
     *
     * Flags only: it never adds or removes a row. That is what makes it the
     * right tool after an upload, where the only thing that changed is a
     * bookkeeping bit. Cheaper than [refresh] and, unlike it, has no opinion
     * about what the Store should be listing — saving or sending a book must not
     * throw away the search the user is looking at. Cheaper than [loadLibrary]
     * too: no kosync round trip per book, which during a mirror would otherwise
     * be one sweep of the whole shelf per book pushed.
     */
    private suspend fun restampRows() {
        val cachedNames = withContext(Dispatchers.IO) { books.cachedNames() }
        val sent = sentBooks.load(deviceKey())
        fun stamp(row: BookRow) = if (row.onReaderOnly) row else row.copy(
            cached = cachedNames.contains(row.book.filename),
            sentFromThisApp = row.book.filename in sent,
        )
        _state.value = _state.value.copy(
            rows = _state.value.rows.map(::stamp),
            library = _state.value.library.map(::stamp),
        )
    }

    /** Asks the sync runner for a pass; see [requestSync]. */
    fun mirrorToDevice(): Job = requestSync()

    /**
     * One pass of the shelf mirror: send what the reader lacks, remove what it
     * should not have. No bar of its own and no loop of its own -- the sync runner
     * owns both, and folds any request that arrives mid-pass into one more pass.
     */
    private suspend fun mirrorPass(): Boolean {
        // The shelf is the contract, so read it now rather than trusting a copy
        // that may predate the save that triggered this. Shelf only: what goes to
        // the reader does not depend on how far it has been read.
        loadLibrary(withProgress = false).join()
        // Not a book still coming down from Calibre: its file is not final yet.
        val pending = libraryRows().filter {
            !it.onReaderOnly && !it.sentFromThisApp && it.book.filename !in calibreUpdating && it.book.filename !in relinking
        }
        // Covers come from Calibre, so they are fetched beside the sends, never ahead of them.
        warmCovers(pending.map { it.book })
        var sentAny = false
        for (row in pending) {
            if (!_state.value.connected || !_state.value.authorized) break
            // Checked here, not when the list was built: the question can be
            // raised after this pass read the shelf. cacheBook sends it once answered.
            if (row.book.filename in resumeUndecided) continue
            sendToDevice(row).join()
            sentAny = true
        }
        // No early return on an empty shelf: removals the user made are still owed.
        // The prune deletes those and nothing else -- see pruneDeviceBooks.
        val removed = pruneDeviceBooks()
        tally?.let { it.removed += removed }
        if (removed > 0) {
            _state.value = _state.value.copy(
                message = "Removed $removed book${if (removed == 1) "" else "s"} from the reader",
            )
        }
        return sentAny
    }

    private var syncJob: Job? = null
    private var syncPending = false
    private var syncWantCatalogue = false
    private var syncWantPositions = false
    private var syncWantSettings = false
    /** Calibre changes waiting for the current or next sync pass. */
    private val calibreQueue = mutableListOf<Book>()

    /**
     * THE sync. Every trigger comes through here: connecting, the refresh button,
     * a library change the reader reports, saving or removing a book, a Calibre
     * update, a retry after "book open".
     *
     * One runner and one bar for the whole job: a request that arrives while it
     * is running does not start another sync, it earns one more pass of this one.
     *
     * [catalogue] also refreshes the Calibre catalogue (and so notices Calibre
     * changes); [settings] also re-reads the reader's settings. Both are sticky
     * across coalesced requests, so asking for more never gets less.
     */
    fun requestSync(catalogue: Boolean = false, settings: Boolean = false, positions: Boolean = false): Job {
        syncPending = true
        if (catalogue) syncWantCatalogue = true
        if (positions) syncWantPositions = true
        if (settings) syncWantSettings = true
        syncJob?.takeIf { it.isActive }?.let { return it }
        val trace = beginTrace()
        val job = viewModelScope.launch {
            val run = SyncTally()
            tally = run
            _state.value = _state.value.copy(syncingLibrary = true, syncStatus = "Syncing with reader\u2026")
            // The shortest connection interval for the whole sync, not per request.
            ble.holdFastLink()
            try {
                while (syncPending) {
                    syncPending = false
                    val cat = syncWantCatalogue
                    syncWantCatalogue = false
                    val set = syncWantSettings
                    syncWantSettings = false
                    val pos = syncWantPositions
                    syncWantPositions = false
                    runSyncPass(cat, set, pos)
                }
            } catch (e: Throwable) {
                run.error = if (e is CancellationException) "cancelled"
                else (e.message ?: e.javaClass.simpleName).take(40)
                throw e
            } finally {
                ble.releaseFastLink()
                tally = null
                val summary = syncSummary(run)
                _state.value = _state.value.copy(syncingLibrary = false, syncStatus = null, lastSync = summary)
                if (summary != null && run.linked) viewModelScope.launch { runCatching { lastSyncStore.save(summary) } }
                closeTraceWhenIdle(trace)
            }
        }
        syncJob = job
        return job
    }

    /** The sync running now; see [SyncTally]. */
    private var tally: SyncTally? = null

    /**
     * The line the sync bar keeps once [run] is over. The one already shown when
     * [run] never reached the reader, or found nothing to do moments after a sync
     * that did something: that result is the one worth reading.
     */
    private fun syncSummary(run: SyncTally): SyncSummary? {
        val previous = _state.value.lastSync
        if (!run.linked) return previous
        val now = System.currentTimeMillis()
        fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"
        val parts = mutableListOf<String>()
        if (run.booksSent > 0) parts += count(run.booksSent, "book") + " sent"
        if (run.removed > 0) parts += "${run.removed} removed"
        if (run.positions > 0) parts += count(run.positions, "position") + " moved"
        if (run.booksFailed > 0) parts += "${run.booksFailed} not sent"
        if (run.removeFailed > 0) parts += "${run.removeFailed} not removed"
        parts += run.problems
        val result = when {
            !_state.value.connected || !_state.value.authorized -> SyncSummary.Result.STOPPED
            run.error != null -> SyncSummary.Result.STOPPED
            run.booksFailed > 0 || run.removeFailed > 0 || run.problems.isNotEmpty() -> SyncSummary.Result.INCOMPLETE
            else -> SyncSummary.Result.DONE
        }
        val detail = when {
            !_state.value.connected || !_state.value.authorized -> "reader disconnected"
            run.error != null -> run.error!!
            parts.isEmpty() -> "up to date"
            else -> parts.joinToString(", ")
        }
        val summary = SyncSummary(now, now - run.startedAt, result, detail, changed = parts.isNotEmpty())
        if (result == SyncSummary.Result.DONE && !summary.changed && previous != null &&
            previous.result == SyncSummary.Result.DONE && previous.changed &&
            now - previous.finishedAt < SYNC_SUMMARY_HOLD_MS
        ) return previous
        return summary
    }

    /**
     * One pass. The reader's steps run back to back and never wait on the
     * network: the books (each followed at once by a resume the user chose for
     * it), the removals, then the positions batch -- all off one library listing,
     * see [readerLibrary]. Server work starts beside them and is not awaited: the
     * catalogue, Calibre changes, covers and kosync. What that work finds for the
     * reader (a replaced book, a server position) asks for one more pass.
     */
    private suspend fun runSyncPass(catalogue: Boolean, settings: Boolean, positions: Boolean) {
        // A refresh that finds Calibre changes asks for another pass itself.
        if (catalogue) trackWork(refresh())
        val linked = _state.value.connected && _state.value.authorized
        if (linked) tally?.linked = true
        if (linked) resendShelfOnce()

        val changes = calibreQueue.toList()
        calibreQueue.clear()
        if (changes.isNotEmpty()) startCalibreUpdates(changes)

        if (!_state.value.connected || !_state.value.authorized) return
        // Only when asked for: nothing in a sync reads the reader's settings.
        if (settings) runCatching { loadDeviceSettings(force = true).join() }

        val sentAny = mirrorPass()
        // The listing is in hand now: fetch Calibre's copy of reader books the shelf lacks, beside the sync.
        startRelink()

        // Positions when the pass was a full one or something was just sent, not
        // on every small trigger: a page-turn-driven pass has nothing to add.
        val recent = System.currentTimeMillis() - lastPositionSyncAt < LISTING_TTL_MS
        // An owed resume skips the throttle: this pass is what it is waiting for.
        val due = sentAny || (positions && owedResumeDue) || ((catalogue || positions) && !recent)
        if (due || serverPositionsForReader.isNotEmpty()) positionsPass(runKosync = due)
    }

    /**
     * Answers the resume prompt and lets the blocked save carry on.
     *
     * "Start again" is recorded rather than acted on immediately: the position
     * sync would otherwise push the server's saved position to the reader
     * within seconds and undo the choice. See [StartFreshStore].
     */
    fun answerResume(resume: Boolean) = viewModelScope.launch {
        val prompt = _state.value.resumePrompt ?: return@launch
        _state.value = _state.value.copy(resumePrompt = null)
        if (resume) {
            startFresh.forget(deviceKey(), prompt.filename)
        } else {
            startFresh.add(deviceKey(), prompt.filename)
            owedResumes.forget(deviceKey(), prompt.filename)
        }
        // A resume is recorded by cacheBook, which holds the server position.
        resumeAnswer?.complete(resume)
        resumeAnswer = null
    }

    /**
     * Records "resume" as owed to the reader. See [OwedResumeStore].
     *
     * The reader needs a spine jump to act on a position from here, and rows
     * this app writes carry only a percentage, so the jump is worked out from
     * the EPUB on this phone ([EpubSpine]). A spine the server row carries is
     * preferred when it was measured on a file with the same spine count.
     * With neither, nothing is owed: the reader could not apply it.
     */
    private suspend fun recordOwedResume(book: Book, saved: Progress) {
        val payload = saved.payloadJson()
        val rowSpineN = payload?.optInt("spine_n", 0) ?: 0
        val rowSpine = payload?.optInt("spine", -1) ?: -1
        val fromRow = if (rowSpineN > 0 && rowSpine in 0 until rowSpineN) {
            SpineJump(rowSpine, (payload?.optDouble("spine_frac", 0.0) ?: 0.0).toFloat(), rowSpineN)
        } else null
        val derived = withContext(Dispatchers.IO) { EpubSpine.jumpFor(books.fileFor(book), saved.percentage) }
        val jump = when {
            fromRow != null && (derived == null || derived.count == fromRow.count) -> fromRow
            else -> derived
        }
        if (jump == null) {
            owedResumes.forget(deviceKey(), book.filename)
            return
        }
        owedResumes.put(
            deviceKey(),
            OwedResume(
                filename = book.filename,
                percentage = saved.percentage,
                spine = jump.spine,
                spineFraction = jump.fraction,
                spineCount = jump.count,
                chosenAt = System.currentTimeMillis() / 1000,
            ),
        )
        owedResumeDue = true
    }

    /**
     * Settles the owed resumes a position batch carried. One is cleared only
     * when the reader reports it `applied`. Returns the one line worth showing.
     */
    private suspend fun settleOwedResumes(
        readerKey: String?,
        sent: Map<String, Long>,
        owed: Map<String, OwedResume>,
        failure: Throwable?,
        shelf: Map<String, Book>,
    ): String? {
        if ((failure as? BleClient.BleException)?.code == "book open") {
            // Still owed; the status stream retries when the book closes. Said once.
            val first = !owedResumeBlocked
            owedResumeBlocked = true
            owedResumeDue = true
            if (!first) return null
            val name = sent.keys.first()
            val title = shelf[name]?.title ?: name
            val pct = Progress(
                document = name,
                percentage = owed[name]?.percentage ?: return null,
                device = "",
                timestamp = 0L,
            ).percentLabel
            return "Close \"$title\" on the reader to move it to $pct"
        }
        owedResumeBlocked = false
        owedResumeDue = false
        // Any other failure: still owed, and the next positions pass tries again.
        if (failure != null) return null
        val results = progressResults() ?: return null
        for ((name, stamp) in sent) {
            when (results[name]) {
                "applied" -> owedResumes.markApplied(readerKey, name, stamp)
                // Nothing the reader could ever act on: stop sending it.
                "invalid", "not_found", "unsupported" -> owedResumes.forget(readerKey, name)
                // skipped_older, write_failed, or no answer: stays owed.
                else -> Unit
            }
        }
        return null
    }

    /** filename -> outcome of the last `progress` batch, or null when the reader cannot say. */
    private suspend fun progressResults(): Map<String, String>? = runCatching {
        val bytes = traced<ByteArray>(
            "progress_result",
            bytes = { it.size.toLong() },
            note = { ble.lastDownloadShape },
        ) {
            ble.download("progress_result")
        }
        val arr = JSONArray(String(bytes, Charsets.UTF_8))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("filename").ifBlank { null } ?: return@mapNotNull null
            name to o.optString("result")
        }.toMap()
    }.getOrNull().also { lastProgressResults = it }

    /**
     * Whether this reader applies a `position` sent with a book: `book_position`
     * in the `about` document's `features`, read once per connection.
     */
    private suspend fun readerTakesBookPosition(): Boolean = readerHasFeature(BOOK_POSITION)

    /** [feature] is in this connection's `about` `features`, read once per connection. */
    private suspend fun readerHasFeature(feature: String): Boolean {
        aboutFeatures?.let { return feature in it }
        val doc = readAbout(fresh = false)
        // Firmware without the `about` kind has no features either. Any other
        // failure (a dropped link) is not an answer, so it is not cached.
        if ((doc.exceptionOrNull() as? BleClient.BleException)?.code == "unsupported transfer kind") {
            aboutFeatures = emptySet()
        }
        return aboutFeatures?.contains(feature) == true
    }

    /**
     * The reader's `about` document. One download per connection however many
     * ask at once (the firmware check and the first send both do); [fresh] reads
     * it again, for the Firmware screen and after an install.
     */
    private suspend fun readAbout(fresh: Boolean): Result<JSONObject> = aboutLock.withLock {
        val cached = aboutDoc
        if (!fresh && cached != null) return@withLock Result.success(cached)
        val read = runCatching {
            val bytes = traced<ByteArray>("about", bytes = { it.size.toLong() }, note = { ble.lastDownloadShape }) {
                ble.download("about")
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        }
        read.getOrNull()?.let { doc ->
            aboutDoc = doc
            rememberAboutFeatures(doc)
            // The name the reader advertises. Absent on older firmware.
            if (doc.has("device_name")) rememberDeviceName(doc.optString("device_name"))
        }
        read
    }

    private fun rememberAboutFeatures(about: JSONObject) {
        val arr = about.optJSONArray("features")
        val features = if (arr == null) emptySet()
        else (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }.toSet()
        aboutFeatures = features
        if (_state.value.readerFeatures != features) _state.value = _state.value.copy(readerFeatures = features)
        // Downloads after this one may use bigger frames and fewer acks. Both absent
        // on older firmware, which keeps 160 bytes and an ack per frame.
        ble.setDownloadCapabilities(
            about.optInt("download_chunk_max", 0).takeIf { it > 0 },
            windowed = "download_window" in features,
        )
        // The bulk channel, once per connection, as soon as `about` says the
        // reader has one. Best effort: without it everything stays on GATT,
        // which is what older firmware gets anyway.
        val l2cap = about.optJSONObject("l2cap")
        if (l2cap != null && "l2cap_coc" in features) {
            val psm = l2cap.optInt("psm", 0)
            val sdu = l2cap.optInt("mtu", 0)
            if (psm > 0 && sdu > 0) viewModelScope.launch { ble.openL2cap(psm, sdu) }
        }
    }

    /**
     * Tells the reader the phone's dark mode when it differs from what this reader
     * was last told. Edge-triggered, so a switch made on the reader stays until the
     * phone next changes. Recorded only once the reader has accepted it.
     */
    private suspend fun matchPhoneDarkMode(): Unit = darkModeLock.withLock {
        if (!_state.value.config.matchPhoneDarkMode) return@withLock
        if (!_state.value.connected || !_state.value.authorized) return@withLock
        val dark = isNight(getApplication<Application>().resources.configuration)
        phoneDark = dark
        val readerId = deviceKey()
        if (runCatching { darkModeSent.load(readerId) }.getOrNull() == dark) return@withLock
        // This connection's `about`, which a connect reads anyway.
        if (aboutFeatures == null) readAbout(fresh = false)
        if (aboutFeatures?.contains(DARK_MODE) != true) return@withLock
        val sent = runCatching {
            traced<Unit>("dark mode " + if (dark) "on" else "off") { ble.setDarkMode(dark) }
        }.isSuccess
        if (!sent) return@withLock
        runCatching { darkModeSent.put(readerId, dark) }
        // An open Reader settings screen would otherwise show the old value.
        deviceSettingsDoc?.let { doc ->
            val v = if (dark) 1 else 0
            doc.put(SCREEN_INVERTED, if (doc.opt(SCREEN_INVERTED) is Boolean) dark else v)
            val ui = _state.value.deviceSettings
            if (ui.loaded) setDeviceSettings(ui.copy(values = ui.values + (SCREEN_INVERTED to v)))
        }
    }

    private val DARK_MODE = "dark_mode"
    private val SCREEN_INVERTED = "screenInverted"

    /**
     * The reader's library fingerprint as of the last status seen.
     *
     * Held in memory only: it describes a live conversation, and a stale value
     * from a previous run would either force a mirror that is not needed or,
     * worse, suppress one that is.
     */
    private var lastLibraryFingerprint: Pair<Int, Long>? = null

    /**
     * Writes a position the READER just reported straight onto its row.
     *
     * No forward-only guard here, deliberately. That rule exists to stop a
     * stale SERVER row dragging the reader backwards; this is the reader
     * speaking about itself, in the present tense, and it is the authority on
     * where it is. Refusing its own lower number would leave the app showing a
     * position the device does not agree with -- which is what "start again"
     * looks like from here.
     */
    private fun applyReaderPosition(filename: String, pct: Float) {
        val now = System.currentTimeMillis() / 1000
        val updated = _state.value.library.map { row ->
            if (row.book.filename != filename) row
            else {
                // Keep whatever the row already had and move the number: the
                // raw payload carries `src`/`set_at`, which are computed from
                // it rather than stored as fields, so rebuilding the object
                // from scratch would throw away the server's own bookkeeping.
                val next = row.progress?.copy(percentage = pct, timestamp = now)
                    ?: Progress(
                        document = filename,
                        percentage = pct,
                        device = _state.value.device?.firmwareName ?: "reader",
                        timestamp = now,
                    )
                row.copy(progress = next)
            }
        }
        if (updated != _state.value.library) _state.value = _state.value.copy(library = updated)
    }

    // --- kosync on heartbeat ----------------------------------------------------
    //
    // The reader pings on every page save. Without this the ping only moved the
    // number on screen and the SERVER learned the position at the next full
    // sync -- so another client opening the book in the meantime got a stale
    // row. Page turns come far faster than kosync needs them, so the latest
    // figure per book is held and published at most once per
    // [HEARTBEAT_WRITE_MS], and at once when the reader goes to sleep.
    //
    // Everything here runs on the main dispatcher; only the hash and the HTTP
    // calls hop to IO. That is what makes the plain maps safe.

    /** Latest percent per book, not yet published. */
    private val heartbeatPending = mutableMapOf<String, Float>()
    /** What was last published (or deliberately not) per book, so a repeat ping is free. */
    private val heartbeatPublished = mutableMapOf<String, Float>()
    /** filename -> (file stamp, content hash). The hash reads the whole file. */
    private val heartbeatHashes = mutableMapOf<String, Pair<Long, String>>()
    private var heartbeatJob: Job? = null
    private var heartbeatWriting = false
    private var heartbeatFlushWanted = false
    private var lastHeartbeatWriteAt = 0L

    private fun queueHeartbeatPosition(filename: String, pct: Float) {
        if (heartbeatPending[filename] == pct) return
        if (filename !in heartbeatPending && heartbeatPublished[filename] == pct) return
        heartbeatPending[filename] = pct
        scheduleHeartbeatWrite()
    }

    private fun scheduleHeartbeatWrite() {
        if (heartbeatWriting || heartbeatJob?.isActive == true) return
        val wait = (lastHeartbeatWriteAt + HEARTBEAT_WRITE_MS - System.currentTimeMillis()).coerceAtLeast(0L)
        heartbeatJob = viewModelScope.launch {
            delay(wait)
            publishHeartbeatPositions()
        }
    }

    private fun flushHeartbeatPositions() {
        if (heartbeatPending.isEmpty()) return
        // Never cancel a write in flight; have it run again as soon as it ends.
        if (heartbeatWriting) {
            heartbeatFlushWanted = true
            return
        }
        heartbeatJob?.cancel() // only ever sitting in its delay here
        heartbeatJob = viewModelScope.launch { publishHeartbeatPositions() }
    }

    private suspend fun publishHeartbeatPositions() {
        heartbeatWriting = true
        try {
            val c = _state.value.config
            if (c.username.isBlank() || !c.serverConfigured) {
                heartbeatPending.clear()
                return
            }
            val batch = heartbeatPending.toMap()
            heartbeatPending.clear()
            val kosync = KosyncClient(http, c.kosyncUrl, c.username, c.password)
            val deviceId = kosyncDeviceId()
            val deviceName = kosyncDeviceName()
            for ((filename, pct) in batch) {
                // A failed write is NOT recorded as published, so the next
                // heartbeat (60s at most) queues it again. No retry loop of its own.
                if (publishHeartbeatPosition(kosync, filename, pct, deviceName, deviceId)) {
                    heartbeatPublished[filename] = pct
                }
            }
            lastHeartbeatWriteAt = System.currentTimeMillis()
        } finally {
            heartbeatWriting = false
        }
        if (heartbeatPending.isNotEmpty()) {
            if (heartbeatFlushWanted) {
                heartbeatFlushWanted = false
                flushHeartbeatPositions()
            } else {
                scheduleHeartbeatWrite()
            }
        }
    }

    /**
     * One book. True when it is settled -- written, or deliberately skipped by
     * the same rules [runKosyncPass] applies -- and false only when a
     * write was attempted and failed.
     *
     * No `pos` is sent: the ping carries the percentage only, and `pos` is
     * provenance that nothing reads back (the push to the reader uses pct and
     * the spine fields). `set_at` is the receive time of the ping, which is
     * the moment the reader saved to within a second.
     */
    private suspend fun publishHeartbeatPosition(
        kosync: KosyncClient,
        filename: String,
        pct: Float,
        deviceName: String,
        deviceId: String,
    ): Boolean {
        // Only books this app delivered. A side-loaded book has no Calibre
        // original and no copy here, exactly as the full sync skips it.
        val local = books.cachedBooks().firstOrNull { it.filename == filename } ?: return true
        val hash = heartbeatHash(local) ?: return true
        val remote = kosync.progressFor(local.progressKey, hash, deviceName, deviceId)
        // FORWARD ONLY, at storage precision -- see runKosyncPass. Equal
        // is skipped too: there is nothing new to say.
        val mine = Math.round(pct * 10_000f) / 10_000f
        val theirs = Math.round((remote?.percentage ?: 0f) * 10_000f) / 10_000f
        if (remote != null && mine <= theirs) return true
        return kosync.putProgressFor(
            stableKey = local.progressKey,
            contentHash = hash,
            position = "",
            percentage = pct,
            setAt = System.currentTimeMillis() / 1000,
            deviceName = deviceName,
            deviceId = deviceId,
        )
    }

    /**
     * The book's KOReader content hash, cached by file size + mtime, so a book is
     * hashed once per version rather than once per sync. The map is touched only
     * on the caller's (main) thread; the file work hops to IO.
     */
    private suspend fun heartbeatHash(book: Book): String? {
        val file = books.fileFor(book)
        val stamp = withContext(Dispatchers.IO) {
            if (file.exists()) file.length() * 31 + file.lastModified() else -1L
        }
        if (stamp < 0) return null
        heartbeatHashes[book.filename]?.takeIf { it.first == stamp }?.let { return it.second }
        val hash = withContext(Dispatchers.IO) {
            runCatching { KoreaderHash.fromContent(file) }.getOrNull()
        } ?: return null
        heartbeatHashes[book.filename] = stamp to hash
        return hash
    }

    private suspend fun kosyncDeviceId(): String = deviceKey() ?: "x4pro"
    private fun kosyncDeviceName(): String = _state.value.deviceName.ifBlank { "X4 Pro" }

    /**
     * True when the server's copy of a shelved book is not the copy we hold.
     * Calibre's `<updated>` decides, and only it.
     *
     * Only a real DIFFERENCE counts. A sidecar written before `updated` was
     * recorded has nothing to compare against, and treating that as a change
     * would re-download every book on the shelf the first time this runs. A
     * record with no `<updated>` yet is stamped by [backfillCalibreStamps]
     * instead.
     *
     * Size is not compared: the phone's record takes its size from the
     * DOWNLOADED file (BookStore), which CWA builds with the metadata and cover
     * embedded, while the feed's `length` is the library file's. The two never
     * match, so a size rule would re-download books on every catalogue refresh.
     */
    private fun calibreChanged(have: Book, feed: Book): Boolean =
        have.updated.isNotBlank() && feed.updated.isNotBlank() && have.updated != feed.updated

    /** Records today's <updated> on old sidecars that lack one, so the NEXT change is seen. */
    private suspend fun backfillCalibreStamps(stored: Map<String, Book>, feed: List<Book>, changed: List<Book>) {
        val changing = changed.map { it.filename }.toSet()
        withContext(Dispatchers.IO) {
            for (f in feed) {
                val have = stored[f.filename] ?: continue
                if (f.filename in changing || have.updated.isNotBlank() || f.updated.isBlank()) continue
                books.remember(have.copy(updated = f.updated))
            }
        }
    }

    /**
     * Pulls Calibre's new copy of shelved books and re-sends them to the reader
     * WITHOUT losing anyone's place.
     *
     * Three things can break a position when a book's bytes change, and each is
     * handled here or where it happens:
     *  - kosync keyed by content hash: a new file hashes differently. The UUID key
     *    ([Book.progressKey]) does not move, and progressFor() is called against
     *    the OLD file first so a position that only exists under the old hash is
     *    copied to the UUID before that hash stops being reachable.
     *  - the reader wiping its cache on re-upload: BleLink keeps
     *    progress.bin / progress.time / syncjump.bin across the replacement.
     *  - a revision that shortened the book: the reader clamps a saved spine
     *    index that no longer exists instead of opening past the end.
     */
    private suspend fun updateFromCalibre(changed: List<Book>): Int {
        val c = _state.value.config
        if (!c.serverConfigured) return 0
        val kosync = KosyncClient(http, c.kosyncUrl, c.username, c.password)
        val canSync = c.username.isNotBlank()
        var updated = 0
        var unchanged = 0
        for (feed in changed) {
            if (feed.id in _state.value.busyBookIds) {
                // Being saved or removed right now: left for a later pass, not dropped.
                if (calibreQueue.none { it.filename == feed.filename }) calibreQueue += feed
                continue
            }
            markBusy(feed.id, true)
            try {
                val target = books.fileFor(feed)

                // The record to remember is the CATALOGUE's current one, not the
                // copy passed in: the one-time pass hands in the phone's own
                // stored record, and remembering that would keep the stale
                // <updated> / size, so the next launch would see a "change" and
                // move the book again for nothing.
                val latest = _state.value.rows.firstOrNull { it.book.filename == feed.filename }?.book ?: feed

                // 1. Hash the copy we hold, and secure its position under it.
                val oldHash = withContext(Dispatchers.IO) {
                    runCatching { KoreaderHash.fromContent(target) }.getOrNull()
                }
                if (canSync) {
                    runCatching { kosync.progressFor(feed.progressKey, oldHash, kosyncDeviceName(), kosyncDeviceId()) }
                }

                // 2. Download beside, not over: a failed download must leave the
                //    old copy intact rather than a truncated book on the shelf.
                //    Outside the books directory, which is read as the shelf.
                val temp = File(getApplication<Application>().cacheDir, "calibre-update-" + target.name)
                showCalibreProgress(0, 0)
                var sameBytes = false
                val ok = runCatching {
                    OpdsClient(http, c.opdsUrl, c.username, c.password).download(feed, temp) { sent, total ->
                        showCalibreProgress(sent, if (total > 0) total else sent)
                    }
                    // 2. Did the BOOK change, or only its catalogue stamp?
                    //
                    // A new <updated> or length says Calibre touched the record; it
                    // does not say the file differs. CWA's export is deterministic
                    // (two exports of the same book, seconds apart, are
                    // byte-identical), so the content hash is the real test.
                    // Same hash: keep our copy, record the new stamps so this does
                    // not come round again, and send the reader nothing.
                    val newHash = withContext(Dispatchers.IO) {
                        runCatching { KoreaderHash.fromContent(temp) }.getOrNull()
                    }
                    if (newHash != null && newHash == oldHash) {
                        sameBytes = true
                    } else {
                        withContext(Dispatchers.IO) { temp.copyTo(target, overwrite = true) }
                    }
                    withContext(Dispatchers.IO) { temp.delete() }
                    books.remember(latest)
                }.isSuccess
                temp.delete()
                if (!ok) continue
                if (sameBytes) {
                    unchanged++
                    continue
                }

                // 3. The reader's copy is now stale; the mirror re-sends it and
                //    the reader keeps its place (see BleLink takePositionFiles).
                sentBooks.forget(deviceKey(), feed.filename)
                // Must REPLACE the reader's copy: an ordinary send would come
                // back "exists" and be taken as already done.
                replaceOnSend.add(deviceKey(), feed.filename)
                markPendingTransfer(feed.id, true)
                updated++
            } finally {
                markBusy(feed.id, false)
            }
        }
        endTransfer(OWNER_CALIBRE)
        if (updated > 0) {
            _state.value = _state.value.copy(
                message = if (updated == 1) "Updated \"${changed.first().title}\" from Calibre"
                else "Updated $updated books from Calibre",
            )
            // No mirror of its own: the pass startCalibreUpdates asks for sends
            // the replaced books.
            loadLibrary(withProgress = false).join()
        }
        return updated
    }

    private val CALIBRE_LABEL = "Updating from Calibre…"


    /** The bar shows a Calibre download only when no other transfer is using it; see [shownTransfer]. */
    private fun showCalibreProgress(sent: Long, total: Long) {
        if (sent > 0 && !calibreGate.due(sent, total)) return
        showTransfer(TransferProgress(CALIBRE_LABEL, sent, total, owner = OWNER_CALIBRE))
    }

    private var calibreJob: Job? = null
    /** Books being re-downloaded from Calibre. The mirror leaves them until the file is final. */
    private val calibreUpdating = mutableSetOf<String>()
    /** Changes that arrived while [calibreJob] ran. */
    private var calibreMoreWanted = false

    /**
     * Runs [updateFromCalibre] beside the sync rather than inside it: each change
     * is a whole book downloaded from Calibre, and the reader's steps must not
     * wait on that. The pass it asks for when done sends what was replaced.
     */
    private fun startCalibreUpdates(changes: List<Book>) {
        if (calibreJob?.isActive == true) {
            changes.filter { c -> calibreQueue.none { it.filename == c.filename } }.let { calibreQueue.addAll(it) }
            calibreMoreWanted = true
            return
        }
        val names = changes.map { it.filename }.toSet()
        calibreUpdating += names
        val job = viewModelScope.launch {
            val updated = try {
                traced<Int>("calibre updates", note = { "$it of ${changes.size} replaced" }) {
                    updateFromCalibre(changes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                0
            } finally {
                calibreUpdating -= names
            }
            val more = calibreMoreWanted
            calibreMoreWanted = false
            if ((updated > 0 || more) && _state.value.connected && _state.value.authorized) requestSync()
        }
        calibreJob = job
        trackWork(job)
    }

    /**
     * Once per reader: replace every shelved book on it.
     *
     * Older app builds could not replace a book on the reader: a Calibre update
     * was re-sent, refused as "exists", and recorded as "already on the reader".
     * Such a book sits on the reader as the OLD copy while its sidecar already
     * matches Calibre, so nothing marks it as changed. There is no cheap way to
     * tell which, so the whole shelf is replaced once. Reading positions survive
     * a replace (the reader keeps its position files), so the cost is transfer
     * time only.
     *
     * Books owed a removal are skipped: re-sending them would undo a delete.
     * The flag is written BEFORE the sends: the queue itself is persisted in
     * ReplaceOnSendStore, so an interrupted run finishes on the next connection
     * without queuing the whole shelf a second time.
     */
    private suspend fun resendShelfOnce() {
        val id = deviceKey()
        if (replaceOnSend.resentAll(id)) return
        // Flagged first: updateFromCalibre ends by running the mirror, which calls
        // back into here.
        replaceOnSend.markResentAll(id)
        val owed = pendingRemovals.load(id)
        val shelf = withContext(Dispatchers.IO) { books.cachedBooks() }
            .filter { it.filename !in owed && it.downloadUrl != null }
        if (shelf.isEmpty()) return
        val n = shelf.size
        _state.value = _state.value.copy(
            message = "Re-downloading $n book${if (n == 1) "" else "s"} from Calibre once and " +
                "replacing ${if (n == 1) "it" else "them"} on the reader. Your places are kept.",
        )
        // RE-DOWNLOAD, not re-send. The phone's own copy can be exactly the
        // stale file: with CWA's embed_metadata on, the SERVED epub carries
        // whatever cover.jpg was at download time, and a cover-only change moves
        // neither <updated> nor the length, so the phone never learns its copy
        // is out of date. Going back
        // to Calibre for each book gets the file CWA serves today.
        // updateFromCalibre moves each position to the UUID key against the OLD
        // file first, marks the book for replace, and runs the mirror itself.
        calibreQueue.addAll(shelf.filter { c -> calibreQueue.none { it.filename == c.filename } })
    }

    /**
     * The reader's library listing, parsed: downloaded at most once per pass and
     * kept while only this app changes the reader.
     *
     * The prune needs it to see what to delete and the positions step needs it
     * for positions. This app's own sends, removals and applied resumes are
     * written into it here rather than downloading it again after each one.
     * Dropped when the reader changes on its own: a library fingerprint that is
     * not this app's echo, or a new session. Positions in it are re-read after a
     * heartbeat moves one, or after [LISTING_POSITIONS_TTL_MS].
     */
    private var readerEntries: MutableList<JSONObject>? = null
    private var readerEntriesAt = 0L
    /** A position moved on the reader since the listing was read. */
    private var readerPositionsStale = false
    /** Until then, a new library fingerprint is this app's own change coming back. */
    private var libraryEchoUntil = 0L
    private var lastPositionSyncAt = 0L
    private var bookOpenDeferredAt = 0L
    private var lastBookOpenRetryAt = 0L

    /** An owed resume is waiting to be tried; lets a positions pass past the listing throttle. */
    private var owedResumeDue = false
    /** The last owed-resume batch was refused with "book open"; the status stream retries it. */
    private var owedResumeBlocked = false
    /** What the previous status said about an open book, so the close itself can be seen. */
    private var lastStatusBookOpen = false
    /**
     * `features` from this connection's `about` document; null until read.
     * Statuses read after hello are trimmed and do not carry capabilities.
     */
    private var aboutFeatures: Set<String>? = null
    /** This connection's `about` document; see [readAbout]. */
    private var aboutDoc: JSONObject? = null
    private val aboutLock = Mutex()
    /** Outcomes from the last `progress_result` download; null when unknown. */
    private var lastProgressResults: Map<String, String>? = null
    /** Books sent before their cover was rendered; [warmCovers] sends their metadata. */
    private val metaOwed = mutableSetOf<String>()
    /** Covers being fetched, by filename, so a book is not fetched twice at once. */
    private val coversWarming = mutableSetOf<String>()
    /**
     * Saves whose resume question is still unanswered. The mirror holds these
     * back: where the book opens is the user's answer, and it travels with the send.
     */
    private val resumeUndecided = mutableSetOf<String>()

    /** Below this the reader has at most opened the book, and an owed resume still wins. */
    private val OWED_RESUME_UNREAD = 0.02f
    private val BOOK_POSITION = "book_position"

    private fun invalidateReaderListing() {
        readerEntries = null
    }

    /**
     * The reader's listing entries, copies. Kept when [forPositions] is false and
     * a listing is held; with [forPositions] also its positions must be current.
     * Null when the listing cannot be read or parsed -- never "the reader is empty".
     */
    private suspend fun readerLibrary(forPositions: Boolean): List<JSONObject>? {
        readerEntries?.let { kept ->
            val positionsCurrent = !readerPositionsStale &&
                System.currentTimeMillis() - readerEntriesAt < LISTING_POSITIONS_TTL_MS
            if (!forPositions || positionsCurrent) return kept.map { JSONObject(it.toString()) }
        }
        val bytes = runCatching {
            traced<ByteArray>("library", bytes = { it.size.toLong() }, note = { ble.lastDownloadShape }) {
                ble.download("library")
            }
        }.getOrNull() ?: return null
        // A BARE ARRAY of book objects -- BookLibraryIndex writes "[", the entries,
        // then "]". A parse failure is null and stops the caller.
        val parsed = runCatching {
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.getOrNull() ?: return null
        readerEntries = parsed.map { JSONObject(it.toString()) }.toMutableList()
        readerEntriesAt = System.currentTimeMillis()
        readerPositionsStale = false
        runCatching { bookLinks.saveListing(deviceKey(), parsed) }
        return parsed
    }

    /** A book this app just put on the reader. A replace keeps its entry, and its position. */
    private fun listingAdd(filename: String) {
        libraryEchoUntil = System.currentTimeMillis() + LIBRARY_ECHO_MS
        val entries = readerEntries ?: return
        if (entries.none { it.optString("filename") == filename }) {
            entries += JSONObject().put("filename", filename).put("fromApp", true)
        }
    }

    /** A book this app just removed from the reader. The kept copy too, or its row would come back. */
    private suspend fun listingRemove(filename: String) {
        libraryEchoUntil = System.currentTimeMillis() + LIBRARY_ECHO_MS
        readerEntries?.removeAll { it.optString("filename") == filename }
        runCatching { bookLinks.dropFromListing(deviceKey(), filename) }
    }

    /** A position the reader reported applied. */
    private fun listingSetPosition(filename: String, stamp: Long, percentage: Float) {
        readerEntries?.firstOrNull { it.optString("filename") == filename }?.let {
            it.put("timestamp", stamp)
            it.put("percent", percentage.toDouble())
        }
    }

    /** A heartbeat position that is not the kept listing's makes its positions stale. */
    private fun noteHeartbeatForListing(filename: String, pct: Float) {
        val entry = readerEntries?.firstOrNull { it.optString("filename") == filename } ?: return
        val listed = entry.optDouble("percent", -1.0)
        if (listed < 0.0 || kotlin.math.abs(listed - pct) > 0.0002) readerPositionsStale = true
    }

    private val LISTING_TTL_MS = 30_000L
    private val BOOK_OPEN_RETRY_MS = 30_000L

    /**
     * One sweep at a time, whoever asks.
     *
     * Each sweep pulls the reader's whole library listing over BLE. Two running
     * together compete for a single link and delay the next book's transfer
     * behind them.
     */
    private val pruneLock = Mutex()

    /**
     * Deletes from the reader what the user removed from the offline shelf -- and
     * nothing else.
     *
     * A book on the reader is deleted only when this installation holds a removal
     * record for it ([PendingRemovalStore]) under the connected reader's own
     * device_id. That record has one writer, [removeOffline]: the user removing a
     * book that was on the shelf while the reader was out of reach. (With the
     * reader in reach, removeOffline deletes it there directly.)
     *
     * This used to delete everything the reader held that the shelf lacked, and so
     * wiped a reader's books on the first sync after a fresh install, whose shelf
     * is empty. A book with no record -- a fresh install, a first pairing, a
     * pairing after Forget, a USB side-load -- stays on the reader, and is listed
     * as on the reader ([UiState.readerBookNames]).
     *
     * A listing that cannot be read or parsed does nothing at all.
     */
    private suspend fun pruneDeviceBooks(): Int = pruneLock.withLock { pruneDeviceBooksLocked() }

    private suspend fun pruneDeviceBooksLocked(): Int {
        // A listing that cannot be read or parsed is null and stops here: it must
        // never read as "the reader is empty".
        val listing = readerLibrary(forPositions = false) ?: run {
            tally?.problems?.add("library not read")
            return 0
        }
        val onDevice = listing.mapNotNull { it.optString("filename").ifBlank { null } }
        // The live reader's id only. deviceKey() falls back to the stored pairing and
        // then to "unknown"; a record filed under either says nothing about THIS reader.
        val readerId = _state.value.device?.deviceId ?: return 0

        val owed = pendingRemovals.load(readerId)
        val gone = mutableSetOf<String>()
        for (filename in onDevice.filter { it in owed }) {
            if (!_state.value.connected || !_state.value.authorized) break
            val deleted = runCatching {
                traced<Boolean>("delete", note = { if (it) "ok" else "refused" }) { ble.deleteBook(filename) }
            }.getOrDefault(false)
            // No answer may still have deleted it: only a confirmed removal is kept locally.
            if (deleted) listingRemove(filename) else invalidateReaderListing()
            if (!deleted) tally?.let { it.removeFailed++ }
            if (deleted) {
                gone += filename
                // Confirmed gone from the reader, so the local copy kept only to draw
                // the pending row can go too. Only now does the row leave the Library.
                settleRemoval(readerId, filename)
            }
        }
        // Owed, but the reader no longer holds it: nothing to delete there.
        var settled = 0
        for (filename in owed) {
            if (filename !in onDevice) {
                settleRemoval(readerId, filename)
                settled++
            }
        }

        _state.value = _state.value.copy(readerBookNames = onDevice.toSet() - gone)
        val shelf = withContext(Dispatchers.IO) { books.cachedNames() }
        val kept = onDevice.count { it !in shelf && it !in owed }
        if (kept > 0 && keptAnnouncedFor != readerId) {
            keptAnnouncedFor = readerId
            _state.value = _state.value.copy(
                message = "Kept $kept book${if (kept == 1) "" else "s"} already on the reader",
            )
        }
        // Also when the books only on the reader are not the ones the Library shows.
        val readerOnlyNow = onDevice.filter { it !in shelf && it !in owed && it !in gone }.toSet()
        val readerOnlyShown = _state.value.library.filter { it.onReaderOnly && !it.pendingRemoval }
            .map { it.book.filename }.toSet()
        if (gone.isNotEmpty() || settled > 0 || readerOnlyNow != readerOnlyShown) loadLibrary(withProgress = false)
        return gone.size
    }

    /** A removal the reader has carried out, or no longer needs: drop the local copy and both records. */
    private suspend fun settleRemoval(readerId: String?, filename: String) {
        withContext(Dispatchers.IO) {
            books.cachedBooks().firstOrNull { it.filename == filename }?.let { books.delete(it) }
        }
        pendingRemovals.forget(readerId, filename)
        sentBooks.forget(readerId, filename)
    }

    /**
     * Ends the removals still owed to [readerId] on this phone only; the reader
     * keeps its copies.
     *
     * Called when a pairing ends (Forget, or the reader forgetting this phone). A
     * removal was an instruction to the reader as it was paired then. A later
     * pairing -- even with the same reader -- starts owing nothing, so its first
     * sync cannot delete anything.
     */
    private suspend fun dropOwedRemovals(readerId: String?) {
        val owed = runCatching { pendingRemovals.load(readerId) }.getOrDefault(emptySet())
        if (owed.isEmpty()) return
        for (filename in owed) {
            withContext(Dispatchers.IO) {
                books.cachedBooks().firstOrNull { it.filename == filename }?.let { books.delete(it) }
            }
            pendingRemovals.forget(readerId, filename)
        }
        loadLibrary(withProgress = false)
    }

    /**
     * Sends a book's cover and metadata ahead of the book itself.
     *
     * The reader cannot render a cover without opening the book, which is a
     * page-layout pass and far too expensive per row. This app already has
     * both: Calibre publishes the cover art and the metadata in the OPDS feed,
     * and DeviceThumb already renders the 1-bit form the panel blits. So the
     * phone does the work once and the reader only reads a file.
     *
     * Sent BEFORE the book, so a shelf row can show a cover and a blurb while
     * the EPUB is still copying rather than a filename and a wait.
     *
     * Best-effort: a book with no cover, or a reader that does not accept the
     * kind, still transfers normally. A missing sidecar costs a thumbnail.
     */
    private suspend fun sendBookMetadata(book: Book, coverFetched: Boolean = false) {
        val kinds = _state.value.device?.uploadKinds.orEmpty()
        if (kinds.isNotEmpty() && "book_meta" !in kinds) return
        val c = _state.value.config
        if (coverFetched) {
            // Sent once, by whichever of the warm and the send gets here first.
            if (!metaOwed.remove(book.filename)) return
        } else if (book.coverUrl != null && c.serverConfigured &&
            !covers.hasDeviceThumb(book.coverUrl, DeviceThumb.ROW_WIDTH, DeviceThumb.ROW_HEIGHT)
        ) {
            // Never waits on Calibre for a cover: the book goes first, and this
            // follows as soon as the cover has been fetched.
            metaOwed += book.filename
            warmCovers(listOf(book))
            return
        }
        val thumb = runCatching {
            covers.deviceThumbnail(
                book.coverUrl, c.username, c.password,
                // The row geometry, not the detail one: the reader blits this
                // as-is, and anything it has to scale becomes dither noise.
                DeviceThumb.ROW_WIDTH, DeviceThumb.ROW_HEIGHT,
            )
        }.getOrNull()

        val uuid = calibreUuidOrNull(book.progressKey)?.takeIf { readerHasFeature(BOOK_UUID) }
        val item = CatalogContainer.Item(
            calibreUuid = uuid.orEmpty(),
            id = book.id,
            title = book.title,
            author = book.author,
            description = book.description,
            filename = book.filename,
            format = book.format,
            size = book.sizeBytes,
            thumbnail = thumb?.bytes,
            series = book.seriesLabel,
            publisher = book.publisher,
            published = book.published,
            language = book.language,
            tags = book.tags,
        )
        // req 0: this is not an answer to anything the reader asked for.
        val blob = CatalogContainer.detail(0, book.id, item, 1024)
        runCatching {
            traced<BleClient.UploadResult>("book_meta", bytes = { it.bytes }) {
                ble.uploadBytes(data = blob, kind = "book_meta")
            }
        }
    }

    /**
     * Renders the reader's row covers for [shelf] from Calibre, beside the sync.
     * A book that was sent before its cover was ready gets its metadata as soon
     * as the cover is in.
     */
    private fun warmCovers(shelf: List<Book>) {
        val c = _state.value.config
        if (!c.serverConfigured) return
        val missing = shelf.filter {
            it.coverUrl != null && it.filename !in coversWarming &&
                !covers.hasDeviceThumb(it.coverUrl, DeviceThumb.ROW_WIDTH, DeviceThumb.ROW_HEIGHT)
        }
        if (missing.isEmpty()) return
        coversWarming += missing.map { it.filename }
        val job = viewModelScope.launch {
            try {
                traced<Int>("covers", note = { "$it of ${missing.size} fetched" }) {
                    coroutineScope {
                        missing.map { b ->
                            async {
                                runCatching {
                                    covers.deviceThumbnail(
                                        b.coverUrl, c.username, c.password,
                                        DeviceThumb.ROW_WIDTH, DeviceThumb.ROW_HEIGHT,
                                    )
                                }.getOrNull()
                            }
                        }.awaitAll().count { it != null }
                    }
                }
            } finally {
                coversWarming -= missing.map { it.filename }.toSet()
            }
            for (b in missing) {
                if (b.filename in metaOwed && _state.value.connected && _state.value.authorized) {
                    sendBookMetadata(b, coverFetched = true)
                }
            }
        }
        trackWork(job)
    }

    private fun markBusy(id: String, busy: Boolean) {
        val next = _state.value.busyBookIds.toMutableSet()
        if (busy) next.add(id) else next.remove(id)
        _state.value = _state.value.copy(busyBookIds = next)
    }

    private fun markPendingTransfer(id: String, pending: Boolean) {
        val next = _state.value.pendingTransferIds.toMutableSet()
        if (pending) next.add(id) else next.remove(id)
        _state.value = _state.value.copy(pendingTransferIds = next)
    }

    private fun markRemoving(id: String, removing: Boolean) {
        val next = _state.value.removingBookIds.toMutableSet()
        if (removing) next.add(id) else next.remove(id)
        _state.value = _state.value.copy(removingBookIds = next)
    }

    /**
     * Saves a book offline, and lets the mirror carry it to the reader.
     *
     * Saving offline IS the gesture that puts a book on the device — there is
     * no separate "send" on the row — so a successful download is followed by
     * [mirrorToDevice]. That call is a no-op when the reader is not connected,
     * and the same mirror runs again on the next connection, so a book saved
     * with the reader in a drawer still arrives.
     */
    fun cacheBook(row: BookRow) = viewModelScope.launch {
        val c = _state.value.config
        // Held out of any mirror until the resume question is settled, because
        // books.remember below puts it on the shelf before the question is asked.
        resumeUndecided += row.book.filename
        markBusy(row.book.id, true)
        _state.value = _state.value.copy(message = null)
        // The transfer bar covers the DOWNLOAD too, not just the BLE push. The
        // download is the longer half on a big book, and showing nothing for it
        // makes saving look stalled until the reader transfer begins.
        val owner = "download:" + row.book.id
        showTransfer(TransferProgress("Downloading \"${row.book.title}\"", 0, 0, owner = owner))
        val gate = ProgressGate()
        val r = runCatching {
            OpdsClient(http, c.opdsUrl, c.username, c.password)
                .download(row.book, books.fileFor(row.book)) { sent, total ->
                    // A server with no Content-Length gives -1; report
                    // the bytes so far as the total so the bar stays
                    // honest rather than pretending to know the end.
                    val end = if (total > 0) total else sent
                    if (gate.due(sent, end)) {
                        showTransfer(TransferProgress("Downloading \"${row.book.title}\"", sent, end, owner = owner))
                    }
                }
            // Only after the bytes landed: the sidecar must never describe a
            // book that is not actually on the shelf.
            books.remember(row.book)
            "Saved \"${row.book.title}\" for offline reading"
        }
        markBusy(row.book.id, false)
        endTransfer(owner) { it.copy(message = r.getOrElse { e -> e.message ?: "Download failed" }) }
        if (r.isSuccess) {
            // Pending from the moment the bytes are on the phone, so the row is
            // grey for the whole gap before the reader has it.
            markPendingTransfer(row.book.id, true)
            restampRows()

            // Ask BEFORE the mirror, because the mirror is what sends the book
            // and the position together. The bytes are on the phone by now, so
            // the CONTENT hash kosync needs can finally be computed -- that is
            // why this cannot be asked at the moment the button is tapped.
            //
            // Only when the server actually holds a position: a book being read
            // for the first time must not be interrupted to be asked whether to
            // start at the beginning.
            val saved = runCatching {
                val hash = withContext(Dispatchers.IO) {
                    KoreaderHash.fromContent(books.fileFor(row.book))
                }
                if (hash != null) {
                    KosyncClient(http, c.kosyncUrl, c.username, c.password)
                        .progressFor(row.book.progressKey, hash, kosyncDeviceName(), kosyncDeviceId())
                } else null
            }.getOrNull()

            if (c.username.isNotBlank() && saved != null && saved.percentage > 0f) {
                val answer = CompletableDeferred<Boolean>()
                resumeAnswer = answer
                _state.value = _state.value.copy(
                    resumePrompt = ResumePrompt(
                        title = row.book.title,
                        filename = row.book.filename,
                        percentLabel = saved.percentLabel,
                    ),
                )
                // Recorded BEFORE the mirror below sends the book, so the send can
                // carry the position (sendToDevice) and the first open lands on it.
                if (answer.await()) recordOwedResume(row.book, saved)
            } else {
                // No question asked means no standing instruction to restart.
                startFresh.forget(deviceKey(), row.book.filename)
                owedResumes.forget(deviceKey(), row.book.filename)
            }
            resumeUndecided -= row.book.filename

            // Straight into the mirror. It reads the shelf itself as its first
            // step, so loading it here as well ran the whole pass twice before
            // a single byte reached the reader.
            //
            // JOINED, and this is load-bearing. The mirror's own first step is
            // loadLibrary(withProgress = false), which carries progress forward
            // from the rows it already has -- and a book saved seconds ago has
            // no prior row, so it carries forward NOTHING. Launching the real
            // progress load beside the mirror raced the two, and the mirror's
            // network and BLE work meant it usually finished LAST and wrote its
            // null over the real value. Every freshly saved book then showed no
            // reading position at all.
            mirrorToDevice().join()
            // The position as soon as the book is there, before the user is likely
            // to open it. Usually the send or that pass already delivered it.
            if (owedResumeDue) requestSync(positions = true)
            // Progress afterwards, off the critical path: it is decoration on a
            // list the user can already see and act on.
            loadLibrary()
        } else {
            resumeUndecided -= row.book.filename
        }
    }

    /**
     * Takes a book off the reader first, then off this phone.
     *
     * The order matters. Deleting the local file first would mean that when the
     * reader refuses -- which it legitimately does while that book is open on
     * it -- the phone has already thrown its copy away, leaving the book on
     * neither side but still listed as sent.
     *
     * The row stays in the list, greyed, for the whole of this. It is only
     * dropped once the reader has actually let go, so "gone from the list" means
     * "gone from both" rather than "the request was sent".
     */
    fun removeOffline(row: BookRow, confirmedOpen: Boolean = false) = viewModelScope.launch {
        val id = row.book.id
        if (id in _state.value.removingBookIds) return@launch  // already on its way out
        // Open on the reader right now: say so and ask before closing it there.
        val openOnReader = _state.value.connected && _state.value.readerOpenBook == row.book.filename
        if (openOnReader && !confirmedOpen) {
            _state.value = _state.value.copy(removeOpenPrompt = row)
            return@launch
        }
        _state.value = _state.value.copy(removeOpenPrompt = null)
        markBusy(id, true)
        markRemoving(id, true)
        markPendingTransfer(id, false)
        _state.value = _state.value.copy(message = null)

        // The reader first. Not connected is not a failure: the book is simply
        // dropped from the shelf and the next mirror prunes it there.
        val linked = _state.value.connected && _state.value.authorized
        val onReader = if (linked) {
            runCatching { ble.deleteBook(row.book.filename, closeIfOpen = openOnReader) }
                .getOrDefault(false).also { removed ->
                    if (removed) listingRemove(row.book.filename) else invalidateReaderListing()
                }
        } else {
            null
        }

        // A refusal stops here, with the local copy intact. Nothing is greyed
        // out any more and the tick comes back, which is the truth: the book is
        // still on both.
        if (onReader == false) {
            markBusy(id, false)
            markRemoving(id, false)
            _state.value = _state.value.copy(
                message = "The reader could not remove \"${row.book.title}\"",
            )
            return@launch
        }

        // NOT CONNECTED: owe the removal instead of doing half of it.
        //
        // Deleting the local copy now would drop the row from the Library and
        // leave nothing to draw the pending deletion from, so a removal made
        // with the reader asleep would look complete before it had started.
        //
        // So the file stays, the filename is recorded, and the row keeps
        // drawing greyed out with what it is waiting for. pruneDeviceBooks()
        // excludes owed removals from the shelf, so the next mirror deletes it
        // on the reader and only then is the local copy dropped.
        if (onReader == null) {
            pendingRemovals.add(deviceKey(), row.book.filename)
            markBusy(id, false)
            loadLibrary(withProgress = false).join()
            _state.value = _state.value.copy(
                message = "\"${row.book.title}\" will be removed from the reader on the next sync",
            )
            restampRows()
            return@launch
        }

        // The reader has ALREADY confirmed. deleteBook() returns on the reader's
        // own "saved" status notification, so by this line the book is gone from
        // the card and the answer is not in doubt. Everything below is local.
        val gone = withContext(Dispatchers.IO) { books.delete(row.book) }
        sentBooks.forget(deviceKey(), row.book.filename)
        pendingRemovals.forget(deviceKey(), row.book.filename)

        markBusy(id, false)
        _state.value = _state.value.copy(
            message = when {
                !gone -> "Removed \"${row.book.title}\" from the reader, but not from this phone"
                else -> "Removed \"${row.book.title}\" from this phone and the reader"
            },
        )
        restampRows()
        // Shelf only, and off the reader entirely: reading the saved directory
        // is a local file listing.
        loadLibrary(withProgress = false).join()
        // Last, so the row does not flicker back to a tick in the gap between
        // the list reloading and this clearing.
        markRemoving(id, false)

        // Reconcile AFTER the user has their answer, not before it.
        //
        // pruneDeviceBooks() pulls the reader's whole library listing over BLE
        // to catch books an earlier failure left behind. That is worth doing,
        // but awaiting it here would hold a deletion the reader has already
        // finished on the list for several more seconds.
        if (linked) requestSync()
    }

    /** Hands a cached EPUB to KOReader (or any epub viewer) via FileProvider. */
    fun openInReader(row: BookRow) {
        val intent = books.openInReaderIntent(row.book)
        if (intent == null) {
            _state.value = _state.value.copy(message = "Save it offline first")
            return
        }
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure {
                _state.value = _state.value.copy(
                    message = "Nothing on this phone can open an EPUB"
                )
            }
    }

    /**
     * Whether any installed app can open [book]. Asked of the package manager,
     * never assumed from a package name — see [BookStore.canOpenInReader].
     */
    fun canOpenInReader(book: Book): Boolean = books.canOpenInReader(book)

    // --------------------------------------------------------------- upload

    /** Download from CWA if needed, then push over BLE. */
    fun sendToDevice(row: BookRow) = viewModelScope.launch {
        val c = _state.value.config
        if (!_state.value.connected) {
            _state.value = _state.value.copy(message = "Connect to the reader first")
            return@launch
        }
        if (!_state.value.authorized) {
            _state.value = _state.value.copy(message = "Authorise with the reader's six-digit code first")
            return@launch
        }
        val kinds = _state.value.device?.uploadKinds.orEmpty()
        if (kinds.isNotEmpty() && "book" !in kinds) {
            _state.value = _state.value.copy(message = "This reader firmware cannot accept books over BLE")
            return@launch
        }

        markBusy(row.book.id, true)
        _state.value = _state.value.copy(message = null)

        // Cover and blurb first: the row can then show the book while the file
        // is still on its way, which is most of the wait.
        sendBookMetadata(row.book)

        // Timestamp of a position sent with the book; 0 when none was.
        var positionStamp = 0L
        var replacing = false
        val owner = "book:" + row.book.filename
        val sending = "Sending \"${row.book.title}\" to reader…"
        val outcome = runCatching {
            // The local copy doubles as the offline library and as the source
            // for handing the book to KOReader, so it is kept, not deleted.
            val target = books.fileFor(row.book)
            if (!books.isCached(row.book)) {
                showTransfer(TransferProgress("Downloading \"${row.book.title}\"", 0, 0, owner = owner))
                OpdsClient(http, c.opdsUrl, c.username, c.password).download(row.book, target)
                // Same rule as cacheBook: describe it only once it is really
                // on the shelf, since this path fills the shelf too.
                books.remember(row.book)
            }
            showTransfer(TransferProgress(sending, 0, target.length(), owner = owner))
            var gate = ProgressGate()
            replacing = row.book.filename in replaceOnSend.load(deviceKey())
            // An owed resume rides with the book where the reader can take it, so
            // the very first open lands on it. Not on a replace: the reader may
            // hold real reading there, and the position sync's 2% rule decides.
            // Otherwise the position sync after the send delivers it.
            // Owed first: `about` is read only when there is a position to send.
            val owedHere = if (replacing) null else {
                owedResumes.load(deviceKey())[row.book.filename]
                    ?.takeIf { it.appliedAt == 0L && it.hasJump }
                    ?.takeIf { readerTakesBookPosition() }
            }
            var meter = RateMeter()
            val onProgress: (Long, Long) -> Unit = { sent, total ->
                // Every call, so the meter has samples between the bar's updates.
                val kbps = meter.kbps(sent)
                if (gate.due(sent, total)) {
                    showTransfer(TransferProgress(sending, sent, total, kbps, owner = owner))
                }
            }
            positionStamp = if (owedHere != null) System.currentTimeMillis() / 1000 else 0L
            // The Calibre UUID rides with the book where the reader keeps it, so the
            // book can be matched to Calibre again whatever its filename becomes.
            val calibreUuid = calibreUuidOrNull(row.book.progressKey)?.takeIf { readerHasFeature(BOOK_UUID) }
            val traceName = "book " + row.book.title.take(24)
            try {
                traced<BleClient.UploadResult>(
                    traceName,
                    bytes = { it.bytes },
                    note = { if (it.positionApplied) "with position" else "" },
                ) {
                    ble.upload(
                        target,
                        row.book.filename,
                        replace = replacing,
                        position = owedHere?.positionJson(positionStamp),
                        calibreUuid = calibreUuid,
                        onProgress = onProgress,
                    )
                }
            } catch (e: BleClient.BleException) {
                // The reader refuses the whole send over a position it cannot take.
                // The book matters more: send it bare, and the position sync delivers.
                if (positionStamp == 0L || e.code != "invalid position") throw e
                positionStamp = 0L
                gate = ProgressGate()
                meter = RateMeter()
                traced<BleClient.UploadResult>(traceName, bytes = { it.bytes }) {
                    ble.upload(target, row.book.filename, replace = replacing, calibreUuid = calibreUuid, onProgress = onProgress)
                }
            }
        }

        val deviceId = deviceKey()
        val message = outcome.fold(
            onSuccess = { r ->
                sentBooks.add(deviceId, row.book.filename)
                replaceOnSend.forget(deviceId, row.book.filename)
                listingAdd(row.book.filename)
                bookOpenDeferredAt = 0L
                // Applied with the book: no longer owed. Otherwise the position
                // sync that follows the send delivers it.
                if (positionStamp > 0L && r.positionApplied) {
                    owedResumes.markApplied(deviceId, row.book.filename, positionStamp)
                }
                tally?.let { it.booksSent++ }
                // On the reader now: the row goes back to full colour.
                markPendingTransfer(row.book.id, false)
                val secs = (r.elapsedMs / 1000.0).coerceAtLeast(0.1)
                "Sent \"${row.book.title}\" — ${r.bytes / 1024} KB in ${secs.toInt()}s " +
                    "(${(r.bytes / secs / 1024).toInt()} KB/s)"
            },
            onFailure = { e ->
                // "exists" is the one on-device fact the protocol will tell us
                // without a list operation, so record it rather than discard it.
                val code = (e as? BleClient.BleException)?.code
                val wantedReplace = row.book.filename in replaceOnSend.load(deviceId)
                if (code == "book open") {
                    // Stays owed, and retried from the status stream (at most every
                    // 30s) until the book is closed. Said ONCE: every retry while the
                    // book is still open would otherwise repeat the same line.
                    tally?.problems?.add("book open on reader")
                    val first = bookOpenDeferredAt == 0L
                    bookOpenDeferredAt = System.currentTimeMillis()
                    if (first) "Close \"${row.book.title}\" on the reader so its update can be sent" else null
                } else if (code == "exists" && wantedReplace) {
                    // Asked to replace and still refused: firmware older than the
                    // replace flag. Keep it owed and SAY so -- marking it sent here
                    // is exactly the silent-stale-copy bug this path exists to fix.
                    tally?.problems?.add("reader firmware too old")
                    "Update the reader's firmware to replace \"${row.book.title}\""
                } else if (code == "exists") {
                    sentBooks.add(deviceId, row.book.filename)
                    // Already there counts as arrived, so stop showing it as
                    // pending or the row stays grey forever.
                    markPendingTransfer(row.book.id, false)
                    "\"${row.book.title}\" is already on the reader"
                } else {
                    tally?.let { it.booksFailed++ }
                    "Send failed: ${e.message}"
                }
            },
        )

        markBusy(row.book.id, false)
        endTransfer(owner) { it.copy(message = message) }
        // Deliberately neither refresh() nor loadLibrary(): the only thing that
        // changed is the "sent" flag (and, on the download-first path, the
        // shelf), and both of the heavier calls would run once per book through
        // a mirror -- reloading the catalogue over the user's search, and
        // re-reading the whole shelf's progress from kosync each time.
        restampRows()

        val sent = outcome.getOrNull() ?: return@launch
        // Cover and blurb held back for a cover that is in now.
        if (row.book.filename in metaOwed &&
            covers.hasDeviceThumb(row.book.coverUrl, DeviceThumb.ROW_WIDTH, DeviceThumb.ROW_HEIGHT)
        ) {
            sendBookMetadata(row.book, coverFetched = true)
        }
        // A resume the user chose, straight after its book and before any network work.
        if (!replacing && !(positionStamp > 0L && sent.positionApplied)) {
            deliverOwedResumeNow(row.book.filename)
        }
    }

    // ------------------------------------------------- reader books, relinked

    /**
     * [list] with each book the shelf already holds under another filename renamed
     * to that filename, matched by Calibre UUID. That is a reader file relinked
     * after a reinstall (kept under the READER's name) or a book Calibre renamed
     * since it was saved: the same book either way, so the Store shows it as saved
     * and a Calibre update replaces the copy the shelf and the reader actually have.
     */
    private suspend fun aliasToShelf(list: List<Book>): List<Book> {
        val shelf = withContext(Dispatchers.IO) { books.cachedBooks() }
        if (shelf.isEmpty()) return list
        val names = shelf.map { it.filename }.toSet()
        val byKey = HashMap<String, String>()
        for (b in shelf) b.progressKey?.let { byKey.putIfAbsent(it, b.filename) }
        return list.map { b ->
            val alias = b.progressKey?.let { byKey[it] }
            if (alias == null || alias == b.filename || b.filename in names) b else b.copy(filename = alias)
        }
    }

    /** A Library row for a book on the reader with no copy here, described by the reader's listing. */
    private fun readerOnlyRow(e: JSONObject, name: String, owedRemoval: Boolean, known: Progress?): BookRow {
        val pct = e.optDouble("percent", -1.0).toFloat()
        val savedAt = e.optLong("timestamp", 0L)
        val listed = if (pct > 0f) {
            Progress(document = name, percentage = pct.coerceIn(0f, 1f), device = kosyncDeviceName(), timestamp = savedAt)
        } else null
        // A heartbeat since the listing was read is the newer number.
        val progress = if (known != null && (listed == null || known.timestamp >= listed.timestamp)) known else listed
        val title = e.optString("title").trim().ifBlank { null }
            ?: name.removeSuffix(".epub").replace(Regex("[-_]+"), " ").trim().ifBlank { name }
        return BookRow(
            book = Book(
                id = READER_ROW + name,
                title = title,
                author = e.optString("author").trim().ifBlank { "Unknown" },
                downloadUrl = null,
                coverUrl = null,
                filename = name,
                sizeBytes = e.optLong("size", 0L).coerceAtLeast(0L),
            ),
            progress = progress,
            sentFromThisApp = true,
            cached = false,
            pendingRemoval = owedRemoval,
            onReaderOnly = true,
        )
    }

    /**
     * Starts [relinkPass] beside the sync for the listing in hand. One at a time; a
     * request while one runs earns one more pass. Nothing on the reader waits on it.
     */
    private fun startRelink() {
        val c = _state.value.config
        if (!c.serverConfigured || catalogueBooks.isEmpty()) return
        val entries = readerEntries?.map { JSONObject(it.toString()) } ?: return
        if (relinkJob?.isActive == true) {
            relinkAgain = true
            return
        }
        val job = viewModelScope.launch {
            runCatching { relinkPass(entries) }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (relinkAgain) {
                relinkAgain = false
                startRelink()
            }
        }
        relinkJob = job
        trackWork(job)
    }

    /**
     * Brings reader books the shelf lacks back onto it from Calibre.
     *
     * For each listing entry that is not on the shelf, not owed a removal and not
     * already being fetched: match a catalogue book by the reader's `calibre_uuid`,
     * then by the link remembered for that filename, then by exact filename. The
     * catalogue is the one refresh() loaded; an entry it does not cover is looked
     * up once per process with a Calibre search by title. A match is downloaded
     * under the READER's filename (see [linkOnShelf]), so the shelf, the sent and
     * removal records and the reader's own position reports all line up, and it
     * is marked sent so the mirror never sends it back.
     *
     * Only books the reader says came from the app (or that carry a UUID): a USB
     * side-load has no Calibre original, and stays a reader-only row.
     */
    private suspend fun relinkPass(entries: List<JSONObject>): Int {
        val c = _state.value.config
        val readerId = deviceKey()
        val shelf = withContext(Dispatchers.IO) { books.cachedNames() }
        val owed = pendingRemovals.load(readerId)
        val links = bookLinks.load(readerId)
        val candidates = entries.filter { e ->
            val name = e.optString("filename")
            name.isNotBlank() && name !in shelf && name !in owed && name !in relinking && name !in addingToCalibre &&
                name !in calibreAddsPending &&
                (e.optBoolean("fromApp", true) || calibreUuidOrNull(e.optString("calibre_uuid")) != null)
        }
        if (candidates.isEmpty()) return 0

        val catalogue = catalogueBooks
        val byKey = HashMap<String, Book>()
        val byName = HashMap<String, Book>()
        for (b in catalogue) {
            b.progressKey?.let { byKey.putIfAbsent(it, b) }
            byName.putIfAbsent(b.filename, b)
        }
        fun match(e: JSONObject, found: (String) -> Book?, named: (String) -> Book?): Book? {
            val name = e.optString("filename")
            val uuid = calibreUuidOrNull(e.optString("calibre_uuid")) ?: links[name]
            return uuid?.let(found) ?: named(name)
        }
        val matches = mutableListOf<Pair<String, Book>>()
        val misses = mutableListOf<JSONObject>()
        for (e in candidates) {
            val m = match(e, { byKey[it] }, { byName[it] })
            if (m != null) matches += e.optString("filename") to m else misses += e
        }
        // Beyond the page the catalogue holds: one search per book per process.
        val searchable = misses.filter { it.optString("filename") !in relinkSearched && it.optString("title").isNotBlank() }
        if (searchable.isNotEmpty()) {
            val opds = OpdsClient(http, c.opdsUrl, c.username, c.password)
            for (e in searchable) {
                val name = e.optString("filename")
                val found = runCatching { opds.search(e.optString("title").trim()) }.getOrNull() ?: continue
                relinkSearched += name
                match(e, { k -> found.firstOrNull { it.progressKey == k } }, { n -> found.firstOrNull { it.filename == n } })
                    ?.let { matches += name to it }
            }
        }
        if (matches.isEmpty()) return 0

        val linked = traced<Int>("relink", note = { "$it of ${matches.size} from Calibre" }) {
            coroutineScope {
                matches.map { (name, book) ->
                    async { relinkLimit.withPermit { linkOnShelf(readerId, name, book) } }
                }.awaitAll().count { it }
            }
        }
        if (linked > 0) loadLibrary()
        return linked
    }

    /**
     * Puts Calibre's [book] on the shelf under the reader's [readerName] and links
     * the two: the link (reader filename -> UUID) is remembered, and the book is
     * marked sent to the reader, which already holds it. Marked sent BEFORE the
     * file lands on the shelf, so no mirror pass can see it unsent and send it back.
     * Downloaded beside the shelf first, so a failed download leaves nothing behind.
     * [fallback] is used when the download fails (the copy just pulled off the reader).
     */
    private suspend fun linkOnShelf(
        readerId: String?,
        readerName: String,
        book: Book,
        fallback: File? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Boolean {
        if (readerName in relinking) return false
        relinking += readerName
        val c = _state.value.config
        val shelved = book.copy(filename = readerName)
        val temp = File(getApplication<Application>().cacheDir, "relink-$readerName")
        return try {
            val target = books.fileFor(shelved)
            if (!withContext(Dispatchers.IO) { target.exists() && target.length() > 0 }) {
                val fetched = runCatching {
                    OpdsClient(http, c.opdsUrl, c.username, c.password).download(book, temp, onProgress)
                }
                if (fetched.isFailure) {
                    val copied = fallback != null && withContext(Dispatchers.IO) {
                        runCatching { fallback.copyTo(temp, overwrite = true); temp.length() > 0 }.getOrDefault(false)
                    }
                    if (!copied) return false
                }
                sentBooks.add(readerId, readerName)
                calibreUuidOrNull(book.progressKey)?.let { bookLinks.put(readerId, readerName, it) }
                val moved = withContext(Dispatchers.IO) {
                    temp.renameTo(target) || runCatching { temp.copyTo(target, overwrite = true); true }.getOrDefault(false)
                }
                if (!moved) return false
            } else {
                sentBooks.add(readerId, readerName)
                calibreUuidOrNull(book.progressKey)?.let { bookLinks.put(readerId, readerName, it) }
            }
            books.remember(shelved)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        } finally {
            withContext(Dispatchers.IO) { temp.delete() }
            relinking -= readerName
        }
    }

    /** Opens "Link to Calibre book" for a book only on the reader, searching by its title. */
    fun openLinkPicker(row: BookRow) {
        if (!row.onReaderOnly) return
        _state.value = _state.value.copy(
            linkPicker = LinkPicker(filename = row.book.filename, title = row.book.title, query = row.book.title),
        )
        searchLinkPicker()
    }

    fun setLinkQuery(q: String) {
        val p = _state.value.linkPicker ?: return
        _state.value = _state.value.copy(linkPicker = p.copy(query = q))
    }

    fun dismissLinkPicker() {
        _state.value = _state.value.copy(linkPicker = null)
    }

    /** Searches Calibre for the picker; a blank query lists the catalogue already loaded. */
    fun searchLinkPicker() = viewModelScope.launch {
        val p = _state.value.linkPicker ?: return@launch
        val c = _state.value.config
        if (!c.serverConfigured) {
            _state.value = _state.value.copy(linkPicker = p.copy(note = "Set the server URL in Settings"))
            return@launch
        }
        val q = p.query.trim()
        _state.value = _state.value.copy(linkPicker = p.copy(searching = true, note = null))
        val found = if (q.isEmpty()) Result.success(catalogueBooks)
        else runCatching { OpdsClient(http, c.opdsUrl, c.username, c.password).search(q) }
        val now = _state.value.linkPicker?.takeIf { it.filename == p.filename } ?: return@launch
        val list = found.getOrDefault(emptyList())
        _state.value = _state.value.copy(
            linkPicker = now.copy(
                searching = false,
                results = list,
                note = when {
                    found.isFailure -> "Search failed"
                    list.isEmpty() -> "Nothing matched"
                    else -> null
                },
            ),
        )
    }

    /** The user's pick: Calibre's [book] goes on the shelf as the reader's [filename]. Not sent again. */
    fun linkToCalibre(filename: String, book: Book) = viewModelScope.launch {
        _state.value = _state.value.copy(linkPicker = null)
        val id = READER_ROW + filename
        val owner = "download:$id"
        val label = "Downloading \"${book.title}\""
        markBusy(id, true)
        showTransfer(TransferProgress(label, 0, 0, owner = owner))
        val gate = ProgressGate()
        val ok = linkOnShelf(deviceKey(), filename, book) { sent, total ->
            val end = if (total > 0) total else sent
            if (gate.due(sent, end)) showTransfer(TransferProgress(label, sent, end, owner = owner))
        }
        markBusy(id, false)
        endTransfer(owner) {
            it.copy(message = if (ok) "Linked to \"${book.title}\"" else "Could not download \"${book.title}\"")
        }
        if (ok) loadLibrary()
    }

    /**
     * Explicit removal of a book only on the reader: owed, exactly like removing a
     * shelved book with the reader away, and carried out by the next sync's prune.
     */
    fun removeFromReader(row: BookRow) = viewModelScope.launch {
        if (!row.onReaderOnly) return@launch
        pendingRemovals.add(deviceKey(), row.book.filename)
        loadLibrary(withProgress = false).join()
        _state.value = _state.value.copy(
            message = "\"${row.book.title}\" will be removed from the reader on the next sync",
        )
        if (_state.value.connected && _state.value.authorized) requestSync()
    }

    /**
     * "Add to Calibre" for a book only on the reader: pull the EPUB off the reader,
     * upload it through CWA's web form ([CalibreUploader]), then look for the
     * imported book every [ADD_POLL_MS] for up to [ADD_WAIT_MS] (CWA imports in the
     * background). Found by title and author, it is linked and shelved under the
     * reader's filename. Not found by then, the next catalogue refresh links it.
     */
    fun addToCalibre(row: BookRow) = viewModelScope.launch {
        val name = row.book.filename
        val c = _state.value.config
        if (!row.onReaderOnly || name in addingToCalibre) return@launch
        if (!c.serverConfigured) {
            _state.value = _state.value.copy(message = "Set the server URL in Settings")
            return@launch
        }
        if (!_state.value.connected || !_state.value.authorized) {
            _state.value = _state.value.copy(message = "Connect to the reader first")
            return@launch
        }
        if (!readerHasFeature(BOOK_DOWNLOAD)) {
            _state.value = _state.value.copy(message = "Update the reader's firmware to add books")
            return@launch
        }
        val readerId = deviceKey()
        val id = READER_ROW + name
        val owner = "pull:$name"
        val temp = File(getApplication<Application>().cacheDir, "pull-$name")
        addingToCalibre += name
        markBusy(id, true)
        _state.value = _state.value.copy(message = "Adding to Calibre\u2026")
        try {
            // 1. Off the reader, straight to a file.
            val label = "Copying \"${row.book.title}\" from reader"
            showTransfer(TransferProgress(label, 0, row.book.sizeBytes, owner = owner))
            val gate = ProgressGate()
            val meter = RateMeter()
            val pulled = runCatching {
                traced<ByteArray>("pull book", note = { ble.lastDownloadShape }) {
                    withContext(Dispatchers.IO) {
                        temp.outputStream().use { out ->
                            ble.download("book", mapOf("name" to name), into = out, maxBytes = HttpGuard.BOOK_MAX) { got, total ->
                                val kbps = meter.kbps(got)
                                val end = if (total > 0) total else maxOf(got, row.book.sizeBytes)
                                if (gate.due(got, end)) showTransfer(TransferProgress(label, got, end, kbps, owner = owner))
                            }
                        }
                    }
                }
            }
            endTransfer(owner)
            if (pulled.isFailure || temp.length() == 0L) {
                val why = (pulled.exceptionOrNull() as? BleClient.BleException)?.message
                _state.value = _state.value.copy(message = why ?: "Could not copy the book from the reader")
                return@launch
            }

            // 2. Up to Calibre.
            val upload = runCatching { CalibreUploader(http, c.base, c.username, c.password).upload(temp, name) }
            upload.exceptionOrNull()?.let { e ->
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    message = if (e is CalibreUploader.Refused) "Calibre did not accept the book" else "Could not reach Calibre",
                )
                return@launch
            }

            // 3. Wait for the import, then link.
            calibreAddsPending[name] = row.book.title to row.book.author
            val found = awaitCalibreImport(name, row.book.title, row.book.author)
            when {
                found != null -> {
                    calibreAddsPending -= name
                    linkOnShelf(readerId, name, found, fallback = temp)
                    _state.value = _state.value.copy(message = "Added to Calibre")
                    loadLibrary()
                }
                // A catalogue refresh found and linked it meanwhile.
                name !in calibreAddsPending -> _state.value = _state.value.copy(message = "Added to Calibre")
                else -> _state.value = _state.value.copy(message = "Calibre has not listed the book yet")
            }
        } finally {
            endTransfer(owner)
            addingToCalibre -= name
            markBusy(id, false)
            withContext(Dispatchers.IO) { temp.delete() }
        }
    }

    /** The book CWA imported from an upload, looked for every [ADD_POLL_MS] for [ADD_WAIT_MS]. */
    private suspend fun awaitCalibreImport(name: String, title: String, author: String): Book? {
        val c = _state.value.config
        val opds = OpdsClient(http, c.opdsUrl, c.username, c.password)
        val deadline = System.currentTimeMillis() + ADD_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(ADD_POLL_MS)
            if (name !in calibreAddsPending) return null
            val found = runCatching { opds.search(title) }.getOrNull()?.firstOrNull { importedAs(it, title, author) }
                ?: catalogueBooks.firstOrNull { importedAs(it, title, author) }
            if (found != null) return found
        }
        return null
    }

    /** Uploads still waiting to show in the catalogue, linked once a refresh lists them. */
    private fun settleCalibreAdds(feed: List<Book>) {
        if (calibreAddsPending.isEmpty()) return
        for ((name, wanted) in calibreAddsPending.toMap()) {
            val found = feed.firstOrNull { importedAs(it, wanted.first, wanted.second) } ?: continue
            calibreAddsPending -= name
            trackWork(viewModelScope.launch {
                if (linkOnShelf(deviceKey(), name, found)) loadLibrary()
            })
        }
    }

    /** [book] is the Calibre record of an upload titled [title] by [author]: same title, compatible author. */
    private fun importedAs(book: Book, title: String, author: String): Boolean {
        fun norm(v: String) = v.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (norm(book.title) != norm(title) || norm(title).isEmpty()) return false
        val want = norm(author)
        val have = norm(book.author)
        return want.isEmpty() || want == "unknown" || have == want || have.contains(want) || want.contains(have)
    }

    /**
     * Pulls the reader's crash report, the one diagnostic the protocol offers.
     */
    fun fetchCrashReport() = viewModelScope.launch {
        val kinds = _state.value.device?.downloadKinds.orEmpty()
        if ("crash_report" !in kinds) {
            _state.value = _state.value.copy(message = "This reader has no crash report to send")
            return@launch
        }
        showTransfer(TransferProgress("Crash report", 0, 0, owner = OWNER_CRASH))
        val outcome = runCatching { ble.download("crash_report") }
        endTransfer(OWNER_CRASH)
        _state.value = _state.value.copy(
            message = outcome.fold(
                onSuccess = { bytes ->
                    books.writeDiagnostic("crash_report.txt", bytes)
                    "Saved crash_report.txt (${bytes.size} bytes)"
                },
                onFailure = { "Crash report failed: ${it.message}" },
            ),
        )
    }

    /**
     * Stages a downloaded, signed image on the reader and reports what the reader
     * made of it. Nothing is flashed here: the reader checks the SHA-256, that the
     * version is newer and the signature, then asks whether to install.
     */
    private suspend fun uploadFirmware(staged: File, displayName: String, version: String?, signature: String?) {
        if (version != null) {
            resumeFirmwareVersion = version
            _state.value = _state.value.copy(firmwareProgress = FirmwareProgress(version, FirmwarePhase.SENDING))
        }
        val label = if (version != null) "Sending firmware $version to reader…" else "Sending $displayName to reader…"
        showTransfer(TransferProgress(label, 0, staged.length(), owner = OWNER_FIRMWARE))
        val gate = ProgressGate()
        val meter = RateMeter()
        val outcome = runCatching {
            ble.upload(staged, name = "firmware.bin", kind = "firmware", version = version, signature = signature) { sent, total ->
                // Every call, so the meter has samples between the bar's updates.
                val kbps = meter.kbps(sent)
                if (gate.due(sent, total)) {
                    val p = TransferProgress(label, sent, total, kbps, owner = OWNER_FIRMWARE)
                    showTransfer(p) { s ->
                        s.copy(firmwareProgress = s.firmwareProgress?.copy(percent = p.percent, kbps = kbps))
                    }
                }
            }
        }
        staged.delete()

        endTransfer(OWNER_FIRMWARE)
        _state.value = _state.value.copy(
            message = outcome.fold(
                onSuccess = { result ->
                    // A rejected image comes back as finalState "error", not as a
                    // thrown exception: the reader validates on commit and reports
                    // the reason while we are still connected to hear it. Treating
                    // that as success would tell the user to go confirm an update
                    // the reader has already thrown away.
                    if (result.finalState == "error") {
                        val reason = runCatching { ble.readStatus().error }.getOrNull()
                        "Reader rejected the image: ${reason ?: "invalid firmware"}"
                    } else {
                        lastFirmwareCheckAt = 0L
                        "Sent $displayName to the reader"
                    }
                },
                onFailure = {
                    if (version != null) "Firmware send interrupted. It resumes when the reader reconnects."
                    else "Firmware upload failed: ${it.message}"
                },
            ),
        )
        // The reader holds it now. Recorded here rather than waiting for the next
        // `about` read, because the periodic check never reads the reader and would
        // otherwise see "update available, nothing staged" and send it all again.
        if (outcome.getOrNull()?.finalState?.let { it != "error" } == true) {
            _state.value = _state.value.copy(readerUpdateStaged = true)
            if (version != null) {
                resumeFirmwareVersion = null
                _state.value = _state.value.copy(firmwareProgress = FirmwareProgress(version, FirmwarePhase.INSTALLING))
                watchFirmwareInstall(version)
            }
        } else if (version != null) {
            // Rejected by the reader (a completed transfer answering "error") is final;
            // only a send the link cut off is worth resuming.
            if (outcome.isSuccess) resumeFirmwareVersion = null
            _state.value = _state.value.copy(firmwareProgress = null)
        }
    }

    /**
     * What the reader runs and what the update page offers, for the Firmware
     * screen. The halves are independent and either can fail alone: the page may
     * be unset or unreachable, and firmware from before the `about` download
     * cannot say its version (the install still works -- the upload path is
     * older than the version report).
     */
    fun checkFirmware(readReader: Boolean = true, freshAbout: Boolean = true) = viewModelScope.launch {
        val base = _state.value.config.effectiveUpdatesUrl.trimEnd('/')
        _state.value = _state.value.copy(firmwareChecking = true)
        // No update page set: nothing to fetch. The reader half still runs.
        val manifest = if (base.isBlank()) null
        else async {
            runCatching {
                traced<FirmwareManifest>("firmware page") { withContext(Dispatchers.IO) { fetchManifest(base) } }
            }
        }
        val linked = _state.value.connected && _state.value.authorized
        val about = if (linked && readReader) readAbout(fresh = freshAbout) else null
        // Firmware from before the `about` download refuses the kind outright, and
        // that is itself an answer: the version report shipped with the update
        // page, so a reader that cannot give one is older than anything on it. Any
        // OTHER failure (a dropped link) says nothing, and must not raise a badge.
        // Not read this time (the periodic network-only check): the reader's answers
        // from the last read stand.
        val tooOld = if (about == null) {
            _state.value.readerFirmwareTooOld
        } else {
            about.exceptionOrNull()?.let { e ->
                val text = ((e as? BleClient.BleException)?.code ?: "") + " " + (e.message ?: "")
                text.contains("unsupported", ignoreCase = true)
            } == true
        }
        val aboutDoc = about?.getOrNull()
        val running = aboutDoc?.optString("firmware_version")?.ifBlank { null }
            ?: _state.value.readerFirmware.takeIf { !tooOld }
        val stagedReported = aboutDoc?.optBoolean("update_staged", false)
            ?: (about == null && _state.value.readerUpdateStaged)
        val atSleep = aboutDoc?.optBoolean("install_at_sleep", false)
            ?: (about == null && _state.value.readerInstallAtSleep)
        val m = manifest?.await()
        val latest = if (base.isBlank()) null else m?.getOrNull() ?: _state.value.latestFirmware
        // An image already waiting on the reader blocks a send only when it IS the
        // newest build. An older one is stale -- say, left staged after its prompt was
        // dismissed while a newer build went up -- and sending the new image
        // replaces it on the reader (a firmware start_put clears the old stage).
        val stagedVersion = aboutDoc?.optString("staged_version")?.ifBlank { null }
        val staged = stagedReported &&
            !(stagedVersion != null && latest != null && stagedVersion < latest.version)
        lastFirmwareCheckAt = System.currentTimeMillis()
        _state.value = _state.value.copy(
            readerFirmware = running,
            readerFirmwareTooOld = tooOld,
            latestFirmware = latest,
            firmwareCheckNote = if (base.isBlank()) HTTPS_REQUIRED
            else m?.exceptionOrNull()?.let { e ->
                // "Unsigned firmware" / "malformed firmware.json" say more than "unreachable".
                if (e is IllegalArgumentException) e.message else "Could not reach the update page ($base)"
            },
            // Stamps are yyyyMMdd.HHmm, so string order is build order.
            firmwareUpdateAvailable = latest != null && (running?.let { it < latest.version } ?: tooOld),
            readerUpdateStaged = staged,
            readerInstallAtSleep = atSleep,
            firmwareChecking = false,
        )
        // An update in flight ends when the reader itself reports the build -- this
        // is usually the check the reconnect runs after the reader reboots into it.
        _state.value.firmwareProgress?.let { p ->
            if (running != null && running >= p.version) {
                finishFirmwareUpdate(running)
            } else if (aboutDoc != null && atSleep && p.phase == FirmwarePhase.INSTALLING) {
                _state.value = _state.value.copy(firmwareProgress = p.copy(phase = FirmwarePhase.SCHEDULED))
            } else if (aboutDoc != null && !staged && p.phase == FirmwarePhase.SCHEDULED) {
                _state.value = _state.value.copy(
                    firmwareProgress = null,
                    message = "The update to ${p.version} is no longer waiting on the reader",
                )
            }
        }
        // Download automatically: send a newer build as soon as it is seen -- unless
        // the reader already holds one, waiting on Update now / Later. Without that
        // check the same 4.6 MB would cross the radio again on every check.
        if (_state.value.config.autoDownloadFirmware && linked && latest != null &&
            _state.value.firmwareUpdateAvailable && !staged && _state.value.transfer == null &&
            _state.value.firmwareProgress == null
        ) {
            installLatestFirmware()
        }
    }

    /** The Reader settings switch for dark mode. Saved quietly, and applied now when on. */
    fun setMatchPhoneDarkMode(on: Boolean) = viewModelScope.launch {
        val c = _state.value.config.copy(matchPhoneDarkMode = on)
        settings.save(c)
        _state.value = _state.value.copy(config = c)
        if (on) matchPhoneDarkMode()
    }

    /** The Reader settings switch. Saved quietly: no catalogue reload, no "Settings saved". */
    fun setAutoDownloadFirmware(on: Boolean) = viewModelScope.launch {
        val c = _state.value.config.copy(autoDownloadFirmware = on)
        settings.save(c)
        _state.value = _state.value.copy(config = c)
        if (on) {
            lastFirmwareCheckAt = 0L
            checkFirmware()
        }
    }

    /**
     * Keeps looking for a newer reader build for as long as the reader is
     * connected, so an update is seen -- and, with auto-download on, sent -- during
     * a long session instead of only at the next reconnect. Silent: the only
     * visible result is the pill badge (or the upload itself).
     *
     * Network only. The reader's version was read at connect; a Bluetooth request
     * every few minutes would count as activity on the reader, reset its sleep
     * timer each time, and keep it awake for as long as the phone is in range.
     */
    private fun pollFirmware() = viewModelScope.launch {
        while (isActive) {
            delay(FIRMWARE_POLL_MS)
            val s = _state.value
            if (!s.connected || !s.authorized || s.transfer != null || syncJob?.isActive == true) continue
            if (s.firmwareProgress != null) continue
            if (s.config.effectiveUpdatesUrl.isBlank()) continue
            if (System.currentTimeMillis() - lastFirmwareCheckAt < FIRMWARE_POLL_MS) continue
            checkFirmware(readReader = false).join()
        }
    }

    /**
     * A firmware send that the link cut off starts again once the reader is back and
     * its sync has run -- the user already asked for it. Not when the reader already
     * runs that build or already holds an image (staged, waiting on Update Now).
     */
    private fun resumeInterruptedFirmware() {
        val version = resumeFirmwareVersion ?: return
        viewModelScope.launch {
            syncJob?.join()
            val s = _state.value
            if (!s.connected || !s.authorized || s.firmwareProgress != null || s.transfer != null) return@launch
            if ((s.readerFirmware ?: "") >= version) {
                resumeFirmwareVersion = null
                return@launch
            }
            if (s.readerUpdateStaged) return@launch
            installLatestFirmware()
        }
    }

    /** When [checkFirmware] last ran; 0 forces the next connect to check. */
    private var lastFirmwareCheckAt = 0L

    /**
     * The badge's source: one check per connection, straight away. The `about` read
     * is a few hundred bytes and slots in between the sync's transfers, so the badge
     * does not wait for the whole sync; a send it triggers still does (see
     * [installLatestFirmware]). Throttled so a flapping link does not hit the update
     * page on every reconnect; a firmware upload resets it, so the reconnect after
     * the reader reboots into the new build clears the badge at once.
     */
    private fun checkFirmwareOnConnect() {
        // No update page set: nothing to compare against. An update already in flight
        // still gets its check, since that is what sees the reader run the new build.
        if (_state.value.config.effectiveUpdatesUrl.isBlank() && _state.value.firmwareProgress == null) return
        // With automatic download on, every reconnect checks: otherwise the checks made
        // while a send was failing use up the throttle window, and the reconnect after
        // it goes by without sending.
        if (!_state.value.config.autoDownloadFirmware &&
            System.currentTimeMillis() - lastFirmwareCheckAt < FIRMWARE_CHECK_MS
        ) return
        // The session's first `about` read; the first send shares it.
        trackWork(checkFirmware(freshAbout = false))
    }

    /**
     * Downloads the image [checkFirmware] found, proves it is the published one,
     * and stages it on the reader.
     *
     * The SHA-256 from firmware.json is checked HERE, before a byte crosses the
     * radio: a truncated or wrong download is caught in seconds on the phone
     * rather than after minutes of Bluetooth. The reader then verifies the upload
     * against the digest the app sends with it, and validates the image itself.
     */
    fun installLatestFirmware() = viewModelScope.launch {
        if (_state.value.firmwareProgress != null) return@launch
        val base = _state.value.config.effectiveUpdatesUrl.trimEnd('/')
        // Also reached from background resumes and auto-download: stay quiet.
        if (base.isBlank()) return@launch
        _state.value = _state.value.copy(firmwareProgress = FirmwareProgress("", FirmwarePhase.DOWNLOADING))
        // Re-read the update page before anything else. The manifest in state can
        // predate the newest build on the page.
        val m = runCatching { withContext(Dispatchers.IO) { fetchManifest(base) } }.getOrElse {
            _state.value = _state.value.copy(
                firmwareProgress = null,
                message = if (it is IllegalArgumentException) it.message else "Could not reach the update page ($base)",
            )
            return@launch
        }
        _state.value = _state.value.copy(latestFirmware = m)
        val running = _state.value.readerFirmware
        if (running != null && running >= m.version) {
            _state.value = _state.value.copy(
                firmwareProgress = null,
                firmwareUpdateAvailable = false,
                message = "The reader already runs ${m.version}",
            )
            return@launch
        }
        val url = "$base/${m.file}"
        val target = File(getApplication<Application>().cacheDir, "firmware-latest.bin")
        val label = "Firmware ${m.version}"
        val downloading = "Downloading firmware ${m.version}…"
        showTransfer(TransferProgress(downloading, 0, m.size, owner = OWNER_FIRMWARE)) {
            it.copy(firmwareProgress = FirmwareProgress(m.version, FirmwarePhase.DOWNLOADING))
        }
        val fetched = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    // Never more than the manifest says, and never over 8 MB.
                    val cap = minOf(m.size, HttpGuard.FIRMWARE_MAX)
                    HttpGuard.checkDeclared(r, cap, "Firmware image")
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    val gate = ProgressGate()
                    val meter = RateMeter()
                    r.body!!.byteStream().use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                total += n
                                if (total > cap) throw HttpGuard.TooLarge("Firmware image")
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                val kbps = meter.kbps(total)
                                // Checked here, so a skipped update costs no thread hop.
                                if (gate.due(total, m.size)) {
                                    val p = TransferProgress(downloading, total, m.size, kbps, owner = OWNER_FIRMWARE)
                                    withContext(Dispatchers.Main) {
                                        showTransfer(p) {
                                            it.copy(
                                                firmwareProgress = FirmwareProgress(
                                                    m.version, FirmwarePhase.DOWNLOADING, p.percent, kbps,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (total != m.size) error("the download is $total bytes, not ${m.size}")
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!hex.equals(m.sha256, ignoreCase = true)) {
                        error("the download does not match its published SHA-256")
                    }
                }
            }
        }
        if (fetched.isFailure) {
            target.delete()
            endTransfer(OWNER_FIRMWARE) {
                it.copy(
                    firmwareProgress = null,
                    message = "Could not download $label: ${fetched.exceptionOrNull()?.message}",
                )
            }
            return@launch
        }
        // The phone-side download runs at once; the radio waits for the connect sync
        // so the book listing is not queued behind 4.6 MB of firmware. The bar says
        // what comes next rather than dropping back to the sync line; with no size
        // yet it gives way to the sync's own sends (see shownTransfer).
        showTransfer(TransferProgress("Sending firmware ${m.version} to reader…", 0, 0, owner = OWNER_FIRMWARE)) {
            it.copy(firmwareProgress = FirmwareProgress(m.version, FirmwarePhase.SENDING))
        }
        syncJob?.join()
        uploadFirmware(target, label, m.version, m.signature)
    }

    private fun fetchManifest(base: String): FirmwareManifest =
        http.newCall(okhttp3.Request.Builder().url("$base/firmware.json").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            FirmwareManifest.parse(HttpGuard.string(r, HttpGuard.MANIFEST_MAX, "firmware.json"))
        }

    private var firmwareWatchJob: Job? = null

    /**
     * After an image is staged, follow the reader until it runs [target].
     *
     * The reader's answer arrives in one of three ways: it reboots into the build
     * (link drops, the reconnect check or this loop reads the new version), it
     * defers to sleep (`install_at_sleep`), or the staged image goes away. Polled
     * over Bluetooth every [FIRMWARE_WATCH_POLL_MS] -- acceptable here, since the
     * reader has an update prompt on screen -- and given up after
     * [FIRMWARE_INSTALL_TIMEOUT_MS], so it cannot keep the reader awake for good.
     */
    private fun watchFirmwareInstall(target: String) {
        firmwareWatchJob?.cancel()
        firmwareWatchJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + FIRMWARE_INSTALL_TIMEOUT_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(FIRMWARE_WATCH_POLL_MS)
                val s = _state.value
                if (s.firmwareProgress?.version != target) return@launch
                if (!s.connected || !s.authorized || s.transfer != null) continue
                val doc = runCatching {
                    org.json.JSONObject(String(ble.download("about"), Charsets.UTF_8))
                }.getOrNull() ?: continue
                val running = doc.optString("firmware_version").ifBlank { null }
                if (running != null && running >= target) {
                    finishFirmwareUpdate(running)
                    return@launch
                }
                if (doc.optBoolean("install_at_sleep", false)) {
                    _state.value = _state.value.copy(
                        readerInstallAtSleep = true,
                        firmwareProgress = FirmwareProgress(target, FirmwarePhase.SCHEDULED),
                    )
                    return@launch
                }
                if (!doc.optBoolean("update_staged", false)) {
                    _state.value = _state.value.copy(
                        firmwareProgress = null,
                        readerUpdateStaged = false,
                        message = "The update to $target was not installed on the reader",
                    )
                    return@launch
                }
            }
            val p = _state.value.firmwareProgress
            if (isActive && p?.version == target && p.phase == FirmwarePhase.INSTALLING) {
                _state.value = _state.value.copy(
                    firmwareProgress = null,
                    message = "The reader has not installed $target yet",
                )
            }
        }
    }

    /** The reader reported the new build: the only thing that ends an update. */
    private fun finishFirmwareUpdate(running: String) {
        resumeFirmwareVersion = null
        val latest = _state.value.latestFirmware
        _state.value = _state.value.copy(
            firmwareProgress = null,
            readerFirmware = running,
            readerFirmwareTooOld = false,
            readerUpdateStaged = false,
            readerInstallAtSleep = false,
            firmwareUpdateAvailable = latest != null && running < latest.version,
            message = "Reader updated to $running",
        )
        firmwareWatchJob?.cancel()
    }

    // ------------------------------------------------------- reader settings

    /**
     * The last document the reader sent, verbatim.
     *
     * Held whole rather than reduced to the keys this app understands, because
     * that is exactly what goes back on Save — see the comment at the top of
     * [DeviceSettings]. Everything the UI does not touch (the SD font name, the
     * dictionary, the front-button remap, `settingsRev`, the obfuscated
     * credential blobs) rides along untouched and lands as an identity write.
     */
    private var deviceSettingsDoc: org.json.JSONObject? = null

    /**
     * Publishes the reader's name to the pill, and remembers it across launches.
     * Null (a document without the field, from older firmware) changes nothing;
     * blank is the name the reader advertises by default.
     */
    private fun rememberDeviceName(name: String?) {
        if (name == null) return
        val clean = name.trim().take(DeviceSettingsSchema.DEVICE_NAME_MAX_BYTES)
            .ifBlank { DeviceSettingsSchema.DEFAULT_DEVICE_NAME }
        if (clean == _state.value.deviceName) return
        _state.value = _state.value.copy(deviceName = clean)
        viewModelScope.launch { runCatching { pairingStore.setDeviceName(clean) } }
    }

    private fun setDeviceSettings(ui: DeviceSettingsUi) {
        _state.value = _state.value.copy(deviceSettings = ui)
    }

    /**
     * Pulls the reader's settings document.
     *
     * [force] is the Retry path and the pull-again path; without it a screen
     * that is already loaded is left alone, so rotating the phone does not cost
     * a BLE round trip.
     */
    fun loadDeviceSettings(force: Boolean = false) = viewModelScope.launch {
        val ui = _state.value.deviceSettings
        if (ui.loading || ui.saving) return@launch
        if (!force && ui.loaded) return@launch

        val s = _state.value
        if (!s.connected || !s.authorized) {
            setDeviceSettings(
                ui.copy(loading = false, loadError = "The reader is not connected.")
            )
            return@launch
        }
        val kinds = s.device?.downloadKinds.orEmpty()
        if (kinds.isNotEmpty() && "settings" !in kinds) {
            setDeviceSettings(
                ui.copy(
                    loading = false,
                    loadError = "This reader's firmware cannot send its settings over Bluetooth.",
                )
            )
            return@launch
        }

        setDeviceSettings(ui.copy(loading = true, loadError = null, notice = null))
        runCatching {
            val bytes = traced<ByteArray>("settings", bytes = { it.size.toLong() }, note = { ble.lastDownloadShape }) {
                ble.download("settings")
            }
            org.json.JSONObject(String(bytes, Charsets.UTF_8).trim())
        }.fold(
            onSuccess = { doc ->
                deviceSettingsDoc = doc
                setDeviceSettings(
                    DeviceSettingsUi(
                        values = DeviceSettingsSchema.readValues(doc),
                        textValues = DeviceSettingsSchema.readTextValues(doc),
                    )
                )
                // The pill shows this, and the pill is visible long before
                // anyone opens the settings sheet -- so cache it the moment it
                // is known rather than only while the sheet is up.
                rememberDeviceName(DeviceSettingsSchema.readTextValues(doc)["deviceName"])
            },
            onFailure = { e ->
                deviceSettingsDoc = null
                setDeviceSettings(
                    _state.value.deviceSettings.copy(
                        loading = false,
                        loadError = e.message ?: "Could not read the reader's settings.",
                    )
                )
            },
        )
    }

    /** Records a pending change. Setting a row back to the reader's own value
     *  drops it from the pending set rather than queuing a no-op write. */
    fun editDeviceSetting(key: String, value: Int) {
        val ui = _state.value.deviceSettings
        val edits = ui.edits.toMutableMap()
        if (ui.values[key] == value) edits.remove(key) else edits[key] = value
        setDeviceSettings(ui.copy(edits = edits, notice = null))
    }

    /** Records a pending change to a string setting. */
    fun editDeviceSettingText(key: String, value: String) {
        val ui = _state.value.deviceSettings
        val edits = ui.textEdits.toMutableMap()
        if ((ui.textValues[key] ?: "") == value) edits.remove(key) else edits[key] = value
        setDeviceSettings(ui.copy(textEdits = edits, notice = null))
    }

    fun discardDeviceSettingEdits() {
        setDeviceSettings(
            _state.value.deviceSettings.copy(edits = emptyMap(), textEdits = emptyMap(), notice = null)
        )
    }

    fun dismissDeviceSettingsNotice() {
        setDeviceSettings(_state.value.deviceSettings.copy(notice = null))
    }

    /** Forgets the screen's state so the next open re-reads the device, which
     *  may have been changed on its own screen in the meantime. */
    fun closeDeviceSettings() {
        deviceSettingsDoc = null
        setDeviceSettings(DeviceSettingsUi())
    }

    /**
     * Writes the edited document back.
     *
     * One BLE round trip plus a flash write on the device, which is why this is
     * an explicit button and not a side effect of every switch.
     */
    fun saveDeviceSettings() = viewModelScope.launch {
        val ui = _state.value.deviceSettings
        val doc = deviceSettingsDoc
        if (doc == null || ui.saving || !ui.dirty) return@launch

        val payload = DeviceSettingsSchema.applyEdits(doc, ui.edits, ui.textEdits)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)

        setDeviceSettings(ui.copy(saving = true, notice = null))
        showTransfer(TransferProgress("Reader settings", 0, bytes.size.toLong(), owner = OWNER_SETTINGS))
        val outcome = runCatching {
            ble.uploadBytes(bytes, kind = "settings") { sent, total ->
                showTransfer(TransferProgress("Reader settings", sent, total, owner = OWNER_SETTINGS))
            }
        }
        endTransfer(OWNER_SETTINGS)

        outcome.fold(
            onSuccess = { result ->
                // The reader can refuse on commit while we are still connected
                // to hear it, in which case nothing is thrown -- the same shape
                // the firmware upload has to handle.
                if (result.finalState == "error") {
                    val reason = runCatching { ble.readStatus().error }.getOrNull()
                    setDeviceSettings(
                        _state.value.deviceSettings.copy(
                            saving = false,
                            notice = SettingsNotice(settingsFailureText(reason, null), true),
                        )
                    )
                } else {
                    deviceSettingsDoc = payload
                    rememberDeviceName(DeviceSettingsSchema.readTextValues(payload)["deviceName"])
                    setDeviceSettings(
                        _state.value.deviceSettings.copy(
                            saving = false,
                            values = DeviceSettingsSchema.readValues(payload),
                            textValues = DeviceSettingsSchema.readTextValues(payload),
                            edits = emptyMap(),
                            textEdits = emptyMap(),
                            notice = SettingsNotice("Settings saved on the reader.", false),
                        )
                    )
                }
            },
            onFailure = { e ->
                setDeviceSettings(
                    _state.value.deviceSettings.copy(
                        saving = false,
                        notice = SettingsNotice(
                            settingsFailureText((e as? BleClient.BleException)?.code, e.message),
                            true,
                        ),
                    )
                )
            },
        )
    }

    /**
     * "book open" is the reader declining, not failing: it holds the open
     * book's state in memory and would write it back over anything we changed,
     * so it refuses the whole document at `start_put`. The user needs to be
     * told what to do about it, not shown a protocol string.
     */
    private fun settingsFailureText(code: String?, fallback: String?): String = when (code) {
        "book open" ->
            "Close the book on the reader, then save again."
        else -> fallback ?: "The reader did not save the settings."
    }

    /**
     * Re-runs trusted authentication on a link that is already up.
     *
     * Only ever silent: it fires from a state observer, so it must not put a
     * failure in front of the user who has not asked for anything. A failure
     * leaves the existing prompt-on-demand paths to say so.
     */
    private var reauthInFlight = false

    /** When the catalogue last loaded; drives [refreshCatalogueIfStale]. */
    private var lastCatalogueAt = 0L

    /**
     * Keeps the phone current with Calibre WITHOUT holding up the reader.
     * Launched beside the connect sync, and only when the catalogue is over
     * [CATALOGUE_STALE_MS] old; anything it finds queues its own pass through
     * calibreQueue, exactly as a manual refresh does.
     */
    private fun refreshCatalogueIfStale() {
        if (!_state.value.config.serverConfigured) return
        if (System.currentTimeMillis() - lastCatalogueAt >= CATALOGUE_STALE_MS) trackWork(refresh())
    }

    private fun reauthenticate() {
        if (reauthInFlight || pairingInProgress) return
        reauthInFlight = true
        viewModelScope.launch {
            try {
                val identity = pairingStore.load() ?: return@launch
                val ok = runCatching { ble.authenticate(identity) }.getOrDefault(false)
                if (ok) onAuthorized(silent = true)
                else if (readerForgotPhone()) onReaderForgotPhone(silent = true)
            } finally {
                reauthInFlight = false
                _state.value = _state.value.copy(authTrace = ble.lastAuthTrace, lastAuthError = ble.lastAuthError)
            }
        }
    }

    /**
     * Keeps the link honest and keeps it up.
     *
     * Two failures this answers:
     *
     *  - **Stale CONNECTED.** When the reader reboots (a firmware flash, a wake
     *    from sleep) Android does not notice the peer vanish until a supervision
     *    timeout, so the app goes on reporting "connected" to something that is
     *    no longer there. Since authentication is suppressed while `authorized`
     *    is true, nothing re-checks and the state never corrects itself.
     *  - **No reconnect.** connectReader() runs once at launch. Without this, a
     *    link that drops for any reason stays dropped until the user acts.
     *
     * The reader's READ status is the arbiter: it names its trusted host only
     * while a session is genuinely through the hello gate. A read that fails, or
     * that comes back without the host, means this app is not authenticated no
     * matter what it currently believes.
     */
    private fun superviseLink() = viewModelScope.launch {
        var backoffMs = RECONNECT_MIN_MS
        while (isActive) {
            delay(LINK_CHECK_MS)
            if (pairingInProgress) continue
            val identity = pairingStore.load() ?: continue
            if (!BlePermissions.granted(getApplication())) continue

            when (ble.connection.value) {
                BleConnection.CONNECTED -> {
                    backoffMs = RECONNECT_MIN_MS
                    val status = runCatching { ble.readStatus() }.getOrNull()
                    if (status == null) {
                        // The peer is gone; Android just has not admitted it yet.
                        // Dropping it ourselves is what starts the reconnect.
                        ble.disconnect()
                    } else if (!status.trustedHost) {
                        _state.value = _state.value.copy(authorized = false)
                        ble.markUnauthorized()
                        reauthenticate()
                    }
                }
                BleConnection.IDLE -> {
                    connectReader(silent = true)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
                }
                else -> Unit  // SCANNING / CONNECTING: already trying
            }
        }
    }

    // --- background ------------------------------------------------------------

    /** The reader was seen advertising, or the service started: connect if nothing is trying. */
    fun onReaderNearby() {
        viewModelScope.launch {
            if (pairingInProgress || pairingStore.load() == null) return@launch
            // The presence scan reports every advertisement, several a second while the
            // reader waits for a phone. At most one attempt every NEARBY_RETRY_MS, and
            // never beside one already running.
            val now = System.currentTimeMillis()
            if (now - lastNearbyAttemptAt < NEARBY_RETRY_MS) return@launch
            if (connectJob?.isActive == true || ble.connection.value != BleConnection.IDLE) return@launch
            lastNearbyAttemptAt = now
            connectReader(silent = true)
        }
    }

    suspend fun isPaired(): Boolean = pairingStore.load() != null

    /** True while stopping the background service would cut something off. */
    fun hasBackgroundWork(): Boolean =
        syncJob?.isActive == true || heartbeatWriting || heartbeatPending.isNotEmpty() ||
            kosyncJob?.isActive == true || calibreJob?.isActive == true ||
            relinkJob?.isActive == true || addingToCalibre.isNotEmpty() ||
            _state.value.transfer != null ||
            // The reader reboots mid-install; the watch must outlive the dropped link.
            _state.value.firmwareProgress?.phase == FirmwarePhase.INSTALLING

    /**
     * Only reached if BluecarrelApp's process-wide store is ever cleared: this is no
     * longer scoped to the Activity, so closing the app does not come here.
     */
    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterComponentCallbacks(configWatcher)
        // Not launched on viewModelScope: that scope is already cancelled here.
        ble.disconnect()
    }
}
