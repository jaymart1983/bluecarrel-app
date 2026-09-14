package dev.bluecarrel.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local library of downloaded EPUBs.
 *
 * Lives in filesDir (not cacheDir) deliberately: cacheDir is evictable by the
 * OS under storage pressure, and a book you pulled down to read offline is
 * exactly the thing you don't want silently deleted on a plane.
 *
 * Filenames come from OpdsClient.filenameFor(), so the name here matches what
 * gets pushed to the reader.
 *
 * This directory is also the *only* record of what the offline shelf contains,
 * independent of whatever the Store is listing; see [cachedBooks].
 */
class BookStore(private val context: Context) {

    private val dir = File(context.filesDir, "books").apply { mkdirs() }

    /**
     * Catalogue metadata for each saved book, one JSON file per book, keyed by
     * the same `book.filename` [fileFor] keys on.
     *
     * Kept in a sibling directory rather than beside the EPUBs so [cachedNames]
     * — which is "what is on the shelf" — never sees a sidecar and mistakes it
     * for a book.
     *
     * Why persist this at all when [cachedAt] deliberately does *not* use a
     * sidecar: a modification time is something the filesystem already knows,
     * but an author, a cover URL and a blurb are not. Without them the Library
     * can only be rebuilt by matching against a live catalogue, which is what
     * made it depend on the Store's current contents in the first place.
     *
     * The EPUB stays the source of truth for "is this saved". The sidecar is
     * written after a successful download and is only ever *extra* information:
     * a book whose sidecar is missing or corrupt still appears on the shelf,
     * described from its filename (see [bookFromFilename]) rather than vanishing.
     */
    private val metaDir = File(context.filesDir, "book-meta").apply { mkdirs() }

    private fun metaFor(filename: String) = File(metaDir, "$filename.json")

    fun fileFor(book: Book) = File(dir, book.filename)

    fun isCached(book: Book): Boolean = fileFor(book).let { it.exists() && it.length() > 0 }

    fun cachedNames(): Set<String> = dir.listFiles()?.map { it.name }?.toSet().orEmpty()

    /** Records what we know about a book we have just saved offline. */
    fun remember(book: Book) {
        runCatching { metaFor(book.filename).writeText(toJson(book).toString()) }
    }

    /**
     * The offline shelf, read off the disk.
     *
     * This is what the Library is built from. It needs no catalogue, no network
     * and no search results: every book that has bytes in the books directory is
     * on the shelf, whatever the Store is currently showing.
     *
     * [Book.sizeBytes] is taken from the file rather than from the sidecar's
     * copy of the OPDS `length` attribute — for a saved book the file itself is
     * the better answer, and it stays right if the server's figure was not.
     */
    fun cachedBooks(): List<Book> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.length() > 0 }
            .map { f ->
                val book = readMeta(f.name) ?: bookFromFilename(f.name)
                book.copy(sizeBytes = f.length())
            }

    /**
     * Somewhere to put things pulled off the reader (its crash report). Kept
     * out of the books directory so it never shows up as a cached title.
     */
    fun writeDiagnostic(name: String, bytes: ByteArray): File {
        val target = File(File(context.filesDir, "diagnostics").apply { mkdirs() }, name)
        target.writeBytes(bytes)
        return target
    }

    /**
     * When this book was saved offline, or 0 when it is not.
     *
     * The Library orders by this, so a book saved a minute ago sits above one
     * saved last week. mtime rather than a sidecar: the file IS the record of
     * having saved it, and a second store to keep in step would be a second
     * thing to get wrong.
     */
    fun cachedAt(book: Book): Long = fileFor(book).let { if (it.exists()) it.lastModified() else 0L }

    /**
     * Removes the offline copy from **this phone**.
     *
     * Deliberately local-only. The mirror to the reader is one-way and additive
     * — there is no list-files operation over BLE, so the app cannot see the
     * reader's card and has no way to know whether a book there is one it sent,
     * one side-loaded over USB, or one someone is half-way through. Deleting it
     * to satisfy a bookkeeping rule would be worse than leaving it. The UI says
     * so before it calls this.
     */
    fun delete(book: Book): Boolean {
        runCatching { metaFor(book.filename).delete() }
        return fileFor(book).delete()
    }

    /**
     * Hands the cached EPUB to another reader app (KOReader et al) through
     * FileProvider. ACTION_VIEW rather than SEND: we want "open this book",
     * not "share this file", and KOReader registers for epub view intents.
     */
    fun openInReaderIntent(book: Book): Intent? {
        val f = fileFor(book)
        if (!f.exists()) return null
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", f,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, EPUB_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Whether anything on this phone can open the book.
     *
     * Asked of the package manager rather than answered by looking for
     * KOReader's package name. There are several forks and repackagings of it
     * (`org.koreader.launcher`, the F-Droid build, the Play build) and plenty of
     * other perfectly good EPUB readers; hardcoding one name would grey the
     * button out for a phone that can in fact open the book, which is a worse
     * failure than offering a button that hands off to something else.
     *
     * Requires the `<queries>` declaration in the manifest: from API 30 an app
     * only sees activities matching an intent shape it has declared, so without
     * it this would return an empty list on every modern phone and the button
     * would be permanently grey.
     */
    fun canOpenInReader(book: Book): Boolean {
        val intent = openInReaderIntent(book) ?: return false
        return runCatching {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .isNotEmpty()
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------- sidecars

    private fun readMeta(filename: String): Book? = runCatching {
        val f = metaFor(filename)
        if (!f.exists() || f.length() == 0L) return null
        fromJson(JSONObject(f.readText()), filename)
    }.getOrNull()

    private fun toJson(book: Book): JSONObject = JSONObject().apply {
        put("id", book.id)
        put("title", book.title)
        put("author", book.author)
        put("download_url", book.downloadUrl)
        put("cover_url", book.coverUrl)
        put("filename", book.filename)
        put("description", book.description)
        put("format", book.format)
        put("size_bytes", book.sizeBytes)
        put("publisher", book.publisher)
        put("language", book.language)
        put("published", book.published)
        put("tags", JSONArray(book.tags))
        put("series", book.series)
        put("updated", book.updated)
        put("series_index", book.seriesIndex)
    }

    private fun fromJson(j: JSONObject, filename: String): Book {
        val title = j.optString("title").ifBlank { titleFromFilename(filename) }
        return Book(
            id = j.optString("id").ifBlank { filename },
            title = title,
            author = j.optString("author").ifBlank { "Unknown" },
            downloadUrl = j.optString("download_url").ifBlank { null },
            coverUrl = j.optString("cover_url").ifBlank { null },
            // Always the name the file actually has: a sidecar that disagreed
            // with its own filename would point fileFor() at nothing.
            filename = filename,
            description = j.optString("description"),
            format = j.optString("format").ifBlank { "epub" },
            sizeBytes = j.optLong("size_bytes", 0L),
            publisher = j.optString("publisher"),
            language = j.optString("language"),
            published = j.optString("published"),
            tags = j.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
            }.orEmpty(),
            series = j.optString("series"),
            seriesIndex = j.optDouble("series_index", 0.0),
            updated = j.optString("updated"),
        )
    }

    /**
     * A book described by nothing but its own filename.
     *
     * The fallback for a shelf entry whose sidecar predates this store, was
     * lost, or will not parse. It is deliberately lossy rather than fatal: the
     * point of the offline library is that the file is there, and showing it
     * under an approximate title beats pretending it does not exist.
     */
    private fun bookFromFilename(filename: String) = Book(
        id = filename,
        title = titleFromFilename(filename),
        author = "Unknown",
        downloadUrl = null,
        coverUrl = null,
        filename = filename,
    )

    /** "Some-Title-Author.epub" -> "Some Title Author". */
    private fun titleFromFilename(filename: String): String =
        filename.removeSuffix(".epub")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { filename }

    private companion object {
        const val EPUB_MIME = "application/epub+zip"
    }
}
