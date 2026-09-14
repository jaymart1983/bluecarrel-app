package com.jmart.x4sync

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.jmart.x4sync.sync.ReaderPresence
import com.jmart.x4sync.sync.ReaderSyncService
import androidx.lifecycle.viewModelScope
import com.jmart.x4sync.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    val fraction: Float get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
}

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
    val storedHostId: String? = null,
    /** Store diagnostics: what the app has seen and done about reader requests. */
    val storeTrace: String = "no request seen",
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
        /** Coalesce upload progress to roughly 100 UI updates per book. */
        const val PROGRESS_STEP_BYTES = 32 * 1024L

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
    }

    private val settings = SettingsStore(app)
    private val pairingStore = PairingStore(app)
    private val sentBooks = SentBooksStore(app)
    private val pendingRemovals = PendingRemovalStore(app)
    private val startFresh = StartFreshStore(app)
    private val replaceOnSend = ReplaceOnSendStore(app)

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

    init {
        store.attach(viewModelScope)
        superviseLink()
        pollFirmware()

        viewModelScope.launch {
            val identity = pairingStore.load()
            _state.value = _state.value.copy(
                config = settings.config.first(),
                deviceName = runCatching { pairingStore.deviceName.first() }.getOrDefault(""),
                permissionsGranted = BlePermissions.granted(getApplication()),
                pairing = if (identity != null) PairingState.TRUSTED else PairingState.UNPAIRED,
                hasStoredPairing = identity != null,
                storedHostId = identity?.hostId,
            )
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
                val s = _state.value
                _state.value = s.copy(
                    connection = c,
                    authorized = if (c == BleConnection.CONNECTED) s.authorized else false,
                    transfer = if (c == BleConnection.CONNECTED) s.transfer else null,
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
                if (c == BleConnection.CONNECTED && !_state.value.authorized) {
                    reauthenticate()
                }
                // Keep the process alive for as long as the link is: closing the
                // app must not end a sync or the heartbeat kosync writes.
                if (c == BleConnection.CONNECTED) ReaderSyncService.start(getApplication())
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
                        if (!first && _state.value.connected && _state.value.authorized) {
                            mirrorToDevice()
                        }
                    }
                }

                // A replace refused with "book open" needs something to retry it.
                // Closing a book saves its position, and the reader notifies on
                // that, so the next status after the book is closed retries the send.
                // Throttled: while the book stays open, every page turn notifies.
                if (bookOpenDeferredAt != 0L && _state.value.connected && _state.value.authorized) {
                    val now = System.currentTimeMillis()
                    if (now - lastBookOpenRetryAt >= BOOK_OPEN_RETRY_MS) {
                        lastBookOpenRetryAt = now
                        mirrorToDevice()
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
        // No settings: the reader-settings screen loads them when it opens, and
        // nothing else reads them.
        return requestSync(catalogue = true)
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
        if (identity == null) {
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
        val outcome = runCatching { ble.connect(identity.address, allowBond = false) }
        val status = outcome.getOrElse { e ->
            failLink(e, silent)
            return@launch
        }

        if (identity.deviceId != null && status.deviceId != identity.deviceId) {
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
        val ok = runCatching { ble.authenticate(identity) }.getOrDefault(false)
        if (ok) {
            onAuthorized(silent)
        } else {
            _state.value = _state.value.copy(
                authorized = false,
                authTrace = ble.lastAuthTrace,
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
     * Pairs with a reader whose Settings screen is open (security v2).
     *
     * Scans by service UUID, connects, bonds (Android's dialog takes the passkey
     * the reader shows), sends `pair` only on a bonded link while the reader's
     * pairing window is open, then a hello. The pairing -- identity, the reader's
     * device_id and Bluetooth address -- is stored only once reader_proof verifies.
     */
    fun pairReader(): Job {
        connectJob?.takeIf { it.isActive }?.let { return it }
        return viewModelScope.launch {
        if (!linkPreflight()) return@launch
        if (pairingStore.load() != null) {
            _state.value = _state.value.copy(message = "Forget the current pairing first")
            return@launch
        }
        _state.value = _state.value.copy(
            permissionsGranted = true,
            message = null,
            link = LinkStatus(LinkStage.SCANNING),
        )

        val status = runCatching { ble.connect(address = null, allowBond = true) }.getOrElse { e ->
            failLink(e, silent = false)
            return@launch
        }
        val address = ble.connectedAddress()
        val deviceId = status.deviceId
        if (address == null || deviceId == null) {
            ble.disconnect()
            failLink(
                BleClient.BleException("The reader did not identify itself", reason = BleClient.Reason.NOT_A_READER),
                silent = false,
            )
            return@launch
        }
        if (!status.pairingWindow) {
            ble.disconnect()
            _state.value = _state.value.copy(
                link = LinkStatus(LinkStage.NEEDS_PAIRING, reason = "Pairing is closed on the reader", hint = PAIR_HINT),
            )
            return@launch
        }

        _state.value = _state.value.copy(link = LinkStatus(LinkStage.PAIRING))
        val identity = pairingStore.mint().copy(deviceId = deviceId, address = address)
        runCatching { ble.pair(identity) }.exceptionOrNull()?.let { e ->
            ble.disconnect()
            failLink(e, silent = false)
            return@launch
        }
        val ok = runCatching { ble.authenticate(identity) }.getOrDefault(false)
        if (!ok) {
            ble.disconnect()
            _state.value = _state.value.copy(
                authorized = false,
                authTrace = ble.lastAuthTrace,
                link = LinkStatus(LinkStage.FAILED, reason = "Pairing failed", hint = PAIR_HINT),
                message = "Pairing failed",
            )
            return@launch
        }

        pairingStore.save(identity)
        _state.value = _state.value.copy(hasStoredPairing = true, storedHostId = identity.hostId)
        ReaderPresence.register(getApplication())
        onAuthorized(silent = true)
        _state.value = _state.value.copy(message = "Paired")
        }.also { connectJob = it }
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
        _state.value = _state.value.copy(
            authorized = true,
            pairing = PairingState.TRUSTED,
            link = LinkStatus(LinkStage.CONNECTED),
            authTrace = ble.lastAuthTrace,
            message = if (silent) null
            else "Connected to ${_state.value.deviceName.ifBlank { "the reader" }}",
        )
        viewModelScope.launch {
            // The reader has no clock of its own worth trusting; tell it the
            // time and the zone before anything else uses a timestamp.
            runCatching { ble.setDeviceTime() }
            // The shelf is the contract: whatever is saved offline belongs on the
            // reader, so a fresh connection is the moment to make that true.
            requestSync(positions = true)
            refreshCatalogueIfStale()
            checkFirmwareOnConnect()
            resumeInterruptedFirmware()
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
                "That device is not a CrossPoint reader",
                "Move closer to the reader and retry.",
            )
            BleClient.Reason.AUTH ->
                if ((e as? BleClient.BleException)?.code == "pairing window closed") {
                    LinkStatus(LinkStage.NEEDS_PAIRING, "Pairing is closed on the reader", PAIR_HINT)
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
                "Update the reader firmware",
                "",
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
            message = if (silent) null else status.reason,
        )
    }

    fun disconnectReader() = viewModelScope.launch {
        ble.disconnect()
        _state.value = _state.value.copy(
            authorized = false,
            transfer = null,
            storeActivity = null,
            link = LinkStatus(LinkStage.IDLE),
        )
    }

    /**
     * Clears the stored pairing. The Android bond stays: removing it takes a
     * hidden API, so the UI opens Bluetooth settings for the user to remove it.
     */
    fun forgetPairing() = viewModelScope.launch {
        pairingStore.clear()
        ReaderPresence.unregister(getApplication())
        ble.disconnect()
        _state.value = _state.value.copy(
            hasStoredPairing = false,
            storedHostId = null,
            authorized = false,
            pairing = PairingState.UNPAIRED,
            link = LinkStatus(LinkStage.IDLE),
            message = "Now remove the reader in Bluetooth settings",
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
     * Publishes the reader's reading positions to kosync.
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
     * this app and any other well-behaved writer set for exactly this reason,
     * and falls back
     * to the receive time only when the payload has none. See [Progress.ageStamp].
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
    fun syncProgressToKosync() = viewModelScope.launch {
        val c = _state.value.config
        if (c.username.isBlank() || !c.serverConfigured) {
            _state.value = _state.value.copy(message = "Set the server account first")
            return@launch
        }
        if (!_state.value.connected || !_state.value.authorized) {
            _state.value = _state.value.copy(message = "Connect the reader first")
            return@launch
        }
        if (_state.value.syncingProgress) return@launch

        // Reading the reader's library is itself a BLE download and is not
        // instant, so the bar goes up BEFORE it rather than after.
        _state.value = _state.value.copy(
            syncingProgress = true,
            transfer = TransferProgress("Syncing with reader…", 0, 0),
        )
        val listing = readerLibrary()
        if (listing == null) {
            _state.value = _state.value.copy(
                syncingProgress = false,
                transfer = null,
                message = "Could not read the reader's library",
            )
            return@launch
        }

        // A BARE ARRAY -- see pruneDeviceBooksLocked.
        val entries = runCatching {
            val arr = JSONArray(String(listing, Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.getOrElse { emptyList() }

        val kosync = KosyncClient(http, c.kosyncUrl, c.username, c.password)
        val deviceId = deviceKey() ?: "x4pro"
        val deviceName = _state.value.deviceName.ifBlank { "X4 Pro" }

        // Shown the same way a book transfer is. Each book costs a full-content
        // hash plus a kosync GET and possibly a PUT, so a shelf of any size is
        // seconds of silence otherwise -- and silence during a network operation
        // is indistinguishable from nothing having happened.
        _state.value = _state.value.copy(
            transfer = TransferProgress("Syncing with reader…", 0, entries.size.toLong())
        )

        var written = 0
        var skippedOlder = 0
        var skippedNoClock = 0
        var missingFile = 0
        var sideLoaded = 0
        var skippedNotAhead = 0
        // Positions the server has that the reader does not. Built during the
        // same walk, because it needs the same per-book facts (hash, device
        // timestamp) that the push already computes.
        val toDevice = mutableListOf<JSONObject>()
        // Read once for the whole pass rather than per book: it is a DataStore
        // round trip and the answer cannot change mid-walk.
        val freshStarts = startFresh.load(deviceKey())

        // Every book's kosync lookups, fetched TOGETHER before the walk. They ran
        // one after another -- two round trips per book through Cloudflare, about
        // eleven for a three-book shelf -- and that was most of a connect sync.
        // The walk below decides with the answers already in hand. Hashes come
        // from the same cache the heartbeat writes use.
        val cachedShelf = withContext(Dispatchers.IO) { books.cachedBooks() }.associateBy { it.filename }
        val prefetched: Map<String, Pair<String, Progress?>> = coroutineScope {
            entries.mapNotNull { entry ->
                val filename = entry.optString("filename").ifBlank { null } ?: return@mapNotNull null
                if (!entry.optBoolean("fromApp", true)) return@mapNotNull null
                val local = cachedShelf[filename] ?: return@mapNotNull null
                val hash = heartbeatHash(local) ?: return@mapNotNull null
                Triple(filename, local, hash)
            }.chunked(PREFETCH_PARALLEL).flatMap { chunk ->
                chunk.map { (filename, local, hash) ->
                    async { filename to (hash to kosync.progressFor(local.progressKey, hash, deviceName, deviceId)) }
                }.awaitAll()
            }.toMap()
        }

        for ((index, entry) in entries.withIndex()) {
            // Counted in BOOKS EXAMINED, not books written. Most of the wait is
            // hashing and GETting books that turn out to need nothing, so a bar
            // that only moved on a write would sit still through the slow part.
            _state.value = _state.value.copy(
                transfer = TransferProgress("Syncing with reader…", index.toLong(), entries.size.toLong())
            )
            val filename = entry.optString("filename").ifBlank { null } ?: continue
            val location = entry.optString("location").ifBlank { null } ?: continue
            // Omitted rather than zeroed by the device when unknown -- see
            // BookLibraryIndex::writeEntry. Absent means the clock was unset.
            val savedAt = entry.optLong("timestamp", 0L)
            if (savedAt < Progress.PLAUSIBLE_EPOCH) {
                skippedNoClock++
                continue
            }
            val percent = entry.optDouble("percent", 0.0).toFloat()

            // Side-loaded books keep their progress to themselves.
            //
            // `fromApp` is the reader's own answer, from whether the phone's
            // metadata sidecar sits beside the book -- not a guess from what
            // this phone happens to be holding today. A book copied on over USB
            // has no Calibre original behind it, so there is nothing on the
            // server for its position to belong to: publishing it would key a
            // row to a file no other client will ever hold.
            //
            // Older firmware omits the field. Absent is treated as "from the
            // app", because that was the only way a book could arrive before
            // USB Drive existed, and defaulting the other way would silently
            // stop syncing every book on a reader that had not been updated.
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
            val hash = prefetched[filename]?.first ?: heartbeatHash(local)
            // Not `?: run { ...; continue }`: a continue inside an inline lambda
            // is an experimental Kotlin feature and does not compile here.
            if (hash == null) {
                missingFile++
                continue
            }

            val remote = if (prefetched.containsKey(filename)) prefetched.getValue(filename).second
            else kosync.progressFor(local.progressKey, hash, deviceName, deviceId)
            if (remote != null && remote.ageStamp >= savedAt) {
                skippedOlder++
                continue
            }

            // FORWARD ONLY. Never publish a position earlier than the one on the
            // server, even when this device's save is genuinely newer.
            //
            // The timestamp rule alone decides who wrote last, not who is
            // further on, and "last" is the wrong question when a sync jams: a
            // stale position that arrives late is still newer by the clock and
            // would drag every other client back to it. One bad write then
            // propagates, because the next device to sync sees the regressed
            // row as authoritative.
            //
            // Monotonic is the only rule that cannot lose a page. It costs the
            // deliberate cases -- re-reading a chapter, or restarting a book,
            // will not publish -- and after a handoff from another client this
            // reader must read PAST that client's percentage before it will
            // publish again, since the two measure percentage differently. That
            // is the price of never going backwards, and it is the trade the
            // user asked for.
            // The reverse direction, decided with the same two facts. The reader
            // gets a position only when the server's is NEWER and NOT BEHIND --
            // the same forward-only rule, applied the other way round, so a
            // stale server row cannot drag the reader backwards either.
            //
            // No `location` is sent. The server's position string is whatever
            // wrote it, and another reading system's position encoding indexes
            // a file this reader will never hold. The spine fields are the part
            // that means anything here, and the reader ignores them unless its
            // own spine count matches `spine_n`.
            // A book the user chose to restart is not pushed back to its old
            // position. The forward-only rule would otherwise make the server's
            // 43% beat the reader's page one every single sync, so "start from
            // the beginning" would survive for about as long as it took the
            // next sync to run.
            //
            // The instruction is spent as soon as the reader has a position of
            // its own: the user has read something, the reader is now the newer
            // authority, and ordinary syncing takes over again.
            if (filename in freshStarts) {
                if (percent > 0f) startFresh.forget(deviceKey(), filename)
            } else if (remote != null && remote.ageStamp > savedAt) {
                val payload = remote.payloadJson()
                val spine = payload?.optInt("spine", -1) ?: -1
                val spineN = payload?.optInt("spine_n", 0) ?: 0
                val remotePct = remote.percentage
                if (remotePct + 0.00005f >= percent) {
                    toDevice += JSONObject().apply {
                        put("filename", filename)
                        put("timestamp", remote.ageStamp)
                        // Sent with the position, not left to the device. The
                        // reader's library screen reads its percentage out of
                        // the sidecar written here, so omitting it stored 0 and
                        // ERASED the percentage rather than leaving it alone.
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
            // value. Both details matter.
            //
            // `percent` is the device's own figure, rounded to four decimals
            // before it left the reader. `remote.percentage` has been through
            // CWA's 0-100 conversion and back. Comparing those two floats raw
            // means float noise decides the outcome: if the round trip nudges
            // the stored value UP, `<=` blocks that book forever; if it nudges
            // DOWN, every sync rewrites it.
            //
            // Strictly-lower rather than not-greater, because reaching here
            // already means this device's save is NEWER (the set_at check
            // above). Rewriting an equal percentage with a corrected timestamp
            // loses no reading and is how a stale row gets repaired; blocking
            // it is what makes a book unfixable.
            val mine = Math.round(percent * 10_000f) / 10_000f
            val theirs = Math.round(remote?.percentage?.times(10_000f) ?: 0f) / 10_000f
            if (remote != null && mine < theirs) {
                skippedNotAhead++
                continue
            }

            if (kosync.putProgressFor(
                    stableKey = local.progressKey,
                    contentHash = hash,
                    position = location,
                    percentage = percent,
                    setAt = savedAt,
                    deviceName = deviceName,
                    deviceId = deviceId,
                )
            ) {
                written++
            }
        }

        // ---------------------------------------------------------------- pull
        //
        // The other half. Everything above publishes what the READER knows; this
        // sends back what the SERVER knows and the reader does not.
        //
        // Without it a position written to CWA by anything else would show on
        // the library row and never reach the reader.
        var pushed = 0
        if (toDevice.isNotEmpty()) {
            _state.value = _state.value.copy(
                transfer = TransferProgress("Syncing with reader…", 0, toDevice.size.toLong())
            )
            val batch = JSONArray().apply { toDevice.forEach { put(it) } }.toString()
            val ok = runCatching {
                ble.uploadBytes(batch.toByteArray(Charsets.UTF_8), kind = "progress")
            }.isSuccess
            if (ok) pushed = toDevice.size
            invalidateReaderListing()
        }
        lastPositionSyncAt = System.currentTimeMillis()

        _state.value = _state.value.copy(
            syncingProgress = false,
            transfer = null,
            // Quiet inside a sync: one sync, one bar, no trailing report.
            message = if (syncJob?.isActive == true) _state.value.message else buildString {
                append("Synced $written position")
                if (written != 1) append("s")
                if (skippedOlder > 0) append(", $skippedOlder already current")
                if (skippedNoClock > 0) append(", $skippedNoClock unstamped")
                if (missingFile > 0) append(", $missingFile not on this phone")
                if (sideLoaded > 0) append(", $sideLoaded side-loaded")
                if (skippedNotAhead > 0) append(", $skippedNotAhead not ahead")
                if (pushed > 0) append("; sent $pushed to the reader")
            }
        )
        // Positions moved, so the percentages on the shelf are stale.
        loadLibrary()
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
                _state.value = _state.value.copy(storeActivity = null, transfer = null)

            is StoreEvent.Serving ->
                _state.value = _state.value.copy(
                    storeActivity = "Reader asked for ${event.detail}"
                )

            is StoreEvent.Progress ->
                _state.value = _state.value.copy(
                    transfer = TransferProgress(event.label, event.sent, event.total)
                )

            is StoreEvent.Answered ->
                _state.value = _state.value.copy(
                    storeActivity = null,
                    transfer = null,
                    message = "Sent ${event.detail} to the reader's Store",
                )

            is StoreEvent.Declined ->
                _state.value = _state.value.copy(
                    storeActivity = null,
                    transfer = null,
                    message = "Told the reader we could not answer: ${event.reason}",
                )
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
            OpdsClient(http, c.opdsUrl, c.username, c.password).feed(FEED_PATH)
        }

        result.onSuccess { feedBooks ->
            val kosync = KosyncClient(http, c.kosyncUrl, c.username, c.password)
            val cachedNames = books.cachedNames()
            val sent = sentBooks.load(deviceKey())
            val canSync = c.username.isNotBlank()
            val rows = feedBooks.map { book ->
                val cached = cachedNames.contains(book.filename)
                // CONTENT hashing needs the bytes, so only a downloaded book
                // has an id the server can match. See [KoreaderHash].
                val hash = if (cached) {
                    runCatching { KoreaderHash.fromContent(books.fileFor(book)) }
                        .getOrNull()
                } else null
                BookRow(
                    book = book,
                    progress = (
                        if (canSync && hash != null) {
                            kosync.progressFor(book.progressKey, hash, kosyncDeviceName(), kosyncDeviceId())
                        } else null
                        ) ?: knownProgressFor(book.filename),
                    sentFromThisApp = book.filename in sent,
                    cached = cached,
                )
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
                // A pass that is awaiting this refresh drains the queue right after
                // it returns. Otherwise ask -- including when a sync is running but
                // did not start this refresh (the stale-catalogue refresh a connect
                // launches alongside it): requestSync() then earns one more lap.
                if (!catalogueJoining) requestSync()
            }

            // The shelf does not come from the feed, but a catalogue refresh is
            // a good moment to pick up reading progress the reader has synced.
            loadLibrary()
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
                    rows = list.map { book ->
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
        val rows = saved.map { book ->
            // CONTENT hashing needs the bytes, which by definition we have.
            val hash = if (canSync) {
                withContext(Dispatchers.IO) {
                    runCatching { KoreaderHash.fromContent(books.fileFor(book)) }
                        .getOrNull()
                }
            } else null
            BookRow(
                book = book,
                progress = if (canSync && hash != null) {
                    kosync.progressFor(book.progressKey, hash, kosyncDeviceName(), kosyncDeviceId())
                } else known[book.filename],
                sentFromThisApp = book.filename in sent,
                cached = true,
                pendingRemoval = book.filename in owed,
            )
        }.sortedWith(
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
        fun stamp(row: BookRow) = row.copy(
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
        val pending = libraryRows().filter { !it.sentFromThisApp }
        var sentAny = false
        for (row in pending) {
            if (!_state.value.connected || !_state.value.authorized) break
            sendToDevice(row).join()
            sentAny = true
        }
        // No early return on an empty shelf: an empty shelf is the strongest
        // statement of what should be on the reader, and the prune carries it out.
        val removed = pruneDeviceBooks()
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
        val job = viewModelScope.launch {
            _state.value = _state.value.copy(syncingLibrary = true, syncStatus = "Syncing with reader\u2026")
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
            } finally {
                _state.value = _state.value.copy(syncingLibrary = false, syncStatus = null)
            }
        }
        syncJob = job
        return job
    }

    /**
     * Everything, in the order each step depends on the last: the catalogue
     * (so Calibre changes are known), the Calibre changes themselves (so the
     * files on this phone are current), the clock (before any timestamp is
     * written), settings, the books, then positions (meaningless for a book that
     * has not arrived). One library listing is shared by the book and position
     * steps -- see [readerLibrary].
     */
    private suspend fun runSyncPass(catalogue: Boolean, settings: Boolean, positions: Boolean) {
        if (catalogue) {
            catalogueJoining = true
            try {
                refresh().join()
            } finally {
                catalogueJoining = false
            }
        }
        val linked = _state.value.connected && _state.value.authorized
        if (linked) resendShelfOnce()

        val changes = calibreQueue.toList()
        calibreQueue.clear()
        if (changes.isNotEmpty()) updateFromCalibre(changes)

        if (!_state.value.connected || !_state.value.authorized) return
        if (settings) runCatching { loadDeviceSettings(force = true).join() }

        val sentAny = mirrorPass()

        // Positions when the pass was a full one or something was just sent, not
        // on every small trigger: a page-turn-driven pass has nothing to add.
        val recent = System.currentTimeMillis() - lastPositionSyncAt < LISTING_TTL_MS
        if (sentAny || ((catalogue || positions) && !recent)) {
            if (!_state.value.syncingProgress) syncProgressToKosync().join()
        }
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
        if (resume) startFresh.forget(deviceKey(), prompt.filename)
        else startFresh.add(deviceKey(), prompt.filename)
        resumeAnswer?.complete(resume)
        resumeAnswer = null
    }

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
     * the same rules [syncProgressToKosync] applies -- and false only when a
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
        // FORWARD ONLY, at storage precision -- see syncProgressToKosync. Equal
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
    private suspend fun updateFromCalibre(changed: List<Book>) {
        val c = _state.value.config
        if (!c.serverConfigured) return
        val kosync = KosyncClient(http, c.kosyncUrl, c.username, c.password)
        val canSync = c.username.isNotBlank()
        var updated = 0
        var unchanged = 0
        for (feed in changed) {
            if (feed.id in _state.value.busyBookIds) continue
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
                _state.value = _state.value.copy(
                    transfer = TransferProgress("Syncing with reader\u2026", 0, 0),
                )
                var sameBytes = false
                val ok = runCatching {
                    OpdsClient(http, c.opdsUrl, c.username, c.password).download(feed, temp) { sent, total ->
                        _state.value = _state.value.copy(
                            transfer = TransferProgress(
                                "Syncing with reader\u2026",
                                sent,
                                if (total > 0) total else sent,
                            ),
                        )
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
        _state.value = _state.value.copy(transfer = null)
        if (updated > 0) {
            _state.value = _state.value.copy(
                message = if (updated == 1) "Updated \"${changed.first().title}\" from Calibre"
                else "Updated $updated books from Calibre",
            )
            // No mirror of its own: this runs inside a sync pass, and the pass
            // sends the replaced books straight after.
            loadLibrary(withProgress = false).join()
        }
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
     * The reader's library listing, fetched once per sync instead of once per
     * step.
     *
     * The prune needs it to see what to delete and the position sync needs it
     * for positions; downloading it per step would mean several BLE downloads
     * of the same few KB in under a minute. Reused for [LISTING_TTL_MS], and
     * dropped the moment
     * anything changes what it would say: a book sent or deleted, positions
     * pushed.
     */
    private var readerListing: ByteArray? = null
    private var readerListingAt = 0L
    private var lastPositionSyncAt = 0L
    private var bookOpenDeferredAt = 0L
    private var lastBookOpenRetryAt = 0L

    private fun invalidateReaderListing() {
        readerListing = null
    }

    private suspend fun readerLibrary(): ByteArray? {
        val cached = readerListing
        if (cached != null && System.currentTimeMillis() - readerListingAt < LISTING_TTL_MS) return cached
        val fresh = runCatching { ble.download("library") }.getOrNull() ?: return null
        readerListing = fresh
        readerListingAt = System.currentTimeMillis()
        return fresh
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
     * Removes from the reader anything that is no longer on the offline shelf.
     *
     * The shelf is the contract in both directions: what is saved is on the
     * reader, and what is not saved is not. Progress is not lost by deleting --
     * it lives in kosync, so re-saving a book brings the position back with it.
     *
     * Driven by what the READER reports, not by what this app remembers. That
     * matters: "not in my library" is only a safe reason to delete if we also
     * know what is actually there. Diffing the reader's own listing means a
     * book side-loaded over USB is seen and considered, and -- more importantly
     * -- a failure to read that listing prunes nothing at all rather than
     * guessing.
     */
    private suspend fun pruneDeviceBooks(): Int = pruneLock.withLock { pruneDeviceBooksLocked() }

    private suspend fun pruneDeviceBooksLocked(): Int {
        val listing = readerLibrary() ?: return 0
        // A BARE ARRAY of book objects -- BookLibraryIndex writes "[", the
        // entries, then "]". Not an object with a "books" key: parsing it that
        // way throws, runCatching turns the throw into an empty list, and an
        // empty list reads as "nothing to prune".
        val onDevice = runCatching {
            val arr = JSONArray(String(listing, Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("filename")?.ifBlank { null } }
        }.getOrElse { emptyList() }
        if (onDevice.isEmpty()) return 0

        // A book awaiting removal is still ON DISK -- that is what keeps its row
        // drawable -- so cachedNames() alone would report it as shelved and the
        // prune would never touch it. The shelf contract is "saved MINUS owed
        // removals".
        val owed = pendingRemovals.load(deviceKey())
        val shelf = books.cachedNames() - owed
        val extra = onDevice.filter { it !in shelf }
        var removed = 0
        for (filename in extra) {
            if (!_state.value.connected || !_state.value.authorized) break
            if (runCatching { ble.deleteBook(filename) }.getOrDefault(false).also { invalidateReaderListing() }) {
                removed++
                sentBooks.forget(deviceKey(), filename)
                // Confirmed gone from the reader, so the local copy that was
                // being kept purely to draw the pending row can go too. Only
                // now does the row actually leave the Library.
                if (filename in owed) {
                    books.cachedBooks().firstOrNull { it.filename == filename }
                        ?.let { withContext(Dispatchers.IO) { books.delete(it) } }
                    pendingRemovals.forget(deviceKey(), filename)
                }
            }
        }
        // A book the reader is no longer holding at all is settled as well:
        // nothing to delete there, so stop owing it.
        var settled = 0
        for (filename in owed) {
            if (filename !in onDevice) {
                books.cachedBooks().firstOrNull { it.filename == filename }
                    ?.let { withContext(Dispatchers.IO) { books.delete(it) } }
                pendingRemovals.forget(deviceKey(), filename)
                sentBooks.forget(deviceKey(), filename)
                settled++
            }
        }
        // Only when something actually moved, not on every sweep of a shelf
        // that has an outstanding removal.
        if (removed > 0 || settled > 0) loadLibrary(withProgress = false)
        return removed
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
    private suspend fun sendBookMetadata(book: Book) {
        val kinds = _state.value.device?.uploadKinds.orEmpty()
        if (kinds.isNotEmpty() && "book_meta" !in kinds) return
        val c = _state.value.config
        val thumb = runCatching {
            covers.deviceThumbnail(
                book.coverUrl, c.username, c.password,
                // The row geometry, not the detail one: the reader blits this
                // as-is, and anything it has to scale becomes dither noise.
                DeviceThumb.ROW_WIDTH, DeviceThumb.ROW_HEIGHT,
            )
        }.getOrNull()

        val item = CatalogContainer.Item(
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
        runCatching { ble.uploadBytes(data = blob, kind = "book_meta") }
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
        markBusy(row.book.id, true)
        _state.value = _state.value.copy(message = null)
        // The transfer bar covers the DOWNLOAD too, not just the BLE push. The
        // download is the longer half on a big book, and showing nothing for it
        // makes saving look stalled until the reader transfer begins.
        _state.value = _state.value.copy(
            transfer = TransferProgress("Downloading \"${row.book.title}\"", 0, 0)
        )
        val r = runCatching {
            OpdsClient(http, c.opdsUrl, c.username, c.password)
                .download(row.book, books.fileFor(row.book)) { sent, total ->
                    _state.value = _state.value.copy(
                        transfer = TransferProgress(
                            "Downloading \"${row.book.title}\"",
                            sent,
                            // A server with no Content-Length gives -1; report
                            // the bytes so far as the total so the bar stays
                            // honest rather than pretending to know the end.
                            if (total > 0) total else sent,
                        )
                    )
                }
            // Only after the bytes landed: the sidecar must never describe a
            // book that is not actually on the shelf.
            books.remember(row.book)
            "Saved \"${row.book.title}\" for offline reading"
        }
        markBusy(row.book.id, false)
        _state.value = _state.value.copy(
            transfer = null,
            message = r.getOrElse { it.message ?: "Download failed" },
        )
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
                answer.await()
            } else {
                // No question asked means no standing instruction to restart.
                startFresh.forget(deviceKey(), row.book.filename)
            }

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
            // Progress afterwards, off the critical path: it is decoration on a
            // list the user can already see and act on.
            loadLibrary()
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
                .getOrDefault(false).also { invalidateReaderListing() }
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

        val outcome = runCatching {
            // The local copy doubles as the offline library and as the source
            // for handing the book to KOReader, so it is kept, not deleted.
            val target = books.fileFor(row.book)
            if (!books.isCached(row.book)) {
                _state.value = _state.value.copy(
                    transfer = TransferProgress("Downloading \"${row.book.title}\"", 0, 0)
                )
                OpdsClient(http, c.opdsUrl, c.username, c.password).download(row.book, target)
                // Same rule as cacheBook: describe it only once it is really
                // on the shelf, since this path fills the shelf too.
                books.remember(row.book)
            }
            _state.value = _state.value.copy(
                transfer = TransferProgress("Transferring: ${row.book.title}", 0, target.length())
            )
            var lastShown = 0L
            val replacing = row.book.filename in replaceOnSend.load(deviceKey())
            ble.upload(target, row.book.filename, replace = replacing) { sent, total ->
                if (sent - lastShown >= PROGRESS_STEP_BYTES || sent == total) {
                    lastShown = sent
                    _state.value = _state.value.copy(
                        transfer = TransferProgress("Transferring: ${row.book.title}", sent, total)
                    )
                }
            }
        }

        val deviceId = deviceKey()
        val message = outcome.fold(
            onSuccess = { r ->
                sentBooks.add(deviceId, row.book.filename)
                replaceOnSend.forget(deviceId, row.book.filename)
                invalidateReaderListing()
                bookOpenDeferredAt = 0L
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
                    val first = bookOpenDeferredAt == 0L
                    bookOpenDeferredAt = System.currentTimeMillis()
                    if (first) "Close \"${row.book.title}\" on the reader so its update can be sent" else null
                } else if (code == "exists" && wantedReplace) {
                    // Asked to replace and still refused: firmware older than the
                    // replace flag. Keep it owed and SAY so -- marking it sent here
                    // is exactly the silent-stale-copy bug this path exists to fix.
                    "Update the reader's firmware to replace \"${row.book.title}\""
                } else if (code == "exists") {
                    sentBooks.add(deviceId, row.book.filename)
                    // Already there counts as arrived, so stop showing it as
                    // pending or the row stays grey forever.
                    markPendingTransfer(row.book.id, false)
                    "\"${row.book.title}\" is already on the reader"
                } else {
                    "Send failed: ${e.message}"
                }
            },
        )

        markBusy(row.book.id, false)
        _state.value = _state.value.copy(transfer = null, message = message)
        // Deliberately neither refresh() nor loadLibrary(): the only thing that
        // changed is the "sent" flag (and, on the download-first path, the
        // shelf), and both of the heavier calls would run once per book through
        // a mirror -- reloading the catalogue over the user's search, and
        // re-reading the whole shelf's progress from kosync each time.
        restampRows()
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
        _state.value = _state.value.copy(transfer = TransferProgress("Crash report", 0, 0))
        val outcome = runCatching { ble.download("crash_report") }
        _state.value = _state.value.copy(
            transfer = null,
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
        _state.value = _state.value.copy(transfer = TransferProgress(displayName, 0, staged.length()))
        var lastShown = 0L
        var firstByteAt = 0L
        val outcome = runCatching {
            ble.upload(staged, name = "firmware.bin", kind = "firmware", version = version, signature = signature) { sent, total ->
                if (firstByteAt == 0L) firstByteAt = System.currentTimeMillis()
                if (sent - lastShown >= PROGRESS_STEP_BYTES || sent == total) {
                    lastShown = sent
                    val elapsed = System.currentTimeMillis() - firstByteAt
                    val kbps = if (elapsed > 0) (sent * 1000 / elapsed / 1024).toInt() else 0
                    _state.value = _state.value.copy(
                        transfer = TransferProgress(displayName, sent, total),
                        firmwareProgress = _state.value.firmwareProgress?.let { p ->
                            if (total > 0) p.copy(percent = (sent * 100 / total).toInt(), kbps = kbps) else p
                        },
                    )
                }
            }
        }
        staged.delete()

        _state.value = _state.value.copy(
            transfer = null,
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
    fun checkFirmware(readReader: Boolean = true) = viewModelScope.launch {
        val base = _state.value.config.effectiveUpdatesUrl.trimEnd('/')
        _state.value = _state.value.copy(firmwareChecking = true)
        // No update page set: nothing to fetch. The reader half still runs.
        val manifest = if (base.isBlank()) null
        else async(Dispatchers.IO) { runCatching { fetchManifest(base) } }
        val linked = _state.value.connected && _state.value.authorized
        val about = if (linked && readReader) {
            runCatching { org.json.JSONObject(String(ble.download("about"), Charsets.UTF_8)) }
        } else null
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
        checkFirmware()
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
        _state.value = _state.value.copy(
            transfer = TransferProgress("Downloading $label", 0, m.size),
            firmwareProgress = FirmwareProgress(m.version, FirmwarePhase.DOWNLOADING),
        )
        val fetched = runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    // Never more than the manifest says, and never over 8 MB.
                    val cap = minOf(m.size, HttpGuard.FIRMWARE_MAX)
                    HttpGuard.checkDeclared(r, cap, "Firmware image")
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var shown = 0L
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
                                if (total - shown >= PROGRESS_STEP_BYTES) {
                                    shown = total
                                    withContext(Dispatchers.Main) {
                                        _state.value = _state.value.copy(
                                            transfer = TransferProgress("Downloading $label", total, m.size),
                                            firmwareProgress = FirmwareProgress(
                                                m.version, FirmwarePhase.DOWNLOADING, (total * 100 / m.size).toInt(),
                                            ),
                                        )
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
            _state.value = _state.value.copy(
                transfer = null,
                firmwareProgress = null,
                message = "Could not download $label: ${fetched.exceptionOrNull()?.message}",
            )
            return@launch
        }
        // The phone-side download runs at once; the radio waits for the connect sync
        // so the book listing is not queued behind 4.6 MB of firmware.
        _state.value = _state.value.copy(
            transfer = null,
            firmwareProgress = FirmwareProgress(m.version, FirmwarePhase.SENDING),
        )
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

    /** Publishes the reader's name to the pill, and remembers it across launches. */
    private fun rememberDeviceName(name: String?) {
        val clean = name?.trim().orEmpty().take(16)
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
            val bytes = ble.download("settings")
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
        _state.value = _state.value.copy(
            transfer = TransferProgress("Reader settings", 0, bytes.size.toLong())
        )
        val outcome = runCatching {
            ble.uploadBytes(bytes, kind = "settings") { sent, total ->
                _state.value = _state.value.copy(
                    transfer = TransferProgress("Reader settings", sent, total)
                )
            }
        }
        _state.value = _state.value.copy(transfer = null)

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
    /** True while a sync pass is awaiting refresh() -- see refresh()'s requestSync. */
    private var catalogueJoining = false

    /**
     * Keeps the phone current with Calibre WITHOUT holding up the reader.
     * Launched beside the connect sync, and only when the catalogue is over
     * [CATALOGUE_STALE_MS] old; anything it finds queues its own pass through
     * calibreQueue, exactly as a manual refresh does.
     */
    private fun refreshCatalogueIfStale() {
        if (!_state.value.config.serverConfigured) return
        if (System.currentTimeMillis() - lastCatalogueAt >= CATALOGUE_STALE_MS) refresh()
    }

    private fun reauthenticate() {
        if (reauthInFlight) return
        reauthInFlight = true
        viewModelScope.launch {
            try {
                val identity = pairingStore.load() ?: return@launch
                val ok = runCatching { ble.authenticate(identity) }.getOrDefault(false)
                if (ok) onAuthorized(silent = true)
            } finally {
                reauthInFlight = false
                _state.value = _state.value.copy(authTrace = ble.lastAuthTrace)
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
            if (pairingStore.load() == null) return@launch
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
            _state.value.transfer != null ||
            // The reader reboots mid-install; the watch must outlive the dropped link.
            _state.value.firmwareProgress?.phase == FirmwarePhase.INSTALLING

    /**
     * Only reached if X4SyncApp's process-wide store is ever cleared: this is no
     * longer scoped to the Activity, so closing the app does not come here.
     */
    override fun onCleared() {
        super.onCleared()
        // Not launched on viewModelScope: that scope is already cancelled here.
        ble.disconnect()
    }
}
