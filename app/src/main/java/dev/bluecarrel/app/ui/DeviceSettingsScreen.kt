package dev.bluecarrel.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.bluecarrel.app.DeviceSettingsUi
import dev.bluecarrel.app.data.DeviceSetting
import dev.bluecarrel.app.data.DeviceSettingGroup
import dev.bluecarrel.app.data.DeviceSettingsSchema
import dev.bluecarrel.app.data.NumOption

/**
 * The reader's own settings, read off the device and written back in one go.
 *
 * A full screen rather than another sheet: there are ~35 rows and several of
 * them open a menu of their own, which a bottom sheet handles badly. The four
 * firmware categories are collapsible sections, so the screen opens as a short
 * index rather than a wall of switches.
 *
 * Nothing here writes as you touch it. Every save is a BLE round trip plus a
 * flash write on the reader, so changes queue up as pending edits and go across
 * together when the user presses Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(
    ui: DeviceSettingsUi,
    onBack: () -> Unit,
    onEdit: (String, Int) -> Unit,
    onEditText: (String, String) -> Unit,
    onReload: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismissNotice: () -> Unit,
    autoDownloadFirmware: Boolean,
    onAutoDownloadFirmware: (Boolean) -> Unit,
    matchPhoneDarkMode: Boolean,
    onMatchPhoneDarkMode: (Boolean) -> Unit,
    readerFirmware: String?,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave = { if (ui.dirty) confirmDiscard = true else onBack() }

    // The system back gesture is the way most people will leave this screen, so
    // it has to hit the same unsaved-changes check the toolbar arrow does.
    BackHandler(enabled = !ui.saving) { leave() }

    // Display open, the rest folded: the first section is enough to show what
    // the screen is, and the other three are one tap away.
    val expanded = remember {
        mutableStateMapOf<DeviceSettingGroup, Boolean>().apply {
            put(DeviceSettingGroup.DISPLAY, true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reader settings") },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onReload,
                        enabled = !ui.loading && !ui.saving,
                    ) { Icon(Icons.Default.Refresh, "Read from the reader again") }
                },
            )
        },
        bottomBar = {
            if (ui.loaded) {
                SaveBar(ui = ui, onSave = onSave, onDiscard = onDiscard)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ui.notice?.let { NoticeBanner(it.text, it.isError, onDismissNotice) }

            // The rows here that belong to the APP rather than the reader: fetching
            // and sending is the phone's job, so it saves the instant it is switched and
            // never waits on the reader's Save. Its partner, auto-install, is the
            // reader's own setting and sits under System with the rest.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onAutoDownloadFirmware(!autoDownloadFirmware) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Download firmware updates automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sends new reader builds in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoDownloadFirmware, onCheckedChange = onAutoDownloadFirmware)
            }
            Text(
                "Reader firmware: " + (readerFirmware ?: "unknown until the next check"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            // Also the app's: it saves at once, and the reader is told when the phone switches.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onMatchPhoneDarkMode(!matchPhoneDarkMode) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Match phone dark mode", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = matchPhoneDarkMode, onCheckedChange = onMatchPhoneDarkMode)
            }
            HorizontalDivider()

            when {
                ui.loading && !ui.loaded -> Loading()
                ui.loadError != null && !ui.loaded -> LoadFailed(ui.loadError!!, onReload)
                !ui.loaded -> Loading()
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    for (group in DeviceSettingsSchema.groups) {
                        // Only rows the reader actually sent. A board without a
                        // frontlight or a touchscreen has no field for some of
                        // these, and its document simply omits them.
                        val rows = DeviceSettingsSchema.of(group)
                            .filter { ui.values.containsKey(it.key) }
                        if (rows.isEmpty()) continue

                        val open = expanded[group] == true
                        val pending = rows.count { ui.isChanged(it.key) }

                        item(key = "header-${group.name}") {
                            GroupHeader(
                                title = group.label,
                                rows = rows.size,
                                pending = pending,
                                open = open,
                                onToggle = { expanded[group] = !open },
                            )
                        }
                        if (open) {
                            items(rows.size, key = { "row-${rows[it].key}" }) { i ->
                                SettingRow(
                                    setting = rows[i],
                                    value = ui.valueOf(rows[i].key),
                                    text = ui.textValueOf(rows[i].key),
                                    changed = ui.isChanged(rows[i].key),
                                    enabled = !ui.saving,
                                    onEdit = onEdit,
                                    onEditText = onEditText,
                                )
                            }
                        }
                        item(key = "divider-${group.name}") { HorizontalDivider() }
                    }
                    item(key = "footer") {
                        Text(
                            "Settings can't be saved while a book is open on the reader.",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Leave without saving?") },
            text = {
                Text(
                    "${ui.edits.size} change${if (ui.edits.size == 1) "" else "s"} have not " +
                        "been sent to the reader."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun SaveBar(ui: DeviceSettingsUi, onSave: () -> Unit, onDiscard: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (ui.saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDiscard,
                    enabled = ui.dirty && !ui.saving,
                ) { Text("Revert") }
                Button(
                    onClick = onSave,
                    enabled = ui.dirty && !ui.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            ui.saving -> "Saving…"
                            ui.dirty -> "Save ${ui.edits.size} change" +
                                if (ui.edits.size == 1) "" else "s"
                            else -> "Nothing to save"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text("Reading the reader's settings…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Warning, null, Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Could not read the settings", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    rows: Int,
    pending: Int,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (pending > 0) "$pending changed · $rows" else "$rows",
                style = MaterialTheme.typography.labelSmall,
                color = if (pending > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (open) "Collapse" else "Expand",
            )
        }
    }
}

/**
 * A free-text row. The only one is the device name, and the cap is enforced here
 * as well as in the firmware's buffer -- a field that silently drops the 17th
 * character is worse than one that will not take it.
 */
@Composable
private fun TextRow(
    setting: DeviceSetting.Text,
    value: String,
    changed: Boolean,
    enabled: Boolean,
    onEdit: (String, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onEdit(setting.key, it.take(setting.maxLength)) },
            label = { Text(setting.label) },
            placeholder = { Text(setting.placeholder) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Row(Modifier.fillMaxWidth()) {
                    Text(setting.help ?: "", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${value.length}/${setting.maxLength}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
        )
        if (changed) {
            Text(
                "Not saved yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SettingRow(
    setting: DeviceSetting,
    value: Int?,
    text: String,
    changed: Boolean,
    enabled: Boolean,
    onEdit: (String, Int) -> Unit,
    onEditText: (String, String) -> Unit,
) {
    when (setting) {
        is DeviceSetting.Text -> TextRow(setting, text, changed, enabled, onEditText)
        is DeviceSetting.Toggle -> ToggleRow(setting, value, changed, enabled, onEdit)
        is DeviceSetting.Choice -> PickerRow(
            label = setting.label,
            help = setting.help,
            changed = changed,
            enabled = enabled,
            // An enum is persisted by index, so option n is what the reader
            // means by n. A value outside the list (an older or newer firmware
            // with a longer enum) is shown as itself rather than mislabelled.
            options = setting.options.mapIndexed { i, l -> NumOption(i, l) },
            value = value,
            onPick = { onEdit(setting.key, it) },
        )
        is DeviceSetting.Values -> PickerRow(
            label = setting.label,
            help = setting.help,
            changed = changed,
            enabled = enabled,
            options = withCurrent(setting.options, value),
            value = value,
            onPick = { onEdit(setting.key, it) },
        )
        is DeviceSetting.Range -> SliderRow(setting, value, changed, enabled, onEdit)
    }
}

/** The reader's own value, kept selectable even when it is not one this app
 *  would have offered — an SD-card font size, say. */
private fun withCurrent(options: List<NumOption>, value: Int?): List<NumOption> =
    if (value == null || options.any { it.value == value }) options
    else (options + NumOption(value, value.toString())).sortedBy { it.value }

@Composable
private fun ToggleRow(
    setting: DeviceSetting.Toggle,
    value: Int?,
    changed: Boolean,
    enabled: Boolean,
    onEdit: (String, Int) -> Unit,
) {
    val on = (value ?: 0) != 0
    ListItem(
        headlineContent = { Text(setting.label) },
        supportingContent = if (setting.help == null && !changed) null else {
            { Supporting(setting.help, changed) }
        },
        trailingContent = {
            Switch(
                checked = on,
                onCheckedChange = { onEdit(setting.key, if (it) 1 else 0) },
                enabled = enabled,
            )
        },
        modifier = Modifier.clickable(enabled = enabled) {
            onEdit(setting.key, if (on) 0 else 1)
        },
    )
}

/**
 * A row whose value comes off a list: enum labels by index, or the handful of
 * numbers worth offering for a number field.
 *
 * A menu rather than a segmented control because the longest list here has
 * eight options and a segmented row of eight is unreadable on a phone.
 */
@Composable
private fun PickerRow(
    label: String,
    help: String?,
    changed: Boolean,
    enabled: Boolean,
    options: List<NumOption>,
    value: Int?,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == value }?.label
        ?: value?.let { "Unknown ($it)" }
        ?: "—"

    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (help == null && !changed) null else {
            { Supporting(help, changed) }
        },
        trailingContent = {
            Box {
                TextButton(onClick = { open = true }, enabled = enabled) {
                    Text(
                        current,
                        color = if (changed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (o in options) {
                        DropdownMenuItem(
                            text = { Text(o.label) },
                            trailingIcon = {
                                if (o.value == value) Icon(Icons.Default.Check, null)
                            },
                            onClick = { open = false; onPick(o.value) },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(enabled = enabled) { open = true },
    )
}

/** A number over a range. The slider only stops on values the firmware's own
 *  step allows, so the reader never has to silently correct one. */
@Composable
private fun SliderRow(
    setting: DeviceSetting.Range,
    value: Int?,
    changed: Boolean,
    enabled: Boolean,
    onEdit: (String, Int) -> Unit,
) {
    val stops = setting.steps
    val current = value ?: setting.min
    // A stored value off the grid (an older firmware's step) snaps to the
    // nearest stop for display only; no edit is recorded until the user moves it.
    val index = stops.indices.minByOrNull { kotlin.math.abs(stops[it] - current) } ?: 0

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(setting.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (setting.unit.isEmpty()) "$current" else "$current ${setting.unit}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (changed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        setting.help?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { v ->
                val i = v.toInt().coerceIn(0, stops.lastIndex)
                if (stops[i] != current) onEdit(setting.key, stops[i])
            },
            valueRange = 0f..stops.lastIndex.toFloat(),
            steps = (stops.size - 2).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

/** "Changed" is worth a line of its own: it is the only thing on the row that
 *  says this value is not yet on the device. */
@Composable
private fun Supporting(help: String?, changed: Boolean) {
    Column {
        help?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        if (changed) {
            Text(
                "Not saved yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
