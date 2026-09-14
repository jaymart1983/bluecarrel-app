package com.jmart.x4sync.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield

/**
 * Renders the reader's thumbnails before the reader asks for them.
 *
 * The Store is a request/response protocol with a deadline: the reader publishes
 * a `catalog_page` request and gives the phone a fixed budget to answer. Serving
 * it cold means downloading six covers from Calibre-Web and dithering each to a
 * 1-bit BMP inside that budget, over whatever link the phone happens to have.
 * That is what made the reader declare "the app did not answer" while the phone
 * was still fetching covers.
 *
 * Nothing here is new work: it is exactly what [CoverCache.deviceThumbnail]
 * would do on demand, moved off the critical path. Once warm, answering a page
 * is a handful of disk reads.
 *
 * Deliberately NOT tied to the reader being connected. The point is to be ready
 * *before* it connects, so warming starts as soon as the catalogue is known.
 */
class CatalogPrefetcher(
    private val covers: CoverCache,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /**
     * Warm the two geometries the reader asks for, newest first.
     *
     * [limit] bounds the work: a large library would otherwise pull every cover
     * on every refresh. The reader browses from the top, so the head of the list
     * is what it will ask for first; the rest stays lazy and still works, just
     * not instantly.
     */
    fun warm(
        books: List<Book>,
        user: String,
        pass: String,
        limit: Int = DEFAULT_LIMIT,
        /**
         * Index the reader is currently looking at, or -1 for "start at the top".
         *
         * Warming is ordered by distance from here rather than from the front of
         * the list. The reader browses a page at a time and asks for a detail
         * from whatever page it is on, so the covers worth having ready are the
         * ones around its cursor -- warming only the head of a large library
         * left everything past it to be fetched live, which is the two seconds
         * a detail request was spending.
         */
        focus: Int = -1,
    ) {
        // A newer catalogue -- or a new focus -- supersedes an in-flight warm.
        // Restarting is cheap: anything already rendered is a disk check away
        // from being skipped.
        job?.cancel()
        if (user.isBlank() && pass.isBlank()) return
        // Ordered by index, not by identity: indexOf() inside a comparator is
        // quadratic, which at 200 books is real work on the main thread.
        val ordered = if (focus < 0) {
            books
        } else {
            books.withIndex()
                // Slight forward bias: paging down is far more common than up.
                .sortedBy { (i, _) -> if (i >= focus) i - focus else (focus - i) * 2 }
                .map { it.value }
        }
        val targets = ordered.asSequence().mapNotNull { it.coverUrl }.distinct().take(limit).toList()
        if (targets.isEmpty()) return

        job = scope.launch(Dispatchers.IO) {
            // Bounded concurrency: enough to hide per-request latency, few enough
            // that this never competes with a transfer the user is watching, or
            // hammers Calibre-Web from a phone on mobile data.
            val gate = Semaphore(CONCURRENCY)
            GEOMETRIES.forEach { (w, h) ->
                targets.map { url ->
                    async {
                        gate.withPermit {
                            // Cheap re-check inside the permit: the list geometry
                            // pass may already have populated the cover tiers, so
                            // the detail pass is usually just a dither.
                            if (!covers.hasDeviceThumb(url, w, h)) {
                                runCatching { covers.deviceThumbnail(url, user, pass, w, h) }
                            }
                            yield()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** List rows first, then detail: the reader always sees a page before a book. */
        val GEOMETRIES = listOf(
            DeviceThumb.LIST_WIDTH to DeviceThumb.LIST_HEIGHT,
            DeviceThumb.DETAIL_WIDTH to DeviceThumb.DETAIL_HEIGHT,
        )
        const val CONCURRENCY = 4
        // Raised from 60. A cover is ~1.2 KB on disk at list size and ~4.3 KB at
        // detail size, so 200 books is roughly 1 MB of cache -- cheap next to
        // fetching one live inside the reader's request budget.
        const val DEFAULT_LIMIT = 200
    }
}
