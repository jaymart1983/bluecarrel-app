package com.jmart.x4sync.data

import org.json.JSONObject

/** One entry from the CWA OPDS catalog. */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val downloadUrl: String?,
    val coverUrl: String?,
    val filename: String,
    /**
     * The blurb, from OPDS `<summary>` or `<content>`. Empty when Calibre has
     * no comment for the book. The Store truncates it per request: 160 bytes
     * for a list row, 1024 for the detail view.
     */
    val description: String = "",
    /** `epub`, `pdf`, ... Derived from the acquisition link's MIME type. */
    val format: String = "epub",
    /** Bytes, from the acquisition link's `length` attribute. 0 when absent. */
    val sizeBytes: Long = 0L,
    // --- richer metadata, detail view only -----------------------------------
    // Calibre publishes all of this in the OPDS feed. Every field is optional:
    // an OPDS server that omits one leaves it empty rather than the entry
    // failing to parse.
    /** `dc:publisher`. */
    val publisher: String = "",
    /** `dc:language`, as the feed gives it (e.g. "eng"). */
    val language: String = "",
    /** Publication date, `dc:issued` or `published`, trimmed to the year when longer. */
    val published: String = "",
    /** Calibre tags, from `<category label=…>`. */
    val tags: List<String> = emptyList(),
    /** Calibre series name, from the `calibre:series` extension. */
    val series: String = "",
    /** Position within [series]; 0 when absent. Whole numbers render without a decimal. */
    val seriesIndex: Double = 0.0,
    /**
     * Calibre's last-modified time for this book: the OPDS entry's `<updated>`.
     *
     * Compared against the value remembered when the book was saved offline, it
     * is how the app notices that Calibre changed a book it already holds -- a
     * metadata edit, a cover swap, a baseline re-encode. Kept as the raw string
     * the feed sent: it is only ever compared for equality, never parsed.
     * Empty for feeds that omit it and for sidecars written before this existed.
     */
    val updated: String = "",
) {
    /**
     * The kosync document key that survives Calibre rewriting the file.
     *
     * kosync stores progress under ANY document string (CWA kosync.py:
     * `KOSyncProgress.document == document`, keys must be non-empty, colon-free,
     * <= 255 chars). The content hash KOReader uses is only needed for CWA to
     * link a row to a library book in its web UI. Keyed by hash alone, every
     * metadata edit, cover swap or baseline re-encode moved the key and orphaned
     * the position; Calibre's book UUID does not move.
     *
     * CWA publishes it as the OPDS entry id, `urn:uuid:<uuid>`. The prefix is
     * stripped because colons are reserved. Null for feeds that do not use the
     * UUID form (and sidecars from them), which then fall back to the hash.
     */
    val progressKey: String?
        get() {
            if (!id.startsWith("urn:uuid:")) return null
            val uuid = id.removePrefix("urn:uuid:").trim()
            return uuid.takeIf { it.isNotEmpty() && !it.contains(':') && it.length <= 255 }
        }

    /** "Foundation #2" — or an empty string when the book is not in a series. */
    val seriesLabel: String
        get() = when {
            series.isBlank() -> ""
            seriesIndex <= 0.0 -> series
            seriesIndex == Math.floor(seriesIndex) -> "$series #${seriesIndex.toInt()}"
            else -> "$series #$seriesIndex"
        }
}

/**
 * Asks whether a freshly saved book should resume where it was left off.
 *
 * Raised only when kosync actually holds a position for the book, so it never
 * interrupts a first read. [percentLabel] is pre-formatted by [Progress] so the
 * dialog and the row cannot disagree about how far along the book is.
 */
data class ResumePrompt(
    val title: String,
    val filename: String,
    val percentLabel: String,
)

/** Reading position returned by kosync. */
data class Progress(
    val document: String,
    val percentage: Float,
    val device: String,
    /**
     * When CWA RECEIVED this row -- not when the position was set.
     *
     * `cps/progress_syncing/protocols/kosync.py:665` stamps
     * `datetime.now(timezone.utc)` on every PUT and discards whatever the
     * client sent. A backfill import therefore lands hundreds of rows all
     * stamped "now", including positions last touched years earlier. Comparing
     * a real save time against this would make every one of those look NEWER
     * than a fresh save. Use [setAt] for age; this is a fallback only.
     */
    val timestamp: Long,
    /** The raw `progress` payload. JSON for rows either side of this project writes. */
    val raw: String = "",
) {
    /**
     * Rendered to match the reader, and with a decimal where whole percent is
     * too coarse to be useful.
     *
     * This TRUNCATED, while the reader rounds. So a book the device showed as
     * "1%" appeared in the app as "0%" -- the same underlying 0.0099, rendered
     * by two different rules. The wire value carries four decimals
     * (BookLibraryIndex writes `round(percent * 10000) / 10000`), so the
     * precision was always there; only the label threw it away.
     *
     * One decimal below 10%: at the start of a book whole percent is a very
     * coarse unit, and "0%" for a book you have demonstrably started reads as
     * "nothing was recorded" rather than as "not far in".
     */
    val percentLabel: String
        get() {
            val p = percentage * 100f
            // One decimal EVERYWHERE, not just below 10%. The threshold version
            // meant some rows showed a decimal and others did not, which reads
            // as inconsistency rather than as precision-where-it-matters -- and
            // a reader cannot tell whether "42%" is exact or rounded while
            // "0.9%" sitting next to it clearly is not.
            return if (p <= 0f) "0%" else String.format(java.util.Locale.getDefault(), "%.1f%%", p)
        }

    /** Which client wrote this row: "reader" for us, or "" when unknown. */
    val source: String get() = payload()?.optString("src").orEmpty()

    /**
     * When the position was actually set, from the payload; 0 when unknown.
     *
     * Implausibly old values are reported as unknown rather than trusted: a
     * reader whose RTC has never been set stamps from its firmware build epoch,
     * and a position "set" before this project existed is a broken clock, not a
     * very patient reader.
     */
    val setAt: Long get() = payload()?.optLong("set_at", 0L)?.takeIf { it >= PLAUSIBLE_EPOCH } ?: 0L

    /** [setAt] when it is known, else the receive time. The conflict rule's input. */
    val ageStamp: Long get() = setAt.takeIf { it > 0 } ?: timestamp

    /** The parsed payload, for callers that need fields beyond src/set_at. */
    fun payloadJson(): org.json.JSONObject? = payload()

    private fun payload(): org.json.JSONObject? =
        raw.takeIf { it.startsWith("{") }?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }

    companion object {
        /** 2020-01-01. Below this, a clock is wrong. Mirrors the reader's own check. */
        const val PLAUSIBLE_EPOCH = 1577836800L
    }
}

/**
 * A book plus whatever we know about it.
 *
 * [sentFromThisApp] is deliberately not called `onDevice`. The BLE protocol
 * has no list-files operation, so this app cannot see what is actually on the
 * reader; all it can honestly say is "this phone uploaded it at some point,
 * and the reader confirmed the commit". A book side-loaded over USB, or
 * deleted on the reader afterwards, will not be reflected here.
 */
data class BookRow(
    val book: Book,
    val progress: Progress? = null,
    val sentFromThisApp: Boolean = false,
    val cached: Boolean = false,
    /**
     * Removed here, still waiting for the reader to let go.
     *
     * The offline copy is deliberately still on disk -- that is what lets this
     * row keep drawing at all -- so it cannot be inferred from [cached]. The row
     * greys out and says what it is waiting for instead of disappearing and
     * leaving the pending deletion invisible.
     */
    val pendingRemoval: Boolean = false,
)

/** Identity this phone presents to the reader. Stored only after a verified pairing. */
data class HostIdentity(
    val hostId: String,
    val hostName: String,
    /** 64 lowercase hex chars. The HMAC key is the 32 bytes it decodes to. */
    val secret: String,
    /** The reader's device_id at pairing. A reader reporting another id is a different reader. */
    val deviceId: String? = null,
    /** The reader's Bluetooth address at pairing. Scans and connects go only to it. */
    val address: String? = null,
)

/** Where this app stands with the reader, for the UI. */
enum class PairingState {
    /** No pairing stored. */
    UNPAIRED,

    /** Paired: identity, reader id and address stored. */
    TRUSTED,
}

/**
 * Where the reader link is, as one value.
 *
 * Deliberately a single enum rather than derived from `connected`,
 * `authorized` and `permissionsGranted`. Those booleans cannot tell "scanning"
 * from "connecting", cannot say *why* a connection failed, and have no way to
 * express "the code is in flight" — situations that each need a different
 * thing from the user.
 */
enum class LinkStage {
    /** Nothing attempted yet. */
    IDLE,

    /** The phone's Bluetooth radio is off. Only the user can fix this. */
    BLUETOOTH_OFF,

    /** Nearby devices (API 31+) or fine location (29/30) not granted. */
    NEEDS_PERMISSION,

    /** Listening for the service UUID. */
    SCANNING,

    /** Found something; opening GATT, negotiating MTU, discovering services. */
    CONNECTING,

    /** Not paired. The user opens Settings on the reader, then taps Pair. */
    NEEDS_PAIRING,

    /** Android is bonding; its system dialog takes the passkey the reader shows. */
    BONDING,

    /** A `pair` or `hello` is in flight. */
    PAIRING,

    /** Connected and authorised. */
    CONNECTED,

    /** The last attempt failed. [LinkStatus.reason] says what to do. */
    FAILED,
}

/**
 * [LinkStage] plus the two strings the UI shows.
 *
 * [reason] is the headline for a problem and [hint] is what to do about it.
 * They are built where the failure is understood — in the ViewModel, from
 * [BleClient.Reason] — rather than in the composable, so the UI never has to
 * guess at a message from a boolean.
 */
data class LinkStatus(
    val stage: LinkStage = LinkStage.IDLE,
    val reason: String? = null,
    val hint: String? = null,
) {
    val busy: Boolean
        get() = stage == LinkStage.SCANNING || stage == LinkStage.CONNECTING ||
            stage == LinkStage.PAIRING || stage == LinkStage.BONDING
}

/**
 * A question the reader has asked us, carried in the `status` notification's
 * `pending` object.
 *
 * The reader is the GATT peripheral and cannot call out, so the Store inverts
 * the usual direction by putting its request here. We answer with an ordinary
 * upload naming the same [req]. The reader has at most one request outstanding
 * and refuses an answer naming any other id as `stale request`.
 *
 * [timeoutMs] is advertised deliberately: an app that knows the deadline can
 * decline early with `catalog_error` rather than let the reader sit out the
 * whole window staring at "Asking your phone".
 */
data class PendingRequest(
    val req: Int,
    /** `catalog_page`, `catalog_detail` or `catalog_fetch`. */
    val op: String,
    val offset: Int = 0,
    val limit: Int = 6,
    /** The opaque handle we published in a page. Detail and fetch only. */
    val id: String? = null,
    /** The filename the reader expects a `catalog_fetch` answer to carry. */
    val name: String? = null,
    val thumbWidth: Int = 0,
    val thumbHeight: Int = 0,
    val descMax: Int = 160,
    val timeoutMs: Long = 20_000L,
) {
    val isFetch: Boolean get() = op == "catalog_fetch"
    val isDetail: Boolean get() = op == "catalog_detail"
    val isPage: Boolean get() = op == "catalog_page"

    companion object {
        fun parse(j: JSONObject): PendingRequest? {
            val req = j.optInt("req", 0)
            val op = j.optString("op").ifBlank { return null }
            if (req <= 0) return null
            if (op != "catalog_page" && op != "catalog_detail" && op != "catalog_fetch") return null
            return PendingRequest(
                req = req,
                op = op,
                offset = j.optInt("offset", 0).coerceAtLeast(0),
                limit = j.optInt("limit", 6).coerceIn(1, 6),
                id = j.optString("id").ifBlank { null },
                name = j.optString("name").ifBlank { null },
                thumbWidth = j.optInt("thumb_w", 0),
                thumbHeight = j.optInt("thumb_h", 0),
                descMax = j.optInt("desc_max", if (op == "catalog_detail") 1024 else 160)
                    .coerceIn(0, 1024),
                timeoutMs = j.optLong("timeout_ms", if (op == "catalog_fetch") 45_000L else 20_000L)
                    .coerceIn(1_000L, 120_000L),
            )
        }
    }
}

/**
 * The reader's `status` characteristic, decoded.
 *
 * Field names are the firmware's own; unknown ones are ignored rather than
 * guessed at. `received` / `sent` / `written` are the three shapes of
 * progress counter the firmware uses depending on the operation.
 */
data class DeviceStatus(
    val state: String?,
    val error: String?,
    val protocolVersion: Int?,
    val firmwareName: String?,
    val deviceId: String?,
    val deviceNonce: String?,
    val hasTrustedHost: Boolean,
    /**
     * The reader's name for the host it just accepted, or null.
     *
     * A STRING on the wire -- the host's display name -- not a flag. It was
     * being read with optBoolean, which returns the fallback for any string
     * that is not literally "true", so it was permanently false: the reader
     * logged "trusted host 'X4 Sync' accepted" while the app decided silent
     * auth had failed and asked for the six-digit code.
     */
    val trustedHostName: String?,
    val paired: Boolean,
    val pairing: String?,
    val uploadKinds: List<String>,
    val downloadKinds: List<String>,
    val firmwareOtaSupported: Boolean,
    val resumeSupported: Boolean,
    val transferName: String?,
    val size: Long?,
    val received: Long?,
    val sent: Long?,
    val written: Long?,
    /** Present only while the Store screen is the one open on the reader. */
    val mode: String?,
    val storeSupported: Boolean,
    /**
     * The Store's request channel. Note that a status carrying this drops
     * `firmware_name`, `device_id`, `device_nonce`, `upload_kinds` and the rest
     * — a notification is capped at ATT_MTU-3 bytes and the request is the
     * field that has to survive. Read the characteristic if you need those.
     */
    val pending: PendingRequest?,
    /**
     * The reader's library fingerprint: book count and a hash of
     * name+size+mtime, from BookLibraryIndex::Fingerprint on the device.
     *
     * Answers "is your idea of my library still right?" without sending the
     * library. Null when the reader has not computed one yet. A value that
     * differs from the last one seen is the cue to run a full mirror; a value
     * that matches means one can be skipped entirely.
     */
    val libraryBooks: Int?,
    val libraryHash: Long?,
    /** How far through the open book the reader is, 0..1. Null when unknown. */
    val bookPercent: Float?,
    /** Filename the [bookPercent] belongs to. Shed first when the notify is tight. */
    val bookFilename: String?,
    /**
     * The reader's last word before deep sleep.
     *
     * Without it a dropped link is ambiguous -- asleep, out of range and
     * crashed all look identical from here. With it, the last state received is
     * known to be accurate rather than merely stale.
     */
    val sleeping: Boolean,
    /** A book is open on the reader right now ([bookFilename] names it). Older firmware never says. */
    val bookOpen: Boolean = false,
    /** The reader's Settings screen is open and will take a new bond and `pair`. */
    val pairingWindow: Boolean = false,
    /** HMAC the reader returns after a hello. Checked by BleClient, never read as a flag. */
    val readerProof: String? = null,
    /** Why the reader refused a hello or pair. */
    val authError: String? = null,
    val raw: String,
) {
    /** Both halves of the fingerprint, or null when the reader did not send one. */
    val libraryFingerprint: Pair<Int, Long>?
        get() = libraryBooks?.let { n -> libraryHash?.let { h -> n to h } }
    /** The reader named a trusted host. Not proof of authentication on its own. */
    val trustedHost: Boolean get() = !trustedHostName.isNullOrBlank()

    companion object {
        fun parse(json: String): DeviceStatus? = runCatching {
            val j = JSONObject(json)
            DeviceStatus(
                state = j.optStringOrNull("state"),
                error = j.optStringOrNull("error"),
                protocolVersion = if (j.has("protocol_version")) j.optInt("protocol_version") else null,
                firmwareName = j.optStringOrNull("firmware_name"),
                deviceId = j.optStringOrNull("device_id"),
                deviceNonce = j.optStringOrNull("device_nonce"),
                hasTrustedHost = j.optBoolean("has_trusted_host", false),
                trustedHostName = j.optStringOrNull("trusted_host"),
                paired = j.optBoolean("paired", false),
                pairing = j.optStringOrNull("pairing"),
                uploadKinds = j.optStringList("upload_kinds"),
                downloadKinds = j.optStringList("download_kinds"),
                firmwareOtaSupported = j.optBoolean("firmware_ota_supported", false),
                resumeSupported = j.optBoolean("resume_supported", false),
                transferName = j.optStringOrNull("name"),
                size = j.optLongOrNull("size"),
                received = j.optLongOrNull("received"),
                sent = j.optLongOrNull("sent"),
                written = j.optLongOrNull("written"),
                mode = j.optStringOrNull("mode"),
                storeSupported = j.optBoolean("store_supported", false),
                pending = j.optJSONObject("pending")?.let { PendingRequest.parse(it) },
                libraryBooks = if (j.has("lib_n")) j.optInt("lib_n") else null,
                libraryHash = if (j.has("lib_h")) j.optLong("lib_h") else null,
                bookPercent = if (j.has("pct")) j.optDouble("pct", -1.0).toFloat().takeIf { it >= 0f } else null,
                bookFilename = j.optStringOrNull("book"),
                sleeping = j.optBoolean("sleeping", false),
                bookOpen = j.optBoolean("open", false),
                pairingWindow = j.optBoolean("pairing_window", false),
                readerProof = j.optStringOrNull("reader_proof"),
                authError = j.optStringOrNull("auth_error"),
                raw = json,
            )
        }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key, -1L).takeIf { it >= 0 } else null

        private fun JSONObject.optStringList(key: String): List<String> {
            val arr = optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        }
    }
}

/**
 * The newest reader firmware on the update page, from its `firmware.json`.
 *
 * Written by the build host next to the image it describes. [version] is the
 * build stamp the firmware reports through its `about` download, so the two
 * compare directly; stamps are yyyyMMdd.HHmm and so also sort.
 */
data class FirmwareManifest(
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
    /** DER ECDSA P-256 signature over "X4FW1|version|sha256", lowercase hex. Passed to the reader. */
    val signature: String,
) {
    companion object {
        fun parse(json: String): FirmwareManifest {
            val j = org.json.JSONObject(json)
            val signature = j.optString("signature", "").trim().lowercase()
            require(signature.isNotEmpty()) { "Unsigned firmware" }
            val m = FirmwareManifest(
                j.getString("version"),
                j.getString("file"),
                j.getLong("size"),
                j.getString("sha256").lowercase(),
                signature,
            )
            require(
                m.version.matches(Regex("\\d{8}\\.\\d{4}")) &&
                    m.file.matches(Regex("[A-Za-z0-9._-]+\\.bin")) &&
                    m.sha256.matches(Regex("[0-9a-f]{64}")) &&
                    m.size > 0 && m.size <= HttpGuard.FIRMWARE_MAX &&
                    m.signature.length <= 256 && m.signature.length % 2 == 0 &&
                    m.signature.matches(Regex("[0-9a-f]+"))
            ) {
                "malformed firmware.json"
            }
            return m
        }
    }
}
