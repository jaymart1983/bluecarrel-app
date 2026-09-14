package com.jmart.x4sync.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's Calibre library as an offset-addressable, session-stable list.
 *
 * `catalog_page` asks for `offset` and `limit` into "the app's whole (possibly
 * filtered or sorted — that is the app's choice, and it must be stable within a
 * session) library view". OPDS does not offer that: it offers a first feed and
 * a chain of `rel="next"` links. So this walks the chain lazily and keeps what
 * it has walked.
 *
 * Consequences, all deliberate:
 *
 *  - **Stability.** Once a book is in [items] it stays at that index for the
 *    rest of the session, so paging forward and back never shuffles rows under
 *    the user even if Calibre's `/new` ordering changes underneath. [reset]
 *    drops the snapshot; it is called when the link drops, because the reader
 *    starts a new request counter then anyway.
 *  - **Cost.** A page turn is one HTTP round trip at most, usually zero once
 *    the feed page it needs is already walked — an OPDS page holds far more
 *    books than the reader's six.
 *  - **Total.** [OpdsPage.total] is OpenSearch's `totalResults`, which
 *    Calibre-Web does send. Without it the reader cannot know a next page
 *    exists, so when it is missing this reports `items + 1` while more remain
 *    and the exact count once the walk is exhausted. That over-reports by one
 *    on such a server; the alternative is pagination that stops dead at the
 *    first feed page.
 *
 * Ids are sanitised here rather than at send time because the reader hands them
 * straight back in `catalog_detail` and `catalog_fetch`, so the mapping from
 * the id on the wire to the book has to survive the round trip. `readEntry`
 * refuses the whole page over one bad id, so this is not a place to be lax.
 */
class CatalogSource(
    private val opds: OpdsClient,
    private val feedPath: String,
) {

    private companion object {
        /** Feed pages walked to satisfy one request, so a huge library cannot
         *  turn a single page turn into a minute of HTTP. */
        const val MAX_FETCHES_PER_CALL = 8

        /** Total books held. At ~200 bytes each this is a few hundred KB. */
        const val MAX_ITEMS = 20_000
    }

    data class Page(val books: List<Book>, val total: Int)

    private val mutex = Mutex()
    private val items = mutableListOf<Book>()
    private val byId = mutableMapOf<String, Book>()
    private val idOfIndex = mutableListOf<String>()

    private var nextUrl: String? = null
    private var started = false
    private var exhausted = false
    private var reportedTotal = -1

    /** Forgets the snapshot. The reader's `req` counter resets on reconnect too. */
    suspend fun reset() = mutex.withLock {
        items.clear()
        byId.clear()
        idOfIndex.clear()
        nextUrl = null
        started = false
        exhausted = false
        reportedTotal = -1
    }

    /** The wire id for the book at [index], as published in a `catalog_page`. */
    suspend fun idAt(index: Int): String? = mutex.withLock { idOfIndex.getOrNull(index) }

    /** The book a `catalog_detail` / `catalog_fetch` id refers to, if we sent it. */
    suspend fun bookFor(id: String): Book? = mutex.withLock { byId[id] }

    /**
     * Walks far enough to answer `offset`..`offset + limit`, then slices.
     *
     * An offset past the end returns an empty list with the real total, which
     * is what the reader needs to stop paging. Note that the firmware refuses a
     * page with no items **and** a non-zero total as `empty catalog page`, so
     * the caller declines with `catalog_error` in that case instead of sending
     * one.
     */
    suspend fun page(offset: Int, limit: Int): Page = mutex.withLock {
        var fetches = 0
        while (items.size < offset + limit && !exhausted && fetches < MAX_FETCHES_PER_CALL) {
            fetchMore()
            fetches++
        }
        val from = offset.coerceIn(0, items.size)
        val to = (offset + limit).coerceIn(from, items.size)
        Page(books = items.subList(from, to).toList(), total = totalNow())
    }

    /** Must be called holding [mutex]. */
    private suspend fun fetchMore() {
        val url = if (!started) null else nextUrl
        if (started && url == null) {
            exhausted = true
            return
        }
        val page = if (url == null) opds.feedPage(feedPath) else opds.feedAt(url)
        started = true
        nextUrl = page.nextUrl
        if (page.total >= 0) reportedTotal = page.total
        if (page.books.isEmpty() && page.nextUrl == null) exhausted = true
        for (book in page.books) {
            if (items.size >= MAX_ITEMS) {
                exhausted = true
                return
            }
            val id = uniqueId(book)
            items += book
            idOfIndex += id
            byId[id] = book
        }
        if (page.nextUrl == null) exhausted = true
    }

    /**
     * A printable-ASCII id the reader will accept, unique within the snapshot.
     *
     * OPDS ids are usually `urn:uuid:…`, which is already clean; the fallback
     * is the filename, which the reader's own filename rule has already forced
     * into a safe alphabet. A collision (two Calibre entries sharing an id, or
     * two ids that sanitise to the same string) would send the wrong book on a
     * fetch, so it is broken with a suffix rather than tolerated.
     */
    private fun uniqueId(book: Book): String {
        val base = CatalogContainer.sanitizeId(
            book.id,
            fallback = book.filename.ifBlank { "b${items.size}" },
        )
        if (!byId.containsKey(base)) return base
        var n = 2
        while (true) {
            val suffix = "~$n"
            val head = CatalogContainer.clampUtf8(
                base, CatalogContainer.MAX_ID_BYTES - suffix.length,
            )
            val candidate = head + suffix
            if (!byId.containsKey(candidate)) return candidate
            n++
        }
    }

    private fun totalNow(): Int = when {
        reportedTotal >= 0 -> reportedTotal
        exhausted -> items.size
        // More to come but the server never said how many. One more than we
        // hold keeps the reader's "next page" affordance alive.
        else -> items.size + 1
    }
}
