package dev.bluecarrel.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore("bluecarrel")

/**
 * Network settings only. The reader itself is reached over BLE and has no
 * host or port, and it chooses the upload directory from the transfer `kind`
 * (book -> /Books, bmp -> /Pictures).
 *
 * One server, one account. Calibre-Web Automated serves the OPDS catalogue
 * and the KOReader sync API from the same origin, and the sync API
 * authenticates against CWA's own user accounts, so there is nothing left to
 * configure separately. The two sub-paths are derived, not stored.
 */
data class Config(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /**
     * Where new builds of this app and of the reader firmware are published:
     * the page that holds `firmware.json`.
     *
     * Blank means [DEFAULT_UPDATES_URL] -- see [effectiveUpdatesUrl]. Stored
     * separately from [serverUrl] because the download page is a different
     * service from Calibre and need not live on the same host.
     */
    val updatesUrl: String = "",
    /** Send a newer reader build in the background as soon as one is seen. */
    val autoDownloadFirmware: Boolean = false,
) {
    /**
     * The update page in use: [updatesUrl] trimmed, [DEFAULT_UPDATES_URL] when
     * blank, and blank (no update checks) when the saved value is not https.
     */
    val effectiveUpdatesUrl: String
        get() {
            val v = updatesUrl.trim()
            return when {
                v.isEmpty() -> DEFAULT_UPDATES_URL
                HttpGuard.isHttps(v) -> v
                else -> ""
            }
        }

    /** A usable server: set and https. A saved http:// value counts as not configured. */
    val serverConfigured: Boolean get() = HttpGuard.isHttps(serverUrl)

    /** [HTTPS_REQUIRED] when either URL is set to something other than https. */
    val urlProblem: String?
        get() = if ((serverUrl.isNotBlank() && !HttpGuard.isHttps(serverUrl)) ||
            (updatesUrl.isNotBlank() && !HttpGuard.isHttps(updatesUrl))
        ) HTTPS_REQUIRED else null

    /** `<base>` with any trailing slashes removed. */
    val base: String get() = serverUrl.trim().trimEnd('/')

    val opdsUrl: String get() = "$base/opds"
    val kosyncUrl: String get() = "$base/kosync"
}

/** Fallback name for this phone on the reader, when the phone reports none. */
const val APP_HOST_NAME = "Bluecarrel"

/** Update page used when the field is blank. GitHub redirects if the repository is renamed. */
const val DEFAULT_UPDATES_URL = "https://github.com/jaymart1983/bluecarrel-firmware/releases/latest/download/"

/** Human-facing release list for the default update page ("Open update page"). */
const val DEFAULT_UPDATES_PAGE = "https://github.com/jaymart1983/bluecarrel-firmware/releases"

/** The one message for a server or update page that is not https. */
const val HTTPS_REQUIRED = "Use an https:// address"

/** The reader keeps at most this many bytes of a host name (BleLink BLE_HOST_NAME_MAX_BYTES). */
private const val READER_HOST_NAME_MAX = 48

/**
 * This phone's own name -- the one set in Android's About phone -- as the reader
 * will show it under Settings > Bluetooth. Falls back to manufacturer + model, then
 * to the app name.
 *
 * Cleaned to what the reader stores: it replaces anything outside printable ASCII
 * with '?', so curly quotes (common in names like "Sam’s Pixel") are straightened
 * first and any other non-ASCII character is dropped rather than shown as a
 * question mark.
 */
fun phoneDisplayName(context: Context): String {
    val raw = runCatching {
        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .takeIf { it.isNotBlank() }
        ?: APP_HOST_NAME
    val cleaned = raw
        .replace('\u2019', '\'').replace('\u2018', '\'')
        .replace('\u201C', '"').replace('\u201D', '"')
        .filter { it.code in 32..126 }
        .trim()
    val bytes = if (cleaned.length > READER_HOST_NAME_MAX) cleaned.take(READER_HOST_NAME_MAX).trim() else cleaned
    return bytes.ifBlank { APP_HOST_NAME }
}

class SettingsStore(private val context: Context) {

    private object K {
        val serverUrl = stringPreferencesKey("server_url")
        val username = stringPreferencesKey("server_user")
        val password = stringPreferencesKey("server_pass")
        val updatesUrl = stringPreferencesKey("updates_url")
        val autoDownloadFirmware = booleanPreferencesKey("auto_download_firmware")

        // Superseded by the three keys above. Read once so an existing install
        // keeps working, then dropped on the next save.
        // See [baseFromLegacyOpdsUrl].
        val legacyOpdsUrl = stringPreferencesKey("opds_url")
        val legacyOpdsUser = stringPreferencesKey("opds_user")
        val legacyOpdsPass = stringPreferencesKey("opds_pass")
    }

    val config: Flow<Config> = context.dataStore.data.map { p ->
        val d = Config()
        Config(
            serverUrl = p[K.serverUrl] ?: baseFromLegacyOpdsUrl(p[K.legacyOpdsUrl]) ?: d.serverUrl,
            username = p[K.username] ?: p[K.legacyOpdsUser] ?: d.username,
            password = p[K.password] ?: p[K.legacyOpdsPass] ?: d.password,
            updatesUrl = p[K.updatesUrl] ?: d.updatesUrl,
            autoDownloadFirmware = p[K.autoDownloadFirmware] ?: d.autoDownloadFirmware,
        )
    }

    suspend fun save(c: Config) {
        context.dataStore.edit { p ->
            p[K.serverUrl] = c.base
            p[K.username] = c.username
            p[K.password] = c.password
            p[K.updatesUrl] = c.updatesUrl
            p[K.autoDownloadFirmware] = c.autoDownloadFirmware
            // The old shape can never be authoritative again; leaving it behind
            // would only invite a future reader to resurrect it.
            p.remove(K.legacyOpdsUrl); p.remove(K.legacyOpdsUser); p.remove(K.legacyOpdsPass)
        }
    }

    /**
     * The legacy `opdsUrl` was the full catalogue URL, so the stored value ends
     * in `/opds`. Strip that to recover the origin [Config.serverUrl] wants.
     * Credentials migrate from the OPDS pair: those are the CWA account, which
     * is also what the sync API authenticates against.
     */
    private fun baseFromLegacyOpdsUrl(raw: String?): String? {
        val v = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        return v.removeSuffix("/opds").trimEnd('/').takeIf { it.isNotEmpty() }
    }
}

/**
 * The pairing: the host id + secret this phone presents to the reader, and the
 * reader's device id and Bluetooth address.
 *
 * Written once, after `pair` and a hello whose reader_proof verified. A record
 * without an address is a v1 (six-digit code) pairing, which a v2 reader no
 * longer knows, and reads as unpaired.
 *
 * The secret is a plain DataStore string, excluded from backup (allowBackup is
 * off). It rides a bonded, encrypted link and is no better or worse protected
 * than the server password beside it.
 */
class PairingStore(private val context: Context) {

    private object K {
        val hostId = stringPreferencesKey("ble_host_id")
        val hostName = stringPreferencesKey("ble_host_name")
        val secret = stringPreferencesKey("ble_host_secret")
        val deviceId = stringPreferencesKey("ble_device_id")
        val address = stringPreferencesKey("ble_device_address")
        // v1 only. Removed on save and clear.
        val legacyTrusted = booleanPreferencesKey("ble_trusted")
        // The reader's own name, mirrored here so the connection pill can show
        // it at launch instead of after the first settings read.
        val deviceName = stringPreferencesKey("ble_device_name")
    }

    val deviceName: Flow<String> = context.dataStore.data.map { p -> p[K.deviceName] ?: "" }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { p ->
            if (name.isBlank()) p.remove(K.deviceName) else p[K.deviceName] = name.take(16)
        }
    }

    val identity: Flow<HostIdentity?> = context.dataStore.data.map { p ->
        val id = p[K.hostId]
        val secret = p[K.secret]
        val address = p[K.address]
        if (id.isNullOrBlank() || secret.isNullOrBlank() || address.isNullOrBlank()) null
        else HostIdentity(
            hostId = id,
            // The phone's current name, not whatever was stored at pairing: the reader
            // takes it from every hello, so renaming the phone renames it there too.
            hostName = phoneDisplayName(context),
            secret = secret,
            deviceId = p[K.deviceId],
            address = address,
        )
    }

    suspend fun load(): HostIdentity? = identity.first()

    /** A brand new identity, not yet stored. Persist it only once the reader has proved itself. */
    fun mint(hostName: String = phoneDisplayName(context)): HostIdentity = HostIdentity(
        hostId = UUID.randomUUID().toString(),
        hostName = hostName,
        secret = BleClient.newHostSecret(),
    )

    suspend fun save(identity: HostIdentity) {
        context.dataStore.edit { p ->
            p[K.hostId] = identity.hostId
            p[K.hostName] = identity.hostName
            p[K.secret] = identity.secret
            val deviceId = identity.deviceId
            if (deviceId != null) p[K.deviceId] = deviceId else p.remove(K.deviceId)
            val address = identity.address
            if (address != null) p[K.address] = address else p.remove(K.address)
            p.remove(K.legacyTrusted)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { p ->
            p.remove(K.hostId); p.remove(K.hostName); p.remove(K.secret)
            p.remove(K.deviceId); p.remove(K.address); p.remove(K.legacyTrusted); p.remove(K.deviceName)
        }
    }
}

/**
 * Books whose reader copy must be REPLACED on the next send, not skipped.
 *
 * A Calibre update downloads a new copy and hands it to the mirror. An ordinary
 * send to a name the reader already holds comes back "exists", which the app
 * rightly reads as "already there" -- so without this, an update would arrive as
 * "already on the reader" while the reader kept the old copy. Persisted because
 * the send can happen after an app restart, and falling back to the ordinary
 * send at that point would silently strand the old copy on the card.
 */
class ReplaceOnSendStore(private val context: Context) {

    private fun key(deviceId: String?) =
        stringSetPreferencesKey("replace_on_send_" + (deviceId ?: "unknown"))

    suspend fun load(deviceId: String?): Set<String> =
        context.dataStore.data.map { it[key(deviceId)] ?: emptySet() }.first()

    suspend fun add(deviceId: String?, filename: String) {
        context.dataStore.edit { p -> val k = key(deviceId); p[k] = (p[k] ?: emptySet()) + filename }
    }

    suspend fun forget(deviceId: String?, filename: String) {
        context.dataStore.edit { p -> val k = key(deviceId); p[k] = (p[k] ?: emptySet()) - filename }
    }

    /** Whether the one-time full-shelf replace has been queued for this reader. */
    private fun resentKey(deviceId: String?) = booleanPreferencesKey("resent_all_v2_" + (deviceId ?: "unknown"))

    suspend fun resentAll(deviceId: String?): Boolean =
        context.dataStore.data.map { it[resentKey(deviceId)] ?: false }.first()

    suspend fun markResentAll(deviceId: String?) {
        context.dataStore.edit { it[resentKey(deviceId)] = true }
    }
}

/**
 * Books the user chose to restart, despite kosync holding a position.
 *
 * Saving a book the user has read before offers to resume at the saved
 * percentage. If they choose the beginning instead, that choice has to OUTLIVE
 * the save: the position sync pushes the server's position to the reader on its
 * own schedule, and the forward-only rule means a 43% server row beats the
 * reader's page one every time. Without this the reader would be dragged back
 * to 43% within seconds of the user asking for a fresh start.
 *
 * An entry is dropped as soon as the reader reports a position of its own for
 * that book -- at that point the user has actually read something, the reader
 * is the newer authority, and normal syncing resumes.
 */
class StartFreshStore(private val context: Context) {

    private fun key(deviceId: String?) =
        stringSetPreferencesKey("start_fresh_" + (deviceId ?: "unknown"))

    suspend fun load(deviceId: String?): Set<String> =
        context.dataStore.data.map { it[key(deviceId)] ?: emptySet() }.first()

    suspend fun add(deviceId: String?, filename: String) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            p[k] = (p[k] ?: emptySet()) + filename
        }
    }

    suspend fun forget(deviceId: String?, filename: String) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            p[k] = (p[k] ?: emptySet()) - filename
        }
    }
}

/**
 * A resume the user chose that the reader has not confirmed yet.
 *
 * The position is what the reader will be sent: [percentage] always, and the
 * spine jump ([spine], [spineFraction], [spineCount]) the reader needs to act
 * on it. [chosenAt] is when the user answered, in epoch seconds.
 *
 * [appliedAt] is 0 while owed. Once the reader reports the position applied
 * it holds the timestamp that was sent, so the next sync can recognise the
 * reader's listing as this app's own write rather than a new reading event.
 */
data class OwedResume(
    val filename: String,
    val percentage: Float,
    val spine: Int,
    val spineFraction: Float,
    val spineCount: Int,
    val chosenAt: Long,
    val appliedAt: Long = 0L,
) {
    /** A spine jump the reader will accept: 0 <= spine < spine_n <= 65535. */
    val hasJump: Boolean get() = spineCount in 1..0xFFFF && spine in 0 until spineCount

    /**
     * The per-entry fields a `progress` batch entry and a book's `position`
     * share. The reader needs a location or a spine jump; this app never has a
     * location of the reader's own, so without [hasJump] this is not sendable.
     */
    fun positionJson(timestamp: Long): org.json.JSONObject = org.json.JSONObject().apply {
        put("timestamp", timestamp)
        put("pct", percentage.coerceIn(0f, 1f).toDouble())
        if (hasJump) {
            put("spine", spine)
            put("spine_frac", spineFraction.coerceIn(0f, 1f).toDouble())
            put("spine_n", spineCount)
        }
    }
}

/**
 * Resumes the user chose that the reader has not applied yet.
 *
 * Answering "resume" does not move the reader by itself. A position batch is
 * refused while any book is open, and the reader keeps a position only when
 * its timestamp is newer than its own save -- so a book opened once before the
 * position lands keeps page one forever. The choice is recorded here and
 * delivered by the position sync until the reader confirms it. See
 * MainViewModel.runKosyncPass.
 *
 * Keyed per reader, like [StartFreshStore], and persisted because delivery can
 * wait for the book to be closed, a reconnect, or an app restart.
 */
class OwedResumeStore(private val context: Context) {

    private fun key(deviceId: String?) =
        stringPreferencesKey("owed_resume_" + (deviceId ?: "unknown"))

    suspend fun load(deviceId: String?): Map<String, OwedResume> =
        context.dataStore.data.map { decode(it[key(deviceId)]) }.first()

    suspend fun put(deviceId: String?, owed: OwedResume) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            p[k] = encode(decode(p[k]) + (owed.filename to owed))
        }
    }

    /** The reader applied it, stamped [timestamp]. */
    suspend fun markApplied(deviceId: String?, filename: String, timestamp: Long) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            val all = decode(p[k])
            val owed = all[filename] ?: return@edit
            p[k] = encode(all + (filename to owed.copy(appliedAt = timestamp)))
        }
    }

    suspend fun forget(deviceId: String?, filename: String) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            val all = decode(p[k])
            if (filename in all) p[k] = encode(all - filename)
        }
    }

    private fun decode(raw: String?): Map<String, OwedResume> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            obj.keys().asSequence().mapNotNull { name ->
                val o = obj.optJSONObject(name) ?: return@mapNotNull null
                name to OwedResume(
                    filename = name,
                    percentage = o.optDouble("pct", 0.0).toFloat(),
                    spine = o.optInt("spine", -1),
                    spineFraction = o.optDouble("spine_frac", 0.0).toFloat(),
                    spineCount = o.optInt("spine_n", 0),
                    chosenAt = o.optLong("chosen_at", 0L),
                    appliedAt = o.optLong("applied_at", 0L),
                )
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    private fun encode(all: Map<String, OwedResume>): String = org.json.JSONObject().apply {
        for ((name, o) in all) {
            put(name, org.json.JSONObject().apply {
                put("pct", o.percentage.toDouble())
                put("spine", o.spine)
                put("spine_frac", o.spineFraction.toDouble())
                put("spine_n", o.spineCount)
                put("chosen_at", o.chosenAt)
                put("applied_at", o.appliedAt)
            })
        }
    }.toString()
}

/**
 * Books the user has removed that the reader has not let go of yet.
 *
 * Removing a book while the reader is out of range keeps the local copy until
 * the reader confirms, and records the filename here, so the row keeps drawing,
 * greyed, saying what it is waiting for. Deleting the copy straight away would
 * leave nothing on screen to show that a deletion is still owed, and no way to
 * tell a removed book from one that was never saved.
 *
 * Persisted rather than held in memory because the wait can span an app
 * restart -- that is the normal case, not the edge one.
 *
 * Keyed per device for the same reason [SentBooksStore] is: "removed" is a fact
 * about one reader, and a second reader has its own idea of what it holds.
 */
class PendingRemovalStore(private val context: Context) {

    private fun key(deviceId: String?) =
        stringSetPreferencesKey("pending_removal_" + (deviceId ?: "unknown"))

    fun names(deviceId: String?): Flow<Set<String>> =
        context.dataStore.data.map { it[key(deviceId)] ?: emptySet() }

    suspend fun load(deviceId: String?): Set<String> = names(deviceId).first()

    suspend fun add(deviceId: String?, filename: String) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            p[k] = (p[k] ?: emptySet()) + filename
        }
    }

    suspend fun forget(deviceId: String?, filename: String) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            p[k] = (p[k] ?: emptySet()) - filename
        }
    }
}

/**
 * Filenames this app has successfully committed to a reader.
 *
 * This is app-side bookkeeping, NOT a view of the reader's filesystem: the
 * BLE protocol exposes no list-files operation, so nothing here is verified
 * against the device. Entries are keyed by reader device id where the reader
 * reports one, so pairing with a second reader does not inherit the first
 * one's history.
 */
class SentBooksStore(private val context: Context) {

    private fun key(deviceId: String?) =
        stringSetPreferencesKey("sent_books_" + (deviceId ?: "unknown"))

    fun names(deviceId: String?): Flow<Set<String>> =
        context.dataStore.data.map { it[key(deviceId)] ?: emptySet() }

    suspend fun load(deviceId: String?): Set<String> = names(deviceId).first()

    suspend fun add(deviceId: String?, filename: String) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            p[k] = (p[k] ?: emptySet()) + filename
        }
    }

    suspend fun forget(deviceId: String?, filename: String) {
        context.dataStore.edit { p ->
            val k = key(deviceId)
            p[k] = (p[k] ?: emptySet()) - filename
        }
    }
}
