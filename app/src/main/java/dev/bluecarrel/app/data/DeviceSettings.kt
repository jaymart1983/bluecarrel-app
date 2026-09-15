package dev.bluecarrel.app.data

import org.json.JSONObject

/**
 * The reader's own settings document, as the app is willing to edit it.
 *
 * ## Why the whole document goes back
 *
 * The firmware's `CrossPointSettings::fromJson()` reads most fields as
 * `doc[key] | currentValue` against the **live** settings singleton
 * (`BleLink::applySettingsDocument()` calls `SETTINGS.fromJson(...)`), so for
 * everything that comes out of the generic `getSettingsList()` loop an absent
 * key does keep the current value.
 *
 * It is **not** true for the fields that loop skips and `fromJson()` handles by
 * hand. Those fall back to a compile-time default rather than to the current
 * value, so omitting them wipes them:
 *
 * ```
 * uint8_t storedFontSize = doc["fontSize"] | DEFAULT_FONT_POINT_SIZE;   // -> 14
 * const uint8_t storedFontFamily = doc["fontFamily"] | (uint8_t)0;      // -> NotoSerif
 * const char* sfn = doc["sdFontFamilyName"] | "";                       // -> cleared
 * copyToField(dictionaryName, doc["dictionaryName"] | "", ...);         // -> cleared
 * frontButtonBack = clamp(doc["frontButtonBack"] | FRONT_HW_BACK, ...); // -> reset
 * ```
 *
 * A minimal "only what changed" upload would therefore quietly reset the user's
 * SD-card font, their dictionary and their remapped front buttons. So the app
 * sends back the exact document it downloaded with the edited keys overwritten:
 * every untouched key, including `settingsRev` and the obfuscated credential
 * blobs this UI never shows, round-trips byte-for-byte as an identity write.
 */
enum class DeviceSettingGroup(val label: String) {
    DISPLAY("Display"),
    READER("Reader"),
    CONTROLS("Controls"),
    SYSTEM("System"),
}

/** One numeric option of a [DeviceSetting.Values] row. */
data class NumOption(val value: Int, val label: String)

sealed class DeviceSetting {
    abstract val key: String
    abstract val label: String
    abstract val group: DeviceSettingGroup
    abstract val help: String?

    /**
     * An enum persisted by index. [options] is ordered by stored value, which is
     * how the firmware's `SettingInfo::Enum` lists are built — the label at
     * index n is what the reader means by n, so the order here is load-bearing.
     */
    data class Choice(
        override val key: String,
        override val label: String,
        override val group: DeviceSettingGroup,
        val options: List<String>,
        override val help: String? = null,
    ) : DeviceSetting()

    /** A 0/1 field. */
    data class Toggle(
        override val key: String,
        override val label: String,
        override val group: DeviceSettingGroup,
        override val help: String? = null,
    ) : DeviceSetting()

    /** A number over a continuous range, rendered as a slider. */
    data class Range(
        override val key: String,
        override val label: String,
        override val group: DeviceSettingGroup,
        val min: Int,
        val max: Int,
        val step: Int,
        val unit: String = "",
        override val help: String? = null,
    ) : DeviceSetting() {
        val steps: List<Int> get() = (min..max step step).toList()
    }

    /**
     * Free text, stored as a string rather than a number.
     *
     * The only one of these so far is the device name, and it is why
     * [DeviceSettingsUi] carries a second, string-keyed map alongside the
     * integer one: every other setting the reader has is an enum index, a
     * bool or a number, and widening the whole pipeline to `Any` to carry one
     * string would make every read site do a type check it does not need.
     */
    data class Text(
        override val key: String,
        override val label: String,
        override val group: DeviceSettingGroup,
        val maxLength: Int,
        val placeholder: String = "",
        override val help: String? = null,
    ) : DeviceSetting()

    /** A number the reader accepts over a range, but only a few values of which
     *  are worth offering (font point sizes, sleep timeouts). */
    data class Values(
        override val key: String,
        override val label: String,
        override val group: DeviceSettingGroup,
        val options: List<NumOption>,
        override val help: String? = null,
    ) : DeviceSetting()
}

object DeviceSettingsSchema {

    /**
     * Every row, in the order it is shown.
     *
     * Keys are the firmware's JSON keys from `src/SettingsList.h`, not the C++
     * field names — `showReaderMenu` is persisted as `tapForReaderMenu`, and
     * that legacy key is the one on the wire.
     *
     * Deliberately absent:
     *  - `orientation` — the X4 Pro is landscape-only and the firmware compiles
     *    the settings row out (`#if FREEINK_DEVICE_X4PRO` in SettingsList.h).
     *  - pairing / trusted-host state, `clockUtcOffsetQ`, `language`,
     *    `keyboardLayouts`, `settingsRev` and the KOReader/OPDS credentials.
     *    They still round-trip untouched inside the document.
     *
     * `clockFormat` IS here: the reader has no way to set it now that its own
     * settings are down to Bluetooth pairing, and the offset is already synced
     * automatically from the phone (`set_time` carries `utc_offset_q`), so
     * 12-vs-24-hour was the one clock decision left with nowhere to live.
     */
    val all: List<DeviceSetting> = listOf(

        // First, and deliberately: it is the one setting that is about WHICH
        // reader this is rather than how it behaves, and it is the label the
        // app shows in the connection pill.
        DeviceSetting.Text(
            "deviceName", "Device name", DeviceSettingGroup.SYSTEM,
            maxLength = 16,
            placeholder = "X4 Pro",
            help = "Shown in this app. Up to 16 characters.",
        ),

        // ----------------------------------------------------------- display
        DeviceSetting.Choice(
            "sleepScreen", "Sleep screen", DeviceSettingGroup.DISPLAY,
            listOf(
                "Dark", "Light", "Custom image", "Book cover",
                "Cover + custom", "None", "Quick resume", "Transparent custom",
            ),
        ),
        DeviceSetting.Choice(
            "sleepScreenCoverMode", "Cover fitting", DeviceSettingGroup.DISPLAY,
            listOf("Fit", "Crop"),
        ),
        DeviceSetting.Choice(
            "sleepScreenCoverFilter", "Cover filter", DeviceSettingGroup.DISPLAY,
            listOf("None", "Black and white", "Inverted B&W"),
        ),
        DeviceSetting.Choice(
            "quickResumeSleepScreen", "Quick resume screen", DeviceSettingGroup.DISPLAY,
            listOf("Never", "After timeout"),
        ),
        DeviceSetting.Choice(
            "hideBatteryPercentage", "Hide battery percentage", DeviceSettingGroup.DISPLAY,
            listOf("Never", "In the reader", "Always"),
        ),
        // `refreshFrequency` is gone: the page-cadence clean refresh it controlled
        // was removed from the firmware. Ghost clearing is driven by how much of
        // the panel has actually changed (`ghostCleanup`), not by a page count.
        DeviceSetting.Choice(
            "ghostCleanup", "Ghost cleanup", DeviceSettingGroup.DISPLAY,
            listOf("Off", "Light (~17 pages)", "Normal (~5 pages)", "Aggressive (~3 pages)"),
            help = "How often the panel does a clean, flashing redraw to clear " +
                "leftover text. Closer together means less ghosting and more flashes.",
        ),
        // `uiTheme` is gone: the Library list is drawn by the Lyra theme alone --
        // covers, tall rows, the Last Read badge and the progress disc are all
        // Lyra code, and the other themes draw rows at their own height while the
        // touch grid still uses the Library's. Switching theme did not restyle
        // that screen, it broke it.
        DeviceSetting.Toggle("fadingFix", "Sunlight fading fix", DeviceSettingGroup.DISPLAY),
        DeviceSetting.Toggle(
            "frontlightRestoreOnWake", "Restore frontlight on wake", DeviceSettingGroup.DISPLAY,
        ),
        DeviceSetting.Toggle(
            "screenInverted", "Dark mode", DeviceSettingGroup.DISPLAY,
            help = "Inverts the whole interface, not just the page.",
        ),

        // ------------------------------------------------------------ reader
        DeviceSetting.Choice(
            "fontFamily", "Font", DeviceSettingGroup.READER,
            listOf("Noto Serif", "Noto Sans"),
            help = "A font installed on the reader's card is chosen on the device; " +
                "this app leaves that choice alone.",
        ),
        DeviceSetting.Values(
            "fontSize", "Font size", DeviceSettingGroup.READER,
            // A point size since firmware 1.5, not a Small/Medium/Large slot.
            // BUILTIN_READER_POINT_SIZES in src/ReaderFontSizes.h is {12,14,16,18};
            // an SD-card family can offer others, and one already on the reader
            // is added to this list rather than being rounded away.
            listOf(
                NumOption(12, "12 pt"), NumOption(14, "14 pt"),
                NumOption(16, "16 pt"), NumOption(18, "18 pt"),
            ),
        ),
        DeviceSetting.Choice(
            "lineSpacing", "Line spacing", DeviceSettingGroup.READER,
            listOf("Tight", "Normal", "Wide", "Extra wide"),
        ),
        DeviceSetting.Range(
            "screenMargin", "Page margin", DeviceSettingGroup.READER,
            min = 5, max = 40, step = 5, unit = "px",
        ),
        DeviceSetting.Choice(
            "paragraphAlignment", "Alignment", DeviceSettingGroup.READER,
            listOf("Justified", "Left", "Centred", "Right", "Book style"),
        ),
        DeviceSetting.Toggle(
            "embeddedStyle", "Use the book's own styling", DeviceSettingGroup.READER,
        ),
        DeviceSetting.Toggle("focusReadingEnabled", "Focus reading", DeviceSettingGroup.READER),
        DeviceSetting.Toggle("hyphenationEnabled", "Hyphenation", DeviceSettingGroup.READER),
        DeviceSetting.Toggle(
            "extraParagraphSpacing", "Extra paragraph spacing", DeviceSettingGroup.READER,
        ),
        DeviceSetting.Toggle(
            "textAntiAliasing", "Smooth text", DeviceSettingGroup.READER,
            help = "Smoother glyphs, and the panel is fully redrawn on every page " +
                "turn -- so pages stay clean, with a visible flash each turn. " +
                "Off is flicker-free but relies on Ghost cleanup to clear " +
                "leftover text every few pages.",
        ),
        DeviceSetting.Choice(
            "imageRendering", "Images", DeviceSettingGroup.READER,
            listOf("Display", "Placeholder", "Suppress"),
        ),
        DeviceSetting.Choice(
            "readerMenuStyle", "Reader menu style", DeviceSettingGroup.READER,
            listOf("List", "Toolbar"),
        ),

        // ---------------------------------------------------------- controls
        DeviceSetting.Choice(
            "sideButtonLayout", "Side buttons", DeviceSettingGroup.CONTROLS,
            listOf("Previous / Next", "Next / Previous", "Disabled"),
        ),
        DeviceSetting.Choice(
            "touchReaderControls", "Touch page turns", DeviceSettingGroup.CONTROLS,
            listOf("Off", "On", "Swipe", "Inverted tap"),
        ),
        // The C++ field is showReaderMenu; the persisted key has always been
        // tapForReaderMenu and old saves map 0 = Off, 1 = Tap.
        DeviceSetting.Choice(
            "tapForReaderMenu", "Open the reader menu with", DeviceSettingGroup.CONTROLS,
            listOf("Off", "Tap", "Swipe up"),
        ),
        DeviceSetting.Choice(
            "longPressButtonBehavior", "Long-press a page button", DeviceSettingGroup.CONTROLS,
            listOf("Off", "Skip chapter", "Change orientation"),
        ),
        DeviceSetting.Choice(
            "longPressMenuFunction", "Long-press the menu button", DeviceSettingGroup.CONTROLS,
            listOf("KOReader sync", "Disabled", "Bookmark", "Dictionary", "Reader menu"),
        ),
        DeviceSetting.Choice(
            "shortPwrBtn", "Tap the power button", DeviceSettingGroup.CONTROLS,
            listOf(
                "Ignore", "Sleep", "Turn the page", "Force refresh",
                "Footnotes", "Confirm", "Control centre",
            ),
        ),
        // `frontButtonFollowOrientation` is gone: the X4 Pro is landscape-only and
        // the firmware compiles the orientation setting out entirely.
        DeviceSetting.Toggle(
            "pwrBtnFootnoteBack", "Power button closes footnotes", DeviceSettingGroup.CONTROLS,
        ),
        DeviceSetting.Toggle(
            "backShortToFileBrowser", "Back goes to the file browser", DeviceSettingGroup.CONTROLS,
        ),

        // ------------------------------------------------------------ system
        DeviceSetting.Choice(
            "clockFormat", "Clock", DeviceSettingGroup.SYSTEM,
            listOf("24-hour", "12-hour"),
            help = "How the time reads in the status bar, on the reader and everywhere else.",
        ),
        DeviceSetting.Values(
            "sleepTimeoutMinutes", "Sleep after", DeviceSettingGroup.SYSTEM,
            listOf(
                NumOption(1, "1 minute"), NumOption(5, "5 minutes"),
                NumOption(10, "10 minutes"), NumOption(15, "15 minutes"),
                NumOption(30, "30 minutes"),
            ),
            help = "Minutes of inactivity before the reader sleeps.",
        ),
        DeviceSetting.Toggle("showHiddenFiles", "Show hidden files", DeviceSettingGroup.SYSTEM),
        DeviceSetting.Toggle(
            "autoInstallFirmware", "Install firmware updates automatically", DeviceSettingGroup.SYSTEM,
            help = "No prompt: a new build installs the next time the reader sleeps, then it goes back to sleep.",
        ),
        // `removeReadBooksFromRecents` is gone: the Recents screen it curates is no
        // longer reachable -- the Library IS the list of books now.
                // `moveFinishedToReadFolder` is gone, and this one was actively harmful:
        // offline is a two-way mirror now, so a book that moves itself to /Read
        // desynchronises it -- the reader drops it from the shelf while the app
        // still believes it was sent, and the next prune goes looking for a file
        // that walked away.
            )

    val groups: List<DeviceSettingGroup> = DeviceSettingGroup.entries.toList()

    fun of(group: DeviceSettingGroup): List<DeviceSetting> = all.filter { it.group == group }

    /**
     * The exposed keys the reader actually sent, with their values.
     *
     * A key the document does not carry is left out rather than defaulted: the
     * firmware compiles several rows out per board (`frontlightRestoreOnWake`
     * without a frontlight, `readerMenuStyle` without a touchscreen), and a row
     * the reader has no field for is one this app must not invent a value for.
     */
    fun readValues(doc: JSONObject): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (s in all) {
            when (val v = doc.opt(s.key)) {
                is Number -> out[s.key] = v.toInt()
                is Boolean -> out[s.key] = if (v) 1 else 0
                else -> Unit
            }
        }
        return out
    }

    /**
     * The document to upload: everything the reader sent, with [edits] applied.
     *
     * See the note at the top of this file for why this is the full document
     * and not a delta. `settingsRev` comes along untouched, which is what the
     * firmware's migration step needs to see.
     */
    fun applyEdits(
        doc: JSONObject,
        edits: Map<String, Int>,
        textEdits: Map<String, String> = emptyMap(),
    ): JSONObject {
        val out = JSONObject(doc.toString())
        for ((k, v) in edits) out.put(k, v)
        for ((k, v) in textEdits) out.put(k, v)
        return out
    }

    /** The string-valued settings the reader sent. See [DeviceSetting.Text]. */
    fun readTextValues(doc: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (s in all) {
            if (s !is DeviceSetting.Text) continue
            val v = doc.opt(s.key)
            if (v is String) out[s.key] = v
        }
        return out
    }
}
