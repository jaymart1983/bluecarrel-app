package dev.bluecarrel.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
// `path { }` is a top-level extension on ImageVector.Builder, not a member of
// it, so it does not come along with the ImageVector import.
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bluecarrel.app.AppTab
import dev.bluecarrel.app.FirmwarePhase
import dev.bluecarrel.app.FirmwareProgress
import dev.bluecarrel.app.MainViewModel
import dev.bluecarrel.app.ReaderScan
import dev.bluecarrel.app.TransferProgress
import dev.bluecarrel.app.transferRate
import dev.bluecarrel.app.UiState
import dev.bluecarrel.app.data.BlePermissions
import dev.bluecarrel.app.data.DiscoveredReader
import dev.bluecarrel.app.data.BookRow
import dev.bluecarrel.app.data.ResumePrompt
import dev.bluecarrel.app.data.SyncSummary
import dev.bluecarrel.app.data.Config
import dev.bluecarrel.app.data.CoverCache
import dev.bluecarrel.app.data.LinkStage
import dev.bluecarrel.app.data.PairingState
import dev.bluecarrel.app.data.DEFAULT_UPDATES_PAGE
import dev.bluecarrel.app.data.DEFAULT_UPDATES_URL
import dev.bluecarrel.app.data.HTTPS_REQUIRED
import dev.bluecarrel.app.data.HttpGuard
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Which app-settings page is open, if any. Null = closed.
    var settingsSection by remember { mutableStateOf<SettingsSection?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showDeviceSettings by remember { mutableStateOf(false) }
    var showPairing by remember { mutableStateOf(false) }
    /** The book whose detail sheet is open, if any. Held by filename because
     *  the row object is replaced whenever the shelf or the catalogue reloads. */
    var detailOf by remember { mutableStateOf<String?>(null) }

    // Pull to refresh: the spinner stays while anything the pull started is running,
    // and a moment longer, so a refresh with nothing to do is still seen to happen.
    var pulled by remember { mutableStateOf(false) }
    val refreshWork = state.loading || state.syncingLibrary || state.syncingProgress ||
        state.firmwareChecking || state.link.busy
    LaunchedEffect(pulled, refreshWork) {
        if (pulled && !refreshWork) {
            delay(800)
            pulled = false
        }
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    // Raised when the ViewModel says pairing is the next step (NEEDS_PAIRING),
    // and dismissed once the session is authorised.
    LaunchedEffect(state.authorized, state.link.stage) {
        when {
            state.authorized -> showPairing = false
            state.link.stage == LinkStage.NEEDS_PAIRING -> showPairing = true
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Bluecarrel") },
                actions = {
                    // No reader, no pill: an unpaired app pairs from the overflow menu.
                    // Not while the picker is only scanning; once a reader has been
                    // chosen it stays up for the pairing, so the progress is visible.
                    if (state.hasStoredPairing || state.pairingReaderName != null) {
                        ReaderStatusAction(
                            state = state,
                            onConnect = { vm.connectReader() },
                            onPair = { showPairing = true },
                            onDisconnect = { vm.disconnectReader() },
                            onDeviceSettings = { showDeviceSettings = true },
                            onFirmware = { settingsSection = SettingsSection.FIRMWARE },
                        )
                    }
                    // One refresh, not two: the catalogue AND the reader, so the
                    // obvious "bring things up to date" control does the whole job.
                    IconButton(
                        onClick = { vm.pullToRefresh() },
                        enabled = !state.loading && !state.syncingLibrary && !state.syncingProgress,
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    // App settings live here, not in the pill. The pill is about
                    // the READER -- connect, pair, its own settings -- and
                    // folding the app's own configuration in beside it made one
                    // control answer for two different machines.
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            if (!state.hasStoredPairing) {
                                DropdownMenuItem(
                                    text = { Text("Pair reader") },
                                    enabled = !state.link.busy && state.pairingReaderName == null,
                                    leadingIcon = { Icon(BluetoothVector, null) },
                                    onClick = { overflowOpen = false; showPairing = true },
                                )
                                HorizontalDivider()
                            }
                            // Firmware belongs to the READER, so it lives in the pill
                            // with the reader's other controls, not with the app's.
                            for (sec in SettingsSection.entries.filter { it != SettingsSection.FIRMWARE }) {
                                DropdownMenuItem(
                                    text = { Text(sec.label) },
                                    onClick = { overflowOpen = false; settingsSection = sec },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // The reader's Store asking this app for a page. Shown separately
            // from the transfer bar because the interesting part is that the
            // *device* started it, not that bytes are moving.
            // ONE bar at a time, by specificity: moving bytes beats "the reader
            // asked for a page", which beats "catching the reader up". Stacking
            // them would show a block twice the height for one job. The mirror
            // line matters because it is the FIRST thing a connection does;
            // without it a reader catching up looks identical to one sitting idle.
            if (state.transfer != null) {
                TransferBar(state.transfer!!)
            } else if (state.storeActivity != null) {
                StoreBar(state.storeActivity!!)
            } else if (state.syncingLibrary) {
                StoreBar(state.syncStatus ?: "Syncing books with the reader…")
            } else if (state.lastSync != null && state.hasStoredPairing) {
                // Where "Syncing" just was, and it stays: a sync is over too fast
                // to read while it runs.
                SyncSummaryBar(state.lastSync!!)
            }
            // Not under a transfer bar either: it has its own, and a second one
            // coming and going below it moved the whole list.
            if (state.loading && !state.syncingLibrary && state.transfer == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // Two surfaces. The Library is the shelf the reader mirrors; the Store
            // is the whole Calibre library, which is searched rather than listed
            // -- a large library is not something to scroll on a phone, and is
            // certainly not something to copy onto a reader.
            //
            // The Library reads `state.library`, which the ViewModel builds from
            // the books directory -- not `state.rows`, which is the Store's
            // current contents and changes with every search.
            val shown = if (state.tab == AppTab.LIBRARY) state.library else state.rows

            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = if (state.tab == AppTab.LIBRARY) 0 else 1) {
                    Tab(
                        selected = state.tab == AppTab.LIBRARY,
                        onClick = { vm.setTab(AppTab.LIBRARY) },
                        text = { Text("Library" + if (shown.isNotEmpty() && state.tab == AppTab.LIBRARY) " (${shown.size})" else "") },
                    )
                    Tab(
                        selected = state.tab == AppTab.STORE,
                        onClick = { vm.setTab(AppTab.STORE) },
                        text = { Text("Store") },
                    )
                }

                if (state.tab == AppTab.STORE) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { vm.setQuery(it) },
                        label = { Text("Search the library") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { vm.runSearch() }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    if (state.searching) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

            PullToRefreshBox(
                isRefreshing = pulled,
                onRefresh = { pulled = true; vm.pullToRefresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
            if (shown.isEmpty() && !state.loading && !state.searching) {
                // Scrollable, or a pull on an empty list has nothing to drag.
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (state.tab == AppTab.LIBRARY) {
                        EmptyLibraryHint(onBrowse = { vm.setTab(AppTab.STORE) })
                    } else {
                        EmptyHint()
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.book.id }) { row ->
                        BookItem(
                            row = row,
                            readingNow = state.readerOpenBook != null && state.readerOpenBook == row.book.filename,
                            // Only in the Store. In the Library every row is on
                            // the shelf, so tinting them all would say nothing.
                            onShelf = state.tab == AppTab.STORE && row.cached,
                            onReader = state.tab == AppTab.STORE && !row.cached &&
                                row.book.filename in state.readerBookNames,
                            busy = row.book.id in state.busyBookIds,
                            removing = row.book.id in state.removingBookIds,
                            awaitingReader = row.book.id in state.pendingTransferIds,
                            covers = vm.covers,
                            creds = state.config.username to state.config.password,
                            onOpenDetail = { detailOf = row.book.filename },
                            onCache = { vm.cacheBook(row) },
                            onRemove = { vm.removeOffline(row) },
                        )
                        HorizontalDivider()
                    }
                }
            }
            }
            }
        }
    }

    settingsSection?.let { sec ->
        SettingsSheet(
            section = sec,
            config = state.config,
            state = state,
            onDismiss = { settingsSection = null },
            onSave = { vm.saveConfig(it); settingsSection = null },
            onForgetPairing = { vm.forgetPairing() },
            onPair = { settingsSection = null; showPairing = true },
            onCrashReport = { vm.fetchCrashReport(); settingsSection = null },
            onCheckFirmware = { vm.checkFirmware() },
            onInstallFirmware = { vm.installLatestFirmware(); settingsSection = null },
        )
    }

    // Drawn over the list rather than pushed onto a nav graph: this app has one
    // screen and one sheet, and a NavHost for a single second destination would
    // be more machinery than the thing it carries.
    if (showDeviceSettings) {
        LaunchedEffect(Unit) { vm.loadDeviceSettings() }
        Surface(Modifier.fillMaxSize()) {
            DeviceSettingsScreen(
                ui = state.deviceSettings,
                onBack = { showDeviceSettings = false; vm.closeDeviceSettings() },
                onEdit = { key, value -> vm.editDeviceSetting(key, value) },
                onEditText = { key, value -> vm.editDeviceSettingText(key, value) },
                onReload = { vm.loadDeviceSettings(force = true) },
                onSave = { vm.saveDeviceSettings() },
                onDiscard = { vm.discardDeviceSettingEdits() },
                onDismissNotice = { vm.dismissDeviceSettingsNotice() },
                autoDownloadFirmware = state.config.autoDownloadFirmware,
                onAutoDownloadFirmware = { vm.setAutoDownloadFirmware(it) },
                matchPhoneDarkMode = state.config.matchPhoneDarkMode,
                onMatchPhoneDarkMode = { vm.setMatchPhoneDarkMode(it) },
                readerFirmware = state.readerFirmware,
            )
        }
    }

    // Resolved from live state rather than captured at tap time, so the sheet's
    // offline toggle and KOReader button follow a save or a removal that
    // finishes while it is open. The shelf wins over the Store: it is the copy
    // that knows the book is really here.
    val detailRow = detailOf?.let { name ->
        state.library.firstOrNull { it.book.filename == name }
            ?: state.rows.firstOrNull { it.book.filename == name }
    }
    state.removeOpenPrompt?.let { openRow ->
        AlertDialog(
            onDismissRequest = { vm.dismissRemoveOpenPrompt() },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Open on the reader") },
            text = {
                Text(
                    "\"${openRow.book.title}\" is open on the reader. Close it and remove it from " +
                        "the reader and this phone?",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.removeOffline(openRow, confirmedOpen = true) }) { Text("Close and remove") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissRemoveOpenPrompt() }) { Text("Keep") }
            },
        )
    }

    if (detailRow != null) {
        BookDetailSheet(
            row = detailRow,
            busy = detailRow.book.id in state.busyBookIds,
            covers = vm.covers,
            creds = state.config.username to state.config.password,
            // A lambda, not a value: this is a PackageManager query and the
            // scaffold recomposes on every byte of transfer progress.
            canOpenInReader = { vm.canOpenInReader(detailRow.book) },
            onDismiss = { detailOf = null },
            onCache = { vm.cacheBook(detailRow) },
            onRemove = { vm.removeOffline(detailRow) },
            onOpen = { vm.openInReader(detailRow) },
        )
    } else if (detailOf != null) {
        // The book left both lists while the sheet was open (removed offline
        // during a search of something else). Close rather than show nothing.
        LaunchedEffect(detailOf) { detailOf = null }
    }

    if (showPairing) {
        // The scan lives exactly as long as the picker: started when it opens,
        // stopped however it closes.
        DisposableEffect(Unit) {
            vm.startReaderScan()
            onDispose { vm.stopReaderScan() }
        }
        ReaderPickerDialog(
            reason = state.link.reason.takeIf { state.link.stage == LinkStage.NEEDS_PAIRING },
            scan = state.readerScan,
            onDismiss = { showPairing = false },
            onRescan = { vm.startReaderScan() },
            onPick = { reader ->
                showPairing = false
                vm.pairReader(reader.address, reader.name)
            },
        )
    }

    state.resumePrompt?.let { p ->
        ResumeDialog(
            prompt = p,
            onResume = { vm.answerResume(true) },
            onRestart = { vm.answerResume(false) },
        )
    }
}

/**
 * "You have read this before -- carry on, or start again?"
 *
 * Raised after the bytes land but BEFORE the book is handed to the reader,
 * because the mirror sends the book and its position together and there is no
 * good default: resuming a book the user meant to re-read is as wrong as
 * restarting one they were halfway through.
 *
 * Deliberately not dismissible. The save is suspended waiting on the answer, so
 * a tap outside would leave the book on the phone and never sent.
 */
@Composable
private fun ResumeDialog(
    prompt: ResumePrompt,
    onResume: () -> Unit,
    onRestart: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Carry on reading?") },
        text = {
            Text(
                "\"${prompt.title}\" is saved at ${prompt.percentLabel} on the server. " +
                    "Open it on the reader where you left off, or start it again from the beginning?"
            )
        },
        confirmButton = {
            TextButton(onClick = onResume) { Text("Resume ${prompt.percentLabel}") }
        },
        dismissButton = {
            TextButton(onClick = onRestart) { Text("Start again") }
        },
    )
}

/**
 * The reader link, as one icon in the app bar with everything it offers behind
 * a tap. A full-width banner would cost a row of screen to say "connected",
 * which is true almost all the time.
 *
 * Every stage gets its own headline, its own line of what-to-do and its own
 * action — turn Bluetooth on, grant a permission, pair, wait. While unpaired
 * the menu offers Pair; `NEEDS_PAIRING` also raises the dialog from AppScaffold.
 *
 * Green means connected AND authorised. The transport being up is not the same
 * thing as being able to use the reader — a link through the hello gate is the
 * only one that can move a book — and a "connected" indicator over a session
 * that can do nothing is exactly the confusion to avoid.
 */
@Composable
private fun ReaderStatusAction(
    state: UiState,
    onConnect: () -> Unit,
    onPair: () -> Unit,
    onDisconnect: () -> Unit,
    onDeviceSettings: () -> Unit,
    onFirmware: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onConnect() }

    var menuOpen by remember { mutableStateOf(false) }

    val stage = state.link.stage
    val busy = state.link.busy
    val live = state.connected && state.authorized

    // Something on the reader's own screen is waiting for the user, or the code
    // is. Neither survives being merely grey in a corner, so the icon carries a
    // dot -- the one thing the banner did that a plain icon cannot.
    val wantsAttention = state.awaitingSaveHostPrompt || stage == LinkStage.NEEDS_PAIRING
    // A newer build on the update page. Its own mark rather than the attention
    // dot: the dot means "the reader needs you now", this means "when convenient".
    val updateAvailable = state.firmwareUpdateAvailable
    // An update in flight: the arrows turn until the reader reports the new build.
    val fwProgress = state.firmwareProgress
    // Spinning while bytes are moving (download, send); pulsing while the image is
    // on the reader and the app is only waiting for it to be installed.
    val fwActive = fwProgress != null &&
        (fwProgress.phase == FirmwarePhase.DOWNLOADING || fwProgress.phase == FirmwarePhase.SENDING)
    val fwWaiting = fwProgress != null && !fwActive

    val tint = when {
        live -> READER_CONNECTED
        wantsAttention -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val headline = when {
        state.awaitingSaveHostPrompt -> "Confirm on the reader"
        stage == LinkStage.CONNECTED ->
            (state.device?.firmwareName ?: "Reader") + " connected"
        stage == LinkStage.SCANNING -> "Looking for the reader…"
        stage == LinkStage.CONNECTING -> "Connecting…"
        stage == LinkStage.BONDING -> "Pairing…"
        stage == LinkStage.PAIRING -> "Authorising…"
        stage == LinkStage.NEEDS_PAIRING -> state.link.reason ?: "Not paired"
        stage == LinkStage.BLUETOOTH_OFF -> "Bluetooth is off"
        stage == LinkStage.NEEDS_PERMISSION -> "Bluetooth permission needed"
        stage == LinkStage.FAILED -> state.link.reason ?: "Could not reach the reader"
        else -> "Reader not connected"
    }

    // One short line, or nothing. The headline already says what state the link is in.
    val detail = when {
        state.awaitingSaveHostPrompt -> "Confirm on the reader"
        stage == LinkStage.CONNECTED -> ""
        stage == LinkStage.BONDING -> state.link.hint ?: ""
        stage == LinkStage.SCANNING || stage == LinkStage.CONNECTING || stage == LinkStage.PAIRING -> ""
        stage == LinkStage.IDLE -> "Wake the reader to connect"
        else -> state.link.hint ?: "Wake the reader to connect"
    }

    // A pill, not a bare icon. The icon alone said "there is a radio"; the pill
    // says WHICH reader, which is the thing worth knowing when the phone can
    // remember more than one, and it gives the menu a target big enough to hit
    // without aiming.
    // While pairing, the name the chosen reader advertised; afterwards, what it calls itself.
    val pillName = state.pairingReaderName ?: state.deviceName.ifBlank { "Reader" }
    Box {
        Surface(
            onClick = { menuOpen = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = tint,
            modifier = Modifier.padding(end = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        BluetoothVector,
                        contentDescription = "Reader: $headline",
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    pillName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (wantsAttention && !busy) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                }
                if (fwProgress != null || (updateAvailable && !busy)) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        SyncGlyph(
                            spinning = fwActive,
                            pulsing = fwWaiting,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            sizeDp = 14,
                            contentDescription = fwProgress?.let { firmwareProgressLabel(it) }
                                ?: "Firmware update available",
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 260.dp)) {
                Text(headline, style = MaterialTheme.typography.bodyMedium)
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()

            when {
                stage == LinkStage.NEEDS_PERMISSION -> DropdownMenuItem(
                    text = { Text("Grant Bluetooth permission") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    onClick = { menuOpen = false; permissionLauncher.launch(BlePermissions.required) },
                )
                stage == LinkStage.BLUETOOTH_OFF -> DropdownMenuItem(
                    text = { Text("Open Bluetooth settings") },
                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                    onClick = {
                        menuOpen = false
                        // Asking rather than switching: enabling the radio
                        // without the user's say-so is exactly what
                        // BLUETOOTH_CONNECT was tightened to stop, and it is
                        // their radio.
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
                !state.hasStoredPairing && !state.connected -> DropdownMenuItem(
                    text = { Text("Pair") },
                    enabled = !busy,
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    onClick = { menuOpen = false; onPair() },
                )
                state.connected -> DropdownMenuItem(
                    text = { Text("Disconnect") },
                    leadingIcon = { Icon(Icons.Default.Close, null) },
                    onClick = { menuOpen = false; onDisconnect() },
                )
                else -> DropdownMenuItem(
                    text = { Text(if (stage == LinkStage.FAILED) "Retry" else "Connect") },
                    enabled = !busy,
                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                    onClick = { menuOpen = false; onConnect() },
                )
            }

            // The reader's own settings hang off the reader's own control, not
            // off a second gear in the bar. Everything in that sheet is read
            // from and written to the device over this link -- if the link is
            // the subject, this is where it belongs.
            DropdownMenuItem(
                text = { Text("Reader settings") },
                leadingIcon = { Icon(Icons.Default.Settings, null) },
                enabled = state.connected,
                onClick = { menuOpen = false; onDeviceSettings() },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        fwProgress?.let { firmwareProgressLabel(it) }
                            ?: if (updateAvailable) "Firmware update available" else "Firmware"
                    )
                },
                leadingIcon = {
                    if (fwProgress != null || updateAvailable) {
                        SyncGlyph(
                            spinning = fwActive,
                            pulsing = fwWaiting,
                            tint = MaterialTheme.colorScheme.primary,
                            sizeDp = 24,
                            contentDescription = null,
                        )
                    } else {
                        Icon(Icons.Default.Build, null, tint = LocalContentColor.current)
                    }
                },
                onClick = { menuOpen = false; onFirmware() },
            )
        }
    }
}

/** Connected *and* authorised. Fixed rather than themed: "the reader is
 *  usable" is the one thing on this bar that should read the same either way. */
private val READER_CONNECTED = Color(0xFF2E9E4F)

/**
 * What the pill, the menu and the Firmware screen say about an update in flight:
 * "Downloading firmware · 45% · 1.2 MB/s", "Sending to reader · 45% · 31 KB/s".
 * The rate is left off until it is known.
 */
private fun firmwareProgressLabel(p: FirmwareProgress): String {
    fun moving(what: String) = buildString {
        append(what).append(" · ").append(p.percent).append('%')
        if (p.kbps > 0) append(" · ").append(transferRate(p.kbps))
    }
    return when (p.phase) {
        FirmwarePhase.DOWNLOADING -> moving("Downloading firmware")
        FirmwarePhase.SENDING -> moving("Sending to reader")
        FirmwarePhase.INSTALLING -> "Sent to reader, waiting on install"
        FirmwarePhase.SCHEDULED -> "${p.version} installs when the reader sleeps"
    }
}

/** The sync glyph: turning while [spinning] (bytes moving), pulsing while [pulsing] (waiting). */
@Composable
private fun SyncGlyph(
    spinning: Boolean,
    tint: Color,
    sizeDp: Int,
    contentDescription: String?,
    pulsing: Boolean = false,
) {
    val angle = if (spinning) {
        val turn = rememberInfiniteTransition(label = "firmwareSync")
        turn.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing)),
            label = "firmwareSyncAngle",
        ).value
    } else {
        0f
    }
    val glow = if (pulsing && !spinning) {
        val beat = rememberInfiniteTransition(label = "firmwareWait")
        beat.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900), repeatMode = RepeatMode.Reverse),
            label = "firmwareWaitAlpha",
        ).value
    } else {
        1f
    }
    Icon(
        SyncVector,
        contentDescription,
        tint = tint,
        modifier = Modifier.size(sizeDp.dp).rotate(angle).alpha(glow),
    )
}

/**
 * The Material `sync` glyph -- two arrows chasing each other round a circle --
 * for "firmware update available". Drawn here for the same reason as
 * [BluetoothVector]: it ships only in material-icons-extended. An up arrow
 * reads as "open this menu", not "update".
 */
private val SyncVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sync",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 4f)
            verticalLineTo(1f)
            lineTo(8f, 5f)
            lineToRelative(4f, 4f)
            verticalLineTo(6f)
            curveToRelative(3.31f, 0f, 6f, 2.69f, 6f, 6f)
            curveToRelative(0f, 1.01f, -0.25f, 1.97f, -0.7f, 2.8f)
            lineToRelative(1.46f, 1.46f)
            curveTo(19.54f, 15.03f, 20f, 13.57f, 20f, 12f)
            curveToRelative(0f, -4.42f, -3.58f, -8f, -8f, -8f)
            close()
            moveTo(12f, 18f)
            curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
            curveToRelative(0f, -1.01f, 0.25f, -1.97f, 0.7f, -2.8f)
            lineTo(5.24f, 7.74f)
            curveTo(4.46f, 8.97f, 4f, 10.43f, 4f, 12f)
            curveToRelative(0f, 4.42f, 3.58f, 8f, 8f, 8f)
            verticalLineToRelative(3f)
            lineToRelative(4f, -4f)
            lineToRelative(-4f, -4f)
            verticalLineToRelative(3f)
            close()
        }
    }.build()
}

/**
 * The Material `bluetooth` glyph, drawn here rather than pulled in.
 *
 * `material-icons-extended` is the only place this icon ships, and it is a
 * multi-megabyte artifact carrying several thousand vectors. Adding it to
 * build.gradle.kts for one 24dp path would be a poor trade, and the rest of
 * this file already sticks to the core icon set.
 */
private val BluetoothVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "Bluetooth",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.71f, 7.71f)
            lineTo(12f, 2f)
            horizontalLineTo(11f)
            verticalLineToRelative(7.59f)
            lineTo(6.41f, 5f)
            lineTo(5f, 6.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(11f, 14.41f)
            verticalLineTo(22f)
            horizontalLineToRelative(1f)
            lineToRelative(5.71f, -5.71f)
            lineToRelative(-4.3f, -4.29f)
            lineToRelative(4.3f, -4.29f)
            close()
            moveTo(13f, 5.83f)
            lineToRelative(1.88f, 1.88f)
            lineTo(13f, 9.59f)
            verticalLineTo(5.83f)
            close()
            moveTo(14.88f, 16.29f)
            lineTo(13f, 18.17f)
            verticalLineToRelative(-3.76f)
            lineToRelative(1.88f, 1.88f)
            close()
        }
    }.build()
}

/** The reader asked this app for something. Distinct from an upload the user
 *  started, because nothing on this screen was touched to cause it. */
@Composable
private fun StoreBar(activity: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            // One line, always. A status carrying a book title is longer than
            // the screen, and an unbounded Text answers that by wrapping -- so
            // the bar grew a line at a time and shoved the list down.
            Text(
                activity,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How the last sync ended, in the sync bar's place. No spinner: nothing is running. */
@Composable
private fun SyncSummaryBar(s: SyncSummary) {
    val context = LocalContext.current
    // A minute tick, so a sync from before midnight picks up its date.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            value = System.currentTimeMillis()
        }
    }
    val time = android.text.format.DateFormat.getTimeFormat(context).format(Date(s.finishedAt))
    val sameDay = Calendar.getInstance().run {
        timeInMillis = s.finishedAt
        val day = get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
        timeInMillis = now
        day == get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
    }
    val clock = if (sameDay) time
    else SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(s.finishedAt)) + " " + time
    val failed = s.result != SyncSummary.Result.DONE
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (failed) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                s.line(clock),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** BLE moves a few KB/s, so a real byte-level bar is the difference between
 *  "working" and "hung". Zero total means an indeterminate phase. */
@Composable
private fun TransferBar(t: TransferProgress) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Title takes the room that is left and truncates, so a long
                // title elides instead of wrapping and growing the bar.
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                // Percent first: the question being asked is "how far along". A
                // send adds its rate, which says whether minutes remain.
                // Tabular figures, so "11%" is as wide as "88%" and the title
                // beside it does not shuffle on every update.
                Text(
                    when {
                        t.total <= 0 -> "…"
                        t.kbps > 0 -> "${t.percent}% · ${transferRate(t.kbps)}"
                        else -> "${t.percent}%"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            // The theme's own track height; the bar should stay compact.
            val bar = Modifier.fillMaxWidth()
            if (t.total > 0) {
                LinearProgressIndicator(progress = { t.fraction }, modifier = bar)
            } else {
                LinearProgressIndicator(bar)
            }
        }
    }
}

/**
 * Pick the reader to pair with. The scan runs while this is open (AppScaffold
 * starts and stops it); tapping a reader pairs with exactly that one.
 */
@Composable
private fun ReaderPickerDialog(
    reason: String?,
    scan: ReaderScan?,
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
    onPick: (DiscoveredReader) -> Unit,
) {
    // Null only for the moment before the scan's first state lands: treat as scanning.
    val scanning = scan?.scanning ?: true
    val readers = scan?.readers.orEmpty()
    val error = scan?.error
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(BluetoothVector, null) },
        title = { Text("Pair a reader") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when {
                    error != null -> Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    readers.isEmpty() && scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Looking for readers… Open Settings on the reader.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    readers.isEmpty() -> Text("No readers found", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                            readers.forEach { r -> ReaderRow(r, onClick = { onPick(r) }) }
                        }
                        Text(
                            "Then type the passkey the reader shows.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRescan, enabled = !scanning) { Text("Scan again") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReaderRow(reader: DiscoveredReader, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Icon(BluetoothVector, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                reader.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "…" + reader.address.takeLast(5),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        SignalBars(reader.rssi)
    }
}

/** Three bars from RSSI: all three from -60 dBm, two from -75, one below that. */
@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -60 -> 3
        rssi >= -75 -> 2
        else -> 1
    }
    val label = when (level) {
        3 -> "Strong signal"
        2 -> "Fair signal"
        else -> "Weak signal"
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        for (i in 1..3) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 4).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) on else off)
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("No books loaded", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Check the server settings, then refresh.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CoverThumb(
    url: String?,
    covers: CoverCache,
    creds: Pair<String, String>,
    desaturated: Boolean = false,
) {
    // produceState keyed on the url: scrolling reuses the composable, and a
    // stale cover flashing on the wrong row is worse than none at all.
    val bmp by produceState<Bitmap?>(initialValue = null, url) {
        value = url?.let { covers.get(it, creds.first, creds.second) }
    }
    Box(
        Modifier
            .size(44.dp, 62.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                // Actually grey, not just faint. Dimming alone still reads as a
                // colour cover behind a veil; dropping the saturation says "this
                // one is not on the reader yet" at a glance, which is the point.
                colorFilter = if (desaturated) {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                } else null,
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookItem(
    row: BookRow,
    /** Store only: this book is already on the shelf, so mark it as had. */
    onShelf: Boolean = false,
    busy: Boolean,
    removing: Boolean = false,
    awaitingReader: Boolean = false,
    /** This book is open on the reader right now. */
    readingNow: Boolean = false,
    /** Store only: not saved on this phone, but the reader holds it. */
    onReader: Boolean = false,
    covers: CoverCache,
    creds: Pair<String, String>,
    onOpenDetail: () -> Unit,
    onCache: () -> Unit,
    onRemove: () -> Unit,
) {
    // Saved on the phone but not yet confirmed on the reader. The two facts are
    // tracked separately for a reason -- the BLE protocol has no list-files, so
    // `sentFromThisApp` is the only honest answer about the device -- and the
    // gap between them is a real state the row should show. Otherwise a book
    // that finished downloading looks identical to one already on the reader.
    // Either the explicit pending mark from the ViewModel, or the inferred
    // state for rows it never saw (a book saved by a previous run of the app,
    // still waiting for a reader it has not met since).
    val pending = (awaitingReader || (row.cached && !row.sentFromThisApp)) &&
        !removing && !row.pendingRemoval
    // Removed here, but the reader has not been told yet -- it was asleep, or
    // out of range. The offline copy is deliberately still on disk so this row
    // can exist at all; without it the pending deletion would be invisible and
    // the book would look as though it had never been saved.
    val owed = row.pendingRemoval && !removing

    ListItem(
        // A Store row for a book already on the shelf gets a tint rather than a
        // word. "offline" said the same thing in text, competing with the
        // reading position for the row's one status line; a background says it
        // without spending a line and is legible while scrolling past.
        colors = if (onShelf) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            ListItemDefaults.colors()
        },
        // A book on its way out is dimmed and stops taking taps. It is still
        // listed -- it is still on the reader until the reader says otherwise --
        // but offering to open or re-save something mid-removal is offering a
        // race the user cannot win.
        //
        // One waiting to reach the reader is dimmed too, but stays tappable:
        // nothing about it is racing, and the detail sheet is where the full
        // cover lives.
        modifier = Modifier
            .clickable(enabled = !removing && !owed, onClick = onOpenDetail)
            .alpha(
                when {
                    removing || owed -> 0.45f
                    pending -> 0.55f
                    else -> 1f
                }
            ),
        leadingContent = {
            CoverThumb(row.book.coverUrl, covers, creds, desaturated = pending || removing || owed)
        },
        headlineContent = { Text(row.book.title, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                Text(row.book.author, style = MaterialTheme.typography.bodySmall)
                // Said in words, not only in grey. A tint is a weak signal for
                // something the user is explicitly waiting on; a line of text
                // either appears or it does not.
                if (removing || owed || pending || onReader) {
                    Text(
                        when {
                            removing -> "Removing from reader…"
                            owed -> "Will be removed on next device sync"
                            pending -> "Waiting to send to reader…"
                            else -> "On the reader"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (removing || owed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (readingNow) {
                        Text(
                            "Reading now · ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    row.progress?.let { p ->
                        val when_ = if (p.timestamp > 0)
                            SimpleDateFormat("d MMM", Locale.getDefault())
                                .format(Date(p.timestamp * 1000))
                        else "—"
                        Text(
                            "${p.percentLabel} · $when_",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // No "offline" or "sent" labels: being IN the Library already
                    // says offline, and a sent row is simply one without the grey.
                    // Extra words would compete with the reading position here.
                }
            }
        },
        // One control, not a menu. Saving offline *is* how a book reaches the
        // reader -- the ViewModel runs the mirror straight after a successful
        // save -- so there is no separate send.
        trailingContent = {
            OfflineToggle(
                row = row,
                busy = busy,
                removing = removing || owed,
                openOnReader = readingNow,
                onCache = onCache,
                onRemove = onRemove,
            )
        },
    )
}

/**
 * Save offline / remove the offline copy, as one button with three states.
 *
 * Saved is a filled tick rather than a greyed-out download arrow: "already
 * done" and "not available" look the same when both are just dim, and this one
 * is very much still tappable.
 */
@Composable
private fun OfflineToggle(
    row: BookRow,
    busy: Boolean,
    removing: Boolean = false,
    /** Open on the reader: the removal is confirmed by the "Open on the reader" dialog instead. */
    openOnReader: Boolean = false,
    onCache: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }

    when {
        // `removing` outranks `busy` and outlives it: the spinner has to keep
        // turning for the whole removal, not just the part this app is doing.
        busy || removing -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        row.cached -> IconButton(onClick = { if (openOnReader) onRemove() else confirmRemove = true }) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Saved offline — tap to remove from this phone",
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
        else -> IconButton(onClick = onCache) {
            Icon(
                DownloadGlyph,
                contentDescription = "Save offline",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Remove the offline copy?") },
            text = {
                Text(
                    "Deletes \"${row.book.title}\" from this phone and the reader.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Keep") }
            },
        )
    }
}

/**
 * Everything Calibre knows about one book.
 *
 * A bottom sheet rather than a second screen: this app deliberately has one
 * screen and a set of sheets (see the note on the device-settings overlay), and
 * a book preview is a thing you glance at and dismiss, not a place you navigate
 * to. It carries the two actions that belong to a single book — save offline
 * and open it — so the row itself can stay a row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDetailSheet(
    row: BookRow,
    busy: Boolean,
    covers: CoverCache,
    creds: Pair<String, String>,
    canOpenInReader: () -> Boolean,
    onDismiss: () -> Unit,
    onCache: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    val book = row.book
    // A PackageManager query, so it is asked once per book and re-asked only
    // when the book's saved state changes (an unsaved book has no file, hence
    // no intent to resolve, hence no handler).
    val canOpen = remember(book.filename, row.cached) { canOpenInReader() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                BigCover(book.coverUrl, covers, creds)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(book.author, style = MaterialTheme.typography.bodyMedium)
                    if (book.seriesLabel.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            book.seriesLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    row.progress?.let { p ->
                        Spacer(Modifier.height(6.dp))
                        val when_ = if (p.timestamp > 0)
                            SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                                .format(Date(p.timestamp * 1000))
                        else "—"
                        Text(
                            "${p.percentLabel} read · $when_ · ${p.device}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Working…")
                    }
                } else if (row.cached) {
                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Remove offline copy")
                    }
                } else {
                    Button(onClick = onCache, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save offline")
                    }
                }
            }

            OutlinedButton(
                // Two separate reasons to be unavailable, and the label says
                // which: a book that is not on the phone has nothing to open,
                // and a phone with no EPUB viewer has nothing to open it with.
                onClick = onOpen,
                enabled = row.cached && canOpen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open in KOReader")
            }
            if (row.cached && !canOpen) {
                Text(
                    "Install an EPUB reader app to open this.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.cached && row.sentFromThisApp) {
                Text(
                    "Removing it here also removes it from the reader.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            DetailField("Series", book.seriesLabel)
            DetailField("Publisher", book.publisher)
            DetailField("Published", book.published)
            DetailField("Language", book.language)
            DetailField("Tags", book.tags.joinToString(" · "))
            DetailField("Format", book.format.uppercase())
            DetailField("Size", humanSize(book.sizeBytes))
            DetailField("Filename", book.filename)

            if (book.description.isNotBlank()) {
                HorizontalDivider()
                Text("Description", style = MaterialTheme.typography.labelLarge)
                Text(book.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A metadata row, or nothing at all when Calibre had no value for it. */
@Composable
private fun DetailField(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** Empty for an unknown size rather than "0 B", which reads as a broken file. */
private fun humanSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/**
 * The detail view's cover.
 *
 * Shares [CoverCache], which decodes to a bounded size for list thumbnails —
 * about 320px tall. That is comfortably more than this 132dp box needs, so the
 * detail view costs no extra decode and no second network fetch.
 */
@Composable
private fun BigCover(url: String?, covers: CoverCache, creds: Pair<String, String>) {
    val bmp by produceState<Bitmap?>(initialValue = null, url) {
        value = url?.let { covers.get(it, creds.first, creds.second) }
    }
    Box(
        Modifier
            .size(132.dp, 190.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One page of app settings.
 *
 * One sheet per section, so each can be opened directly: the overflow menu
 * lists every section except [FIRMWARE], which opens from the reader pill.
 */
enum class SettingsSection(val label: String) {
    CALIBRE("Calibre"),
    BLUETOOTH("Bluetooth"),
    UPDATES("Updates"),
    FIRMWARE("Firmware"),
    DIAGNOSTICS("Diagnostics"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    section: SettingsSection,
    config: Config,
    state: UiState,
    onDismiss: () -> Unit,
    onSave: (Config) -> Unit,
    onForgetPairing: () -> Unit,
    onPair: () -> Unit,
    onCrashReport: () -> Unit,
    onCheckFirmware: () -> Unit,
    onInstallFirmware: () -> Unit,
) {
    var c by remember { mutableStateOf(config) }

    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(section.label, style = MaterialTheme.typography.titleLarge)

            when (section) {
                SettingsSection.CALIBRE -> {
                    Field("Server URL", c.serverUrl, error = httpsError(c.serverUrl)) { c = c.copy(serverUrl = it) }
                    Field("Username", c.username) { c = c.copy(username = it) }
                    Field("Password", c.password, secret = true) { c = c.copy(password = it) }
                    Text(
                        "https:// server address only, no path.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SettingsSection.BLUETOOTH -> {
                    Text(
                        buildString {
                            append(
                                when (state.pairing) {
                                    PairingState.TRUSTED -> "Paired"
                                    PairingState.UNPAIRED -> "Not paired"
                                }
                            )
                            state.device?.let { d ->
                                d.firmwareName?.let { append("\nFirmware: $it") }
                                d.protocolVersion?.let { append("\nProtocol: v$it") }
                                if (d.uploadKinds.isNotEmpty()) {
                                    append("\nAccepts: " + d.uploadKinds.joinToString(", "))
                                }
                            }
                            append(
                                "\n\nThe reader has no list-files operation over Bluetooth, so " +
                                    "\"sent\" marks only what this app uploaded — not what is " +
                                    "actually on the device."
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Both halves of pairing in one place. Re-pairing is Forget, then Pair.
                    OutlinedButton(
                        onClick = onPair,
                        enabled = !state.hasStoredPairing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pair") }
                    // Always enabled: a half-finished pairing leaves no stored pairing
                    // but an Android bond and a reader that remembers the phone.
                    // forgetPairing() opens Bluetooth settings to remove the bond.
                    OutlinedButton(
                        onClick = onForgetPairing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Forget pairing") }
                }

                SettingsSection.UPDATES -> {
                    Text(
                        "Installed: " + dev.bluecarrel.app.BuildConfig.VERSION_NAME +
                            " (code " + dev.bluecarrel.app.BuildConfig.VERSION_CODE + ")",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Field("Update page", c.updatesUrl, error = httpsError(c.updatesUrl)) { c = c.copy(updatesUrl = it) }
                    Text(
                        "Firmware update page (firmware.json). Blank uses GitHub releases.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                // The default is a download folder; its release page is the one to open.
                                val page = c.effectiveUpdatesUrl.let {
                                    if (it == DEFAULT_UPDATES_URL) DEFAULT_UPDATES_PAGE else it
                                }
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(page))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        enabled = c.effectiveUpdatesUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open update page") }
                }

                SettingsSection.FIRMWARE -> {
                    LaunchedEffect(Unit) { onCheckFirmware() }
                    val linked = state.connected && state.authorized
                    val latest = state.latestFirmware
                    val running = state.readerFirmware
                    // Stamps are yyyyMMdd.HHmm, so string order is build order. A reader
                    // flashed over USB with a newer build than the page is not offered a
                    // downgrade.
                    val progress = state.firmwareProgress
                    // Only the reader's own report counts: not the upload finishing, not the
                    // prompt being answered. Until it names the new build, nothing is up to date.
                    val upToDate = progress == null && !state.firmwareChecking &&
                        latest != null && running != null && running >= latest.version
                    Text(
                        buildString {
                            append("On the reader: ")
                            append(
                                running ?: when {
                                    state.readerFirmwareTooOld -> "older than the version report"
                                    linked -> "checking\u2026"
                                    else -> "connect to check"
                                }
                            )
                            append("\nOn the update page: ")
                            append(
                                latest?.let {
                                    it.version + " (" + String.format(java.util.Locale.US, "%.1f", it.size / 1048576.0) + " MB)"
                                } ?: (state.firmwareCheckNote ?: "checking\u2026")
                            )
                            progress?.let { append("\n").append(firmwareProgressLabel(it)) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Enabled as soon as the page has a newer build. A check or a sync in
                    // progress does not block it: the send waits for the sync, and
                    // installLatestFirmware() re-checks the reader's build before sending.
                    val newer = latest != null && (running == null || running < latest.version)
                    Button(
                        onClick = onInstallFirmware,
                        enabled = linked && newer && !upToDate && !state.readerUpdateStaged && progress == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                progress != null -> firmwareProgressLabel(progress)
                                upToDate -> "Reader is up to date"
                                state.readerInstallAtSleep -> "Installs when the reader sleeps"
                                state.readerUpdateStaged -> "Waiting on the reader"
                                latest != null -> "Send ${latest.version} to the reader"
                                state.firmwareChecking -> "Checking for updates\u2026"
                                else -> "No update found"
                            }
                        )
                    }
                    Text(
                        "The reader asks to install now or when it sleeps.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                SettingsSection.DIAGNOSTICS -> {
                    Text(
                        buildString {
                            append("App ").append(dev.bluecarrel.app.BuildConfig.VERSION_NAME)
                            append(" (code ").append(dev.bluecarrel.app.BuildConfig.VERSION_CODE).append(")\n")
                            append("link=").append(state.connection)
                            append("  authorized=").append(state.authorized)
                            append("  pairing=").append(state.pairing).append('\n')
                            append("stored host=").append(state.storedHostId?.take(8) ?: "none").append('\n')
                            // So "the row did not grey out" can be answered:
                            // either the state is empty (the ViewModel never
                            // marked it) or it is not (the row is not drawing
                            // it). Two very different bugs.
                            append("busy=").append(state.busyBookIds.joinToString(",").ifBlank { "-" })
                            append("  pending=").append(
                                state.pendingTransferIds.joinToString(",").ifBlank { "-" }
                            )
                            append("  removing=").append(
                                state.removingBookIds.joinToString(",").ifBlank { "-" }
                            ).append('\n')
                            append("reader auth_error=").append(state.lastAuthError ?: "-").append('\n')
                            append("last auth attempt:\n").append(state.authTrace)
                            append("\nstore:\n").append(state.storeTrace)
                            append("\n\nlink: ").append(state.linkInfo.ifBlank { "-" })
                            append("\n\nlast transfer:\n").append(state.lastTransfer ?: "none yet")
                            append("\n\nlast sync:\n")
                            append(state.syncTrace.joinToString("\n").ifBlank { "none yet" })
                            append("\n\nsync before:\n")
                            append(state.previousSyncTrace.joinToString("\n").ifBlank { "none" })
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    OutlinedButton(
                        onClick = onCrashReport,
                        enabled = state.connected && state.authorized &&
                            state.device?.downloadKinds?.contains("crash_report") == true,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Get crash report") }
                }
            }

            // Only the sections that actually hold editable fields get a Save.
            // A Save button under a read-only diagnostics dump invites the
            // question of what it is going to save.
            if (section == SettingsSection.CALIBRE || section == SettingsSection.UPDATES) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = { onSave(c) }, enabled = c.urlProblem == null, modifier = Modifier.fillMaxWidth()) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    error: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error) }
        } else null,
        visualTransformation = if (secret)
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        // Password keyboard, autocorrect off: the keyboard does not learn the password.
        keyboardOptions = if (secret) {
            KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        } else {
            KeyboardOptions.Default
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** [HTTPS_REQUIRED] for a filled-in URL that is not https, else null. */
private fun httpsError(url: String): String? =
    if (url.isNotBlank() && !HttpGuard.isHttps(url)) HTTPS_REQUIRED else null

/**
 * Shown when nothing has been saved offline yet.
 *
 * The Library is deliberately empty rather than pre-filled with the catalogue:
 * what is here is what the reader mirrors, so it only contains books that were
 * chosen.
 */
@Composable
private fun EmptyLibraryHint(onBrowse: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing saved offline yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Save books from the Store to put them on the reader.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBrowse) { Text("Browse the Store") }
    }
}

/**
 * A download mark: an arrow into a tray.
 *
 * Not KeyboardArrowDown, which is the chevron every expandable section uses --
 * on a book row it read as "show me more", which is the opposite of what tapping
 * it does. The tray line is what makes it "save", not "scroll".
 */
private val DownloadGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Shaft and head.
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 3f); lineTo(12f, 14f)
            moveTo(7.5f, 10f); lineTo(12f, 14.5f); lineTo(16.5f, 10f)
        }
        // The tray it lands in.
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4.5f, 18.5f); lineTo(19.5f, 18.5f)
        }
    }.build()
}
