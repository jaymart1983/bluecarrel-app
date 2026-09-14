package com.jmart.x4sync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.json.JSONObject

/**
 * KOReader sync protocol client, talking to Calibre-Web Automated's built-in
 * sync server (`cps/progress_syncing/protocols/kosync.py`).
 *
 * Surface:
 *   GET /users/auth                 validate credentials
 *   GET /syncs/progress/:document   read position
 *   PUT /syncs/progress             write position
 *
 * Auth is **HTTP Basic**, against an ordinary CWA account.
 *
 * This differs from the standalone `koreader/kosync` server, which takes
 * `x-auth-user` plus `x-auth-key` (the MD5 of the password). CWA's
 * `authenticate_user()` reads only the `Authorization: Basic` header and
 * verifies the *raw* password against CWA's own password hash — those headers
 * are ignored outright and produce a 401, and an MD5 digest sent as the
 * password is simply the wrong password.
 *
 * `percentage` is a fraction in 0..1 on both sides of the wire; CWA stores
 * 0..100 internally and converts at the edge.
 */
class KosyncClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val user: String,
    private val pass: String,
) {

    private fun builder(path: String) = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .header("Authorization", Credentials.basic(user, pass))
        .header("Accept", "application/vnd.koreader.v1+json")

    suspend fun authenticates(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(builder("/users/auth").get().build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Null when the server has never seen this document, which is normal.
     *
     * CWA answers an unknown document with `200 {}` rather than a 404, so the
     * empty-object case is a miss, not an error.
     */
    suspend fun progress(documentHash: String): Progress? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(builder("/syncs/progress/$documentHash").get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    val json = JSONObject(body)
                    val doc = json.optString("document")
                    if (doc.isNullOrBlank()) return@use null
                    Progress(
                        document = doc,
                        percentage = json.optDouble("percentage", 0.0).toFloat(),
                        device = json.optString("device", "unknown"),
                        timestamp = json.optLong("timestamp", 0L),
                        // Kept, not dropped. This is where both sides record WHEN
                        // a position was set, which is the only trustworthy input
                        // to the conflict rule -- see Progress.timestamp.
                        raw = json.optString("progress").orEmpty(),
                    )
                }
        }.getOrNull()
    }

    /**
     * Writes a position. Returns true only when CWA stored it.
     *
     * ## The payload
     *
     * `progress` carries JSON rather than a bare position, so a row says who
     * wrote it and when the position was really set:
     *
     *     {"src":"reader","pos":"<spine>:<page>:<offset>","set_at":<epoch>,"pct":0.43}
     *
     * `src` says who wrote it, `set_at` is when the position was really saved
     * (CWA overwrites the row's own timestamp with its receive time), and `pos`
     * is provenance only -- a CrossPoint location indexes a repagination that
     * depends on font size, margins and viewport, so it means nothing to any
     * other client, and another client's `pos` means nothing to us. Read a
     * foreign row's `pct` (and `spine`/`spine_frac` when present); never its
     * `pos`.
     *
     * ## Limits, verified against CWA's validator
     *
     *  - `progress` must be <= 255 chars (kosync.py:76 MAX_PROGRESS_LENGTH).
     *    Over that is a hard 400/2003 -- it rejects, it never truncates -- so
     *    the payload degrades to the bare position rather than risk the write.
     *  - `document` is a KEY field and must not contain a colon (kosync.py:111).
     *    A hex partial-MD5 never does; this is asserted rather than assumed
     *    because it would otherwise fail as an opaque "invalid fields".
     *  - `percentage` goes as a 0-1 decimal. CWA multiplies anything <= 1.0 by
     *    100 (kosync.py:654) and stores 0-100.
     */
    suspend fun putProgress(
        documentHash: String,
        position: String,
        percentage: Float,
        setAt: Long,
        deviceName: String,
        deviceId: String,
    ): Boolean {
        val payload = JSONObject().apply {
            put("src", "reader")
            put("pos", position)
            put("set_at", setAt)
            put("pct", percentage.toDouble())
        }.toString()
        // Never send something the validator will reject: a 400 loses the write
        // entirely, whereas the bare position still records where the reader is.
        val progressField = if (payload.length <= MAX_PROGRESS_LENGTH) payload else position
        return putRaw(documentHash, progressField, percentage, deviceName, deviceId)
    }

    /**
     * Where the reader is in a book, looked up under BOTH keys.
     *
     * [stableKey] is the Calibre book UUID ([Book.progressKey]); [contentHash] is
     * KOReader's partial MD5 of the file. The hash changes every time Calibre
     * rewrites the book, so on its own it orphaned positions whenever a book was
     * re-downloaded. The UUID does not change. The hash is still read because
     * KOReader devices and every row written before this only know that key.
     *
     * Migrates on read: a position that exists ONLY under the hash is copied to
     * the UUID, verbatim, the first time it is seen. Without that, a book nobody
     * happened to read before its next re-download would lose its only row.
     *
     * Returns the newer of the two by [Progress.ageStamp] (the set_at the writer
     * recorded, not CWA's receive time), then by percentage.
     */
    suspend fun progressFor(
        stableKey: String?,
        contentHash: String?,
        deviceName: String,
        deviceId: String,
    ): Progress? {
        // Both keys at once: two independent GETs, one round trip instead of two.
        val (stable, hashed) = coroutineScope {
            val s = async { stableKey?.let { progress(it) } }
            val h = async { contentHash?.takeIf { it != stableKey }?.let { progress(it) } }
            s.await() to h.await()
        }
        if (stable == null && hashed != null && stableKey != null && hashed.raw.isNotBlank()) {
            putRaw(stableKey, hashed.raw, hashed.percentage, deviceName, deviceId)
        }
        return listOfNotNull(stable, hashed)
            .maxWithOrNull(compareBy<Progress>({ it.ageStamp }, { it.percentage }))
    }

    /** Writes a position under both keys; true when at least one landed. */
    suspend fun putProgressFor(
        stableKey: String?,
        contentHash: String?,
        position: String,
        percentage: Float,
        setAt: Long,
        deviceName: String,
        deviceId: String,
    ): Boolean {
        var any = false
        for (key in listOfNotNull(stableKey, contentHash).distinct()) {
            if (putProgress(key, position, percentage, setAt, deviceName, deviceId)) any = true
        }
        return any
    }

    /**
     * Writes the `progress` field VERBATIM under [document].
     *
     * [putProgress] wraps a position in this app's payload; a migrated row must
     * be copied exactly as it was written, including rows another client wrote,
     * or its set_at would be replaced and it would look newer than it is.
     */
    private suspend fun putRaw(
        document: String,
        progressField: String,
        percentage: Float,
        deviceName: String,
        deviceId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (document.isBlank() || document.contains(':') || document.length > MAX_DOCUMENT_LENGTH) {
            return@withContext false
        }
        if (progressField.length > MAX_PROGRESS_LENGTH) return@withContext false

        val body = JSONObject().apply {
            put("document", document)
            put("progress", progressField)
            put("percentage", percentage.coerceIn(0f, 1f).toDouble())
            put("device", deviceName)
            put("device_id", deviceId)
        }.toString()

        runCatching {
            http.newCall(
                builder("/syncs/progress")
                    .put(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private companion object {
        /** kosync.py:76 MAX_PROGRESS_LENGTH. Enforced on PUT; over it is a 400. */
        const val MAX_PROGRESS_LENGTH = 255
        /** kosync.py:75 MAX_DOCUMENT_LENGTH, and colons are reserved (kosync.py:111). */
        const val MAX_DOCUMENT_LENGTH = 255
    }
}
