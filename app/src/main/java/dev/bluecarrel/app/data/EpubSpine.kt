package dev.bluecarrel.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * A position the reader accepts from another source: a spine item, a fraction
 * through it, and the spine item count it was measured against.
 */
data class SpineJump(val spine: Int, val fraction: Float, val count: Int)

/**
 * Turns a percentage into a [SpineJump] for an EPUB this phone holds.
 *
 * The reader cannot take a bare percentage in a `progress` upload: an entry
 * needs either its own native `location` or a spine jump, and one with neither
 * is refused as `invalid`. Rows this app writes to kosync carry only `pct`, so
 * a resume to a server position has to be converted here.
 *
 * Mirrors the firmware exactly, so the spine count matches and the reader's own
 * percentage lands where the server's was:
 *  - the spine is every `<itemref>` whose `idref` is in the manifest, in order
 *    (ContentOpfParser; `linear` is not consulted);
 *  - an item's href is the OPF directory plus the href, %-decoded, then
 *    normalised (FsHelpers::decodeUriEscapes / normalisePath);
 *  - an item's size is its uncompressed size in the zip, 0 when missing
 *    (BookMetadataCache);
 *  - percent = (cumulative size before the item + fraction * item size) / total
 *    (Epub::calculateProgress), inverted here the way ProgressMapper picks the
 *    first item whose cumulative size reaches the target.
 */
object EpubSpine {

    fun jumpFor(file: File, percentage: Float): SpineJump? = runCatching {
        ZipFile(file).use { zip ->
            val opfPath = zip.getEntry("META-INF/container.xml")
                ?.let { e -> zip.getInputStream(e).use { rootfile(it) } }
                ?: return null
            val opf = zip.getEntry(opfPath) ?: return null
            val base = opfPath.substring(0, opfPath.lastIndexOf('/') + 1)
            val hrefs = zip.getInputStream(opf).use { spineHrefs(it, base) }
            if (hrefs.isEmpty() || hrefs.size > 0xFFFF) return null

            val sizes = hrefs.map { href -> zip.getEntry(href)?.size?.takeIf { it > 0 } ?: 0L }
            val total = sizes.sum()
            if (total <= 0L) return null

            val target = (total.toDouble() * percentage.coerceIn(0f, 1f)).toLong()
            var before = 0L
            for ((i, size) in sizes.withIndex()) {
                if (before + size >= target) {
                    val fraction = if (size > 0) ((target - before).toDouble() / size).toFloat() else 0f
                    return SpineJump(i, fraction.coerceIn(0f, 1f), sizes.size)
                }
                before += size
            }
            SpineJump(sizes.size - 1, 1f, sizes.size)
        }
    }.getOrNull()

    /** `full-path` of the first `<rootfile>` in container.xml. */
    private fun rootfile(input: InputStream): String? {
        val parser = parserFor(input)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localName(parser.name) == "rootfile") {
                return parser.getAttributeValue(null, "full-path")?.takeIf { it.isNotBlank() }
            }
            event = parser.next()
        }
        return null
    }

    /** Spine hrefs in reading order, resolved to zip entry names. */
    private fun spineHrefs(input: InputStream, base: String): List<String> {
        val parser = parserFor(input)
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        var inManifest = false
        var inSpine = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "manifest" -> inManifest = true
                    "spine" -> inSpine = true
                    "item" -> if (inManifest) {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = normalise(decode(base + href))
                    }
                    "itemref" -> if (inSpine) {
                        parser.getAttributeValue(null, "idref")?.let { manifest[it] }?.let { spine += it }
                    }
                }
                XmlPullParser.END_TAG -> when (localName(parser.name)) {
                    "manifest" -> inManifest = false
                    "spine" -> inSpine = false
                }
            }
            event = parser.next()
        }
        return spine
    }

    private fun parserFor(input: InputStream): XmlPullParser {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        // No DOCTYPE, as in OpdsClient: entity expansion attacks start there.
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.DOCDECL) error("EPUB package has a DOCTYPE")
            event = parser.nextToken()
        }
        return parser
    }

    private fun localName(name: String?): String = name?.substringAfter(':').orEmpty()

    /** Byte-wise %XX decoding, as FsHelpers::decodeUriEscapes does. */
    private fun decode(path: String): String {
        val bytes = path.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            if (b == '%'.code && i + 2 < bytes.size) {
                val hi = Character.digit(bytes[i + 1].toInt(), 16)
                val lo = Character.digit(bytes[i + 2].toInt(), 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            out.write(b)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Collapses `..` and empty segments, as FsHelpers::normalisePath does. */
    private fun normalise(path: String): String {
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }
}
