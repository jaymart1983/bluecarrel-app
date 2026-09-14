package com.jmart.x4sync.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Reads the Calibre-Web Automated OPDS catalog.
 *
 * CWA requires HTTP Basic auth on /opds (it returns 401 unauthenticated),
 * so credentials are mandatory rather than optional.
 */
private const val MAX_TAGS = 6

class OpdsClient(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val user: String,
    private val pass: String,
) {

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", Credentials.basic(user, pass))
        .header("Accept", "application/atom+xml")
        .build()

    /** Fetch and parse one OPDS feed page. */
    suspend fun feed(path: String = "/new"): List<Book> = feedPage(path).books

    /**
     * One feed page with the paging metadata the BLE Store needs: the reader
     * cannot show "page 3 of 40" — or even know a next page exists — without a
     * total, and OPDS only supplies one through OpenSearch's `totalResults`.
     */
    suspend fun feedPage(path: String = "/new"): OpdsPage =
        feedAt(joinUrl(baseUrl, path))

    /** As [feedPage] but for an absolute URL, e.g. a feed's own `next` link. */
    suspend fun feedAt(url: String): OpdsPage = withContext(Dispatchers.IO) {
        http.newCall(request(url)).execute().use { resp ->
            // A server behind Cloudflare Access redirects to a login page, and
            // OkHttp follows it to a 200 with HTML. Left alone that surfaces as
            // an XML parse error that says nothing about the actual cause.
            if (resp.request.url.host.endsWith("cloudflareaccess.com")) {
                error("the server is behind Cloudflare Access, which is redirecting the app to a login page")
            }
            if (!resp.isSuccessful) error("OPDS ${resp.code} from $url")
            parse(resp.body!!.byteStream(), url)
        }
    }

    /**
     * Searches the catalogue.
     *
     * The terms go in a *path* segment (`/opds/search/<terms>`), so they are
     * percent-encoded as a path segment: `URLEncoder.encode` is the
     * `application/x-www-form-urlencoded` rules, which turn a space into `+`
     * and leave a literal `+` indistinguishable from one. HttpUrl's
     * [HttpUrl.Builder.addPathSegment] encodes for the position the text
     * actually occupies, which is what the server is parsing it as.
     */
    suspend fun search(query: String): List<Book> {
        val url = baseHttpUrl()?.newBuilder()
            ?.addPathSegment("search")
            ?.addPathSegment(query)
            ?.build()
            ?.toString()
            ?: error("Server URL is not a valid address: $baseUrl")
        return feedAt(url).books
    }

    /**
     * Streams a book to [target]. Returns the file for hashing/upload.
     *
     * The failure text matters here: this is the one network call the user
     * triggers by name and then watches fail. A bare "Download 404" reads as an
     * app bug, and the two things it can actually mean — the server has no such
     * book, or it has the row but not the file — are both on the server and both
     * actionable there. Calibre-Web logs "File not found: …" and returns 404
     * when its database lists a format whose file is missing from the library
     * folder, which is a real state a library gets into.
     *
     * @param onProgress called with (bytesWritten, totalBytes); totalBytes is -1
     *        when the server sends no Content-Length. Fires on the IO thread.
     */
    suspend fun download(
        book: Book,
        target: File,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val raw = book.downloadUrl
            ?: error("The catalogue lists no download link for \"${book.title}\"")
        // Defensive: everything downstream (OkHttp, the retry, the message)
        // assumes an absolute http(s) URL. A relative one that escaped
        // resolution would otherwise fail as an opaque IllegalArgumentException.
        val url = raw.toHttpUrlOrNull()
            ?: error("The download link for \"${book.title}\" is not a usable address: $raw")

        http.newCall(request(url.toString())).execute().use { resp ->
            if (!resp.isSuccessful) {
                error(
                    when (resp.code) {
                        404 -> "The server has no file for \"${book.title}\" " +
                            "(404 from ${url.encodedPath}). Calibre lists the format " +
                            "but the file is missing from the library folder."
                        401, 403 -> "The server refused the download of " +
                            "\"${book.title}\" (${resp.code}). Check the username and " +
                            "password, and that the account may download."
                        else -> "Download failed with ${resp.code} for \"${book.title}\""
                    }
                )
            }
            val body = resp.body ?: error("Empty response downloading \"${book.title}\"")
            val total = body.contentLength()
            if (onProgress == null) {
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            } else {
                // Copied by hand rather than with copyTo so the caller can see
                // it happening. A 3 MB book over a phone link is most of the
                // wait before the reader transfer even starts, and reporting
                // nothing for that whole stretch is what made saving a book
                // look like it had stalled.
                onProgress(0L, total)
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            // Throttled: a progress bar redrawn every 64 KB is
                            // a lot of recompositions for a bar that only has a
                            // few hundred pixels to move.
                            if (written - lastReport >= 256 * 1024 || written == total) {
                                lastReport = written
                                onProgress(written, total)
                            }
                        }
                    }
                }
            }
        }
        // A truncated or empty file is worse than none: it would sit on the
        // shelf looking saved, hash to nothing kosync knows, and be pushed to
        // the reader as a broken book.
        if (target.length() == 0L) {
            target.delete()
            error("The server sent an empty file for \"${book.title}\"")
        }
        target
    }

    private fun parse(input: java.io.InputStream, feedUrl: String): OpdsPage {
        val books = mutableListOf<Book>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        var id = ""; var title = ""; var author = ""; var description = ""
        var download: String? = null; var cover: String? = null
        var format = "epub"; var size = 0L
        var publisher = ""; var language = ""; var published = ""
        var tags = mutableListOf<String>(); var series = ""; var seriesIndex = 0.0
        var updated = ""
        var inEntry = false; var inAuthor = false
        var total = -1; var next: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        inEntry = true
                        id = ""; title = ""; author = ""; description = ""
                        download = null; cover = null; format = "epub"; size = 0L
                        publisher = ""; language = ""; published = ""
                        tags = mutableListOf(); series = ""; seriesIndex = 0.0
                        updated = ""
                    }
                    "author" -> inAuthor = true
                    // Calibre-Web publishes these on every entry. Namespaces are
                    // matched by local name because parser.name is unprefixed for
                    // some feeds and "dc:publisher" for others.
                    "publisher", "dc:publisher" ->
                        if (inEntry) publisher = runCatching { parser.nextText().trim() }.getOrDefault("")
                    "language", "dc:language" ->
                        if (inEntry) language = runCatching { parser.nextText().trim() }.getOrDefault("")
                    "issued", "dc:issued", "published" ->
                        if (inEntry && published.isEmpty()) {
                            published = runCatching { parser.nextText().trim() }.getOrDefault("")
                        }
                    // <category term="…" label="Science Fiction"/> — self-closing,
                    // so the value is an attribute and nextText() must not be called.
                    "category" -> if (inEntry) {
                        val label = parser.getAttributeValue(null, "label")
                            ?: parser.getAttributeValue(null, "term")
                        if (!label.isNullOrBlank() && tags.size < MAX_TAGS) tags += label.trim()
                    }
                    // Calibre's OPDS extension: <calibre:series name="…"/> and
                    // <calibre:series_index>2.0</calibre:series_index>.
                    "series", "calibre:series" -> if (inEntry) {
                        val name = parser.getAttributeValue(null, "name")
                        if (!name.isNullOrBlank()) series = name.trim()
                        else series = runCatching { parser.nextText().trim() }.getOrDefault("")
                    }
                    "series_index", "calibre:series_index" -> if (inEntry) {
                        seriesIndex = runCatching { parser.nextText().trim().toDouble() }.getOrDefault(0.0)
                    }
                    // Entry-level only: the feed carries its own <updated> too,
                    // which says when the FEED was generated and changes every
                    // request.
                    "updated" -> if (inEntry) {
                        updated = runCatching { parser.nextText().trim() }.getOrDefault("")
                    }
                    "id" -> if (inEntry) id = parser.nextText().trim()
                    "title" -> if (inEntry) title = parser.nextText().trim()
                    "name" -> if (inEntry && inAuthor) author = parser.nextText().trim()
                    // Calibre-Web puts the book's comment in <summary>; some
                    // other OPDS servers use <content>. Take whichever is
                    // non-empty and strip the HTML Calibre allows in comments,
                    // because the reader draws plain text with its own font.
                    "summary", "content" -> if (inEntry) {
                        // nextText() throws on an element with children, which
                        // is what type="xhtml" means. Calibre-Web escapes its
                        // comments into a text node, so only that shape is read
                        // and anything else is left to the ordinary walk.
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        if (!type.contains("xhtml")) {
                            val text = runCatching { parser.nextText() }.getOrDefault("")
                            if (description.isEmpty()) description = plainText(text)
                        }
                    }
                    "opensearch:totalResults", "totalResults" ->
                        if (!inEntry) {
                            total = runCatching { parser.nextText().trim().toInt() }
                                .getOrDefault(-1)
                        }
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel").orEmpty()
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        if (inEntry) {
                            when {
                                rel.contains("acquisition") && type.contains("epub") -> {
                                    download = absolute(href, feedUrl)
                                    format = "epub"
                                    size = parser.getAttributeValue(null, "length")
                                        ?.trim()?.toLongOrNull() ?: size
                                }
                                rel.contains("image") || rel.contains("thumbnail") ->
                                    cover = absolute(href, feedUrl)
                            }
                        } else if (rel == "next" && href.isNotEmpty()) {
                            next = absolute(href, feedUrl)
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        if (title.isNotEmpty() && download != null) {
                            books += Book(
                                id = id.ifEmpty { download!! },
                                title = title,
                                author = author.ifEmpty { "Unknown" },
                                downloadUrl = download,
                                coverUrl = cover,
                                filename = filenameFor(title, author),
                                description = description,
                                format = format,
                                sizeBytes = size,
                                publisher = publisher,
                                language = language,
                                // Feeds give a full ISO timestamp; the reader has
                                // room for a year and nothing is served by more.
                                published = published.take(4),
                                tags = tags.toList(),
                                series = series,
                                seriesIndex = seriesIndex,
                                updated = updated,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return OpdsPage(books = books, total = total, nextUrl = next)
    }

    /**
     * Calibre comments are HTML. The reader has no markup renderer, so tags
     * are dropped, the handful of entities Calibre emits are decoded, and
     * runs of whitespace collapse to single spaces.
     */
    private fun plainText(raw: String): String = raw
        .replace(Regex("(?i)<br\\s*/?>|</p>"), " ")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Filename must be stable and identical to what lands on the device: the
     * shelf, the sent/removal bookkeeping and the reader's own position and
     * library reports are all matched up by this string.
     *
     * The BLE reader accepts only `^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$` and
     * rejects anything else as "unsafe book filename" — spaces included. So
     * the safe name is generated here rather than patched at upload time; if
     * the two diverged, the reader's copy would stop matching this app's.
     */
    private fun filenameFor(title: String, author: String): String =
        safeEpubName("$title - $author")

    companion object {
        private const val MAX_NAME = 96
        private const val EXT = ".epub"

        /** Coerces a title into something the reader's filename check accepts. */
        fun safeEpubName(raw: String): String {
            val base = raw
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .replace(Regex("[._-]{2,}"), "-")
                .replace(Regex("^[^A-Za-z0-9]+"), "")
                .replace(Regex("[^A-Za-z0-9]+$"), "")
                .take(MAX_NAME - EXT.length)
                .ifBlank { "book-" + raw.hashCode().toUInt().toString(16) }
            return base + EXT
        }
    }

    /**
     * Resolves an OPDS `href` against the feed it came from.
     *
     * Uses OkHttp's [HttpUrl] rather than [java.net.URI] for two reasons, both
     * of which a title like "Weakest Beast Tamer Gets All SSS Dragons: Book 1"
     * can trip:
     *
     *  - **Encoding.** `URI.resolve(String)` parses its argument strictly and
     *    throws on a raw space, so a server that puts an un-encoded filename in
     *    the acquisition href would take out the whole feed parse rather than
     *    one book. (The reader firmware hit exactly this and answers it with
     *    `UrlUtils::encodeUnsafeUrlChars`.) [HttpUrl] canonicalises instead,
     *    percent-encoding what has to be encoded and leaving existing escapes
     *    alone, which is what a browser does with the same string.
     *  - **Resolution.** `href="download/17/epub/"` — relative with no leading
     *    slash — resolves against the *feed's* path. Browsing `/opds/new` that
     *    gives `/opds/download/17/epub/`; searching `/opds/search/<terms>` it
     *    gives `/opds/search/download/17/epub/`, which is a 404. HttpUrl applies
     *    the same RFC 3986 rules, but see [download] for the safety net.
     *
     * Falls back to the raw href rather than throwing: a book with an odd link
     * should fail on its own at download time, not remove every book after it
     * in the feed.
     */
    private fun absolute(href: String, base: String): String =
        base.toHttpUrlOrNull()?.resolve(href)?.toString() ?: href

    private fun joinUrl(base: String, path: String) =
        base.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * The catalogue root, for re-anchoring a relative acquisition link.
     * Null when the configured server URL will not parse as one.
     */
    private fun baseHttpUrl(): HttpUrl? = (baseUrl.trimEnd('/') + "/").toHttpUrlOrNull()
}

/**
 * One page of an OPDS feed.
 *
 * [total] is `opensearch:totalResults` when the server sends it and -1 when it
 * does not; [nextUrl] is the feed's own `rel="next"` link. Together they are
 * what [CatalogSource] needs to answer the reader's `catalog_page` requests,
 * which carry an absolute offset into a view the reader never sees.
 */
data class OpdsPage(
    val books: List<Book>,
    val total: Int,
    val nextUrl: String?,
)
