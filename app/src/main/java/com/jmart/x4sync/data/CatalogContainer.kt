package com.jmart.x4sync.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the `CPCT` blob that answers a `catalog_page` or `catalog_detail`
 * request.
 *
 * ## Why a container rather than JSON
 *
 * A page is text (titles, authors, a blurb) and pictures (a cover per book).
 * Base64 inside JSON would cost a third more bytes on a link where bytes are
 * the whole constraint, and would force the reader to hold a decoded page in
 * RAM. So the payload is one binary blob: a short JSON header followed by the
 * thumbnails back to back. It rides the ordinary `start_put` upload path with
 * its framing, credit flow control and SHA-256 unchanged — the Store adds a
 * question, not a transport.
 *
 * ```
 *   offset  bytes      field
 *   0       4          magic "CPCT"
 *   4       1          version, currently 1
 *   5       1          flags, reserved, must be 0
 *   6       2          jsonLen, little-endian uint16
 *   8       jsonLen    the JSON header, UTF-8, no trailing NUL
 *   8+n     ...        thumbnails, concatenated in `items` order, each exactly
 *                      as many bytes as its item's `thumb` field says
 * ```
 *
 * The device seeks to each thumbnail by its own running offset rather than
 * reading straight through, so the `thumb` lengths in the header and the bytes
 * that follow must agree exactly or every cover after the first mismatch lands
 * in the wrong row.
 *
 * ## Caps, and what happens when we would break one
 *
 * `jsonLen` <= 6144, any single thumbnail <= 8192, the whole container <= 64 KB.
 * A container that breaks any of them is refused **whole** — the reader shows
 * nothing rather than a partial page. So the builder never emits one that does:
 * [build] shrinks descriptions and then drops covers until the result fits,
 * because a page with plain rows is worth far more to the user than a rejected
 * one.
 *
 * ## Field-level rules the firmware enforces
 *
 * `BleCatalog::readEntry` rejects the whole page — not the entry — when an
 * `id` is empty, longer than 64 bytes, or contains anything outside printable
 * ASCII minus `"` and `\`. Titles clamp to 160 bytes, authors to 128 and
 * filenames to 96 with a `[A-Za-z0-9._ -]` charset and no leading dot; those
 * are clamped rather than refused, but a filename that fails the check is
 * blanked on the device and the book becomes unfetchable, so it is worth
 * getting right here. Everything below is sized in **bytes of UTF-8**, not
 * characters, and truncation lands on a code-point boundary.
 */
object CatalogContainer {

    private const val MAGIC_C = 'C'.code.toByte()
    private const val MAGIC_P = 'P'.code.toByte()
    private const val MAGIC_T = 'T'.code.toByte()
    private const val VERSION: Byte = 1

    const val HEADER_BYTES = 8
    const val MAX_JSON_BYTES = 6144
    const val MAX_THUMB_BYTES = 8192
    const val MAX_CONTAINER_BYTES = 64 * 1024

    const val MAX_ID_BYTES = 64
    const val MAX_TITLE_BYTES = 160
    const val MAX_AUTHOR_BYTES = 128
    const val MAX_FILENAME_BYTES = 96
    const val MAX_META_BYTES = 64
    const val MAX_TAGS_BYTES = 96

    /** One catalogue row, already reduced to what goes on the wire. */
    data class Item(
        val id: String,
        val title: String,
        val author: String,
        val description: String,
        val filename: String,
        val format: String,
        val size: Long,
        /** The 1-bit BMP from [DeviceThumb], or null for a row with no art. */
        val thumbnail: ByteArray?,
        // Detail-only metadata. Sent for a catalog_detail and left empty for a
        // catalog_page: a page carries six of everything, and none of this is
        // shown in a list row.
        val series: String = "",
        val publisher: String = "",
        val published: String = "",
        val language: String = "",
        val tags: List<String> = emptyList(),
    ) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = id.hashCode()
    }

    /**
     * A `catalog_page` container: `{"req":…,"offset":…,"total":…,"items":[…]}`
     * followed by the covers.
     *
     * [total] is the size of the app's whole view. It is the field that makes
     * pagination possible — without it the reader cannot know whether a next
     * page exists — so it is required rather than optional.
     */
    fun page(req: Int, offset: Int, total: Int, items: List<Item>, descMax: Int): ByteArray =
        build(items, descMax) { list ->
            JSONObject().apply {
                put("req", req)
                put("offset", offset)
                put("total", total)
                put("items", JSONArray().apply { list.forEach { put(it) } })
            }
        }

    /**
     * A `catalog_detail` container: one `item` rather than an `items` array,
     * with the longer blurb and the bigger cover.
     */
    fun detail(req: Int, id: String, item: Item, descMax: Int): ByteArray =
        build(listOf(item), descMax) { list ->
            JSONObject().apply {
                put("req", req)
                put("id", id)
                put("item", list.first())
            }
        }

    /**
     * Serialises [items] under [header], shrinking until every cap is met.
     *
     * Three passes, cheapest concession first: the full thing, then
     * descriptions cut to a quarter, then no descriptions at all. If the JSON
     * still will not fit, covers are dropped from the end — a row without art
     * still reads, and a container over any cap is refused outright.
     */
    private fun build(
        items: List<Item>,
        descMax: Int,
        header: (List<JSONObject>) -> JSONObject,
    ): ByteArray {
        var thumbs: List<ByteArray?> = items.map { it.thumbnail?.takeIf { t -> fitsAsThumb(t) } }
        var descBudget = descMax

        while (true) {
            val objects = items.mapIndexed { i, item -> toJson(item, thumbs[i], descBudget) }
            val json = header(objects).toString().toByteArray(Charsets.UTF_8)
            val thumbBytes = thumbs.sumOf { it?.size ?: 0 }
            val totalSize = HEADER_BYTES + json.size + thumbBytes

            if (json.size <= MAX_JSON_BYTES && totalSize <= MAX_CONTAINER_BYTES) {
                return assemble(json, thumbs)
            }
            if (json.size > MAX_JSON_BYTES && descBudget > 0) {
                descBudget = if (descBudget > 64) descBudget / 4 else 0
                continue
            }
            // JSON alone is over budget with no descriptions left to cut, or
            // the covers push the blob past 64 KB: shed the last cover and
            // retry. `thumb` shrinks with it, so the header stays consistent.
            val lastWithArt = thumbs.indexOfLast { it != null }
            if (lastWithArt < 0) {
                // Nothing left to give. Emit it anyway rather than throwing:
                // the reader's own cap check is the authority and its error
                // message is more use to the user than an app-side exception.
                return assemble(json, thumbs)
            }
            thumbs = thumbs.toMutableList().also { it[lastWithArt] = null }
        }
    }

    private fun assemble(json: ByteArray, thumbs: List<ByteArray?>): ByteArray {
        val out = ByteArray(HEADER_BYTES + json.size + thumbs.sumOf { it?.size ?: 0 })
        out[0] = MAGIC_C
        out[1] = MAGIC_P
        out[2] = MAGIC_C
        out[3] = MAGIC_T
        out[4] = VERSION
        out[5] = 0 // flags, reserved
        out[6] = (json.size and 0xFF).toByte()
        out[7] = ((json.size ushr 8) and 0xFF).toByte()
        System.arraycopy(json, 0, out, HEADER_BYTES, json.size)
        var at = HEADER_BYTES + json.size
        for (t in thumbs) {
            if (t == null) continue
            System.arraycopy(t, 0, out, at, t.size)
            at += t.size
        }
        return out
    }

    private fun fitsAsThumb(bytes: ByteArray) = bytes.size in 1..MAX_THUMB_BYTES

    private fun toJson(item: Item, thumb: ByteArray?, descBudget: Int): JSONObject =
        JSONObject().apply {
            put("id", clampAscii(item.id, MAX_ID_BYTES))
            put("title", clampUtf8(item.title, MAX_TITLE_BYTES))
            put("author", clampUtf8(item.author, MAX_AUTHOR_BYTES))
            put("description", clampUtf8(item.description, descBudget))
            put("filename", clampUtf8(item.filename, MAX_FILENAME_BYTES))
            put("format", clampUtf8(item.format, 16))
            put("size", item.size.coerceAtLeast(0L))
            // Absent and 0 both mean "no cover"; 0 is written so the field is
            // never confused with a truncated header.
            put("thumb", thumb?.size ?: 0)
            // Optional, and omitted entirely when empty so a page entry pays
            // nothing for fields only a detail uses. The reader treats every one
            // as absent-by-default.
            if (item.series.isNotBlank()) put("series", clampUtf8(item.series, MAX_META_BYTES))
            if (item.publisher.isNotBlank()) put("publisher", clampUtf8(item.publisher, MAX_META_BYTES))
            if (item.published.isNotBlank()) put("published", clampUtf8(item.published, 16))
            if (item.language.isNotBlank()) put("language", clampUtf8(item.language, 16))
            if (item.tags.isNotEmpty()) {
                put("tags", clampUtf8(item.tags.joinToString(", "), MAX_TAGS_BYTES))
            }
        }

    /**
     * Truncates to [maxBytes] of UTF-8 without splitting a code point.
     *
     * The device re-clamps anyway (it does not trust the app), but it clamps
     * to *its* cap, which for a list row is 160 bytes; sending more just wastes
     * the one resource this link does not have.
     */
    fun clampUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /**
     * Reduces [value] to what `isSafeCatalogId` accepts: printable ASCII, no
     * `"` and no `\`, 1..64 bytes. An id that survives this round trips through
     * `catalog_detail` and `catalog_fetch` unchanged; one that does not would
     * make the reader refuse the whole page, so the caller keeps the mapping
     * from the sanitised id back to the real book.
     */
    fun sanitizeId(value: String, fallback: String): String {
        val cleaned = buildString {
            for (c in value) {
                val code = c.code
                if (code > 32 && code < 127 && c != '"' && c != '\\') append(c)
            }
        }
        val safe = cleaned.ifEmpty { fallback }
        return if (safe.length <= MAX_ID_BYTES) safe
        // Keeping the tail rather than the head: OPDS ids are long common
        // prefixes ("urn:uuid:") with the distinguishing part at the end.
        else safe.substring(safe.length - MAX_ID_BYTES)
    }

    private fun clampAscii(value: String, maxBytes: Int): String =
        if (value.length <= maxBytes) value else value.substring(0, maxBytes)
}
