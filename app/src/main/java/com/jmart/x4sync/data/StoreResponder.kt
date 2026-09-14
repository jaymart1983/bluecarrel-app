package com.jmart.x4sync.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Everything the responder needs from the rest of the app to answer one
 * request. Re-read per request rather than captured, so changing the OPDS URL
 * in Settings takes effect on the next page turn instead of on the next launch.
 */
data class StoreContext(
    val opds: OpdsClient,
    val baseUrl: String,
    val user: String,
    val pass: String,
    /** The OPDS path the Store browses. `/new` is Calibre-Web's whole library,
     *  newest first, paginated. */
    val feedPath: String,
) {
    /**
     * Identifies the *view*, not the client object. A fresh [OpdsClient] is
     * built per call, so keying the cached snapshot on the instance would throw
     * the whole library away on every page turn.
     */
    val viewKey: String get() = "$baseUrl|$user|$feedPath"
}

/** What the responder is doing, for the UI. Purely informational. */
sealed interface StoreEvent {
    data object Idle : StoreEvent
    data class Serving(val op: String, val req: Int, val detail: String) : StoreEvent
    data class Progress(val label: String, val sent: Long, val total: Long) : StoreEvent
    data class Answered(val op: String, val detail: String) : StoreEvent
    data class Declined(val op: String, val reason: String) : StoreEvent
}

/**
 * Answers the reader's Store requests.
 *
 * ## The inversion
 *
 * BLE GATT is client-driven: this app is the central and the reader is the
 * peripheral, so the reader cannot call out. Everything else in the protocol
 * fits that — the app decides to push a book and the reader answers. A store
 * does not. The reader knows which six books are on screen and when the user
 * turned the page; only the phone can reach Calibre.
 *
 * So the `status` characteristic, which is already `notify` and already
 * subscribed to, doubles as a request channel. When the reader wants something
 * it puts a `pending` object in the status document and notifies. This class
 * watches for that and answers with an **ordinary upload** naming the same
 * `req`: `start_put`, framed writes on `data-in`, credit flow control,
 * SHA-256, `commit`. The store is a new question, not a new transport.
 *
 * ## One at a time, and what "stale" means
 *
 * `req` is a counter starting at 1, and the reader has at most one request
 * outstanding. An answer naming any other `req` is refused with `stale
 * request` and changes nothing on the reader's screen — that is what stops a
 * slow reply, arriving after the user has already paged on, from repainting
 * the page they left.
 *
 * This side keeps the same discipline:
 *
 *  - [servingReq] is the request currently being worked on. A notification
 *    naming it is a **retry** (the reader re-notifies every 4 s, up to 4 times,
 *    because a GATT notification is unacknowledged) and is ignored.
 *  - [answered] holds the ids we have already responded to, successfully or
 *    with a `catalog_error`. A retry that races our answer is ignored.
 *  - A notification naming a *different, newer* request means the user moved
 *    on. The in-flight job is cancelled — its answer would be refused as stale
 *    anyway, and the new question is what matters. Cancellation during an
 *    upload leaves the reader with a part file it drops on its own; the
 *    `cancel` op is sent by the upload path's own failure handler.
 *  - The whole session — snapshot, ids, [answered] — is dropped when the link
 *    drops, because the reader's counter restarts at 1 on the next connection
 *    and ids from the old session mean nothing.
 *
 * ## Deadlines
 *
 * The reader gives 20 s for a page or a detail and 45 s for a fetch, measured
 * from issue to the answering `start_put` — the clock stops when the upload
 * begins, and from there the ordinary transfer machinery owns the failure. So
 * the *preparation* is what is bounded here: the Calibre query, the cover
 * render, the EPUB download. A margin is subtracted because the notification
 * we acted on may have been the second or third re-notify, i.e. seconds of the
 * window are already gone.
 *
 * Exceeding it, or failing outright, sends `catalog_error` rather than going
 * quiet. Letting the reader sit out a 45 s window it will fail anyway, when we
 * already know Calibre is unreachable, is the difference between an error the
 * user can act on and a device that looks broken.
 *
 * NONE of this has been tested against real hardware — no reader has ever
 * answered these ops.
 */
class StoreResponder(
    private val ble: BleClient,
    private val covers: CoverCache,
    private val books: BookStore,
    private val context: () -> StoreContext?,
    private val onEvent: (StoreEvent) -> Unit = {},
) {

    private companion object {
        /**
         * Slack subtracted from the reader's advertised deadline. Covers the
         * re-notify we may have acted on (up to 12 s late) plus the time the
         * upload's own `start_put` round trip needs to land inside the window.
         */
        const val PREPARE_MARGIN_MS = 4_000L

        /** Never leave less than this to actually do the work. */
        const val MIN_PREPARE_MS = 3_000L

        /** Ids remembered as answered. Far more than a session can produce. */
        const val ANSWERED_MEMORY = 64
    }

    private var source: CatalogSource? = null
    private var sourceKey: String? = null

    private var job: Job? = null
    private var servingReq: Int = 0
    private val answered = linkedSetOf<Int>()

    /**
     * Starts watching. [scope] should outlive the connection — the ViewModel's
     * scope — because the subscription has to survive a reconnect.
     */
    fun attach(scope: CoroutineScope) {
        scope.launch {
            ble.connection.collect { c ->
                if (c != BleConnection.CONNECTED) endSession()
            }
        }
        scope.launch {
            ble.status.filterNotNull().collect { status ->
                onStatus(scope, status)
            }
        }
    }

    /**
     * The highest request id seen on the current link, for detecting the
     * reader's per-session counter restart. Not the same as [servingReq], which
     * is only meaningful while a request is actually in flight.
     */
    private var lastSeenReq = 0

    private fun onStatus(scope: CoroutineScope, status: DeviceStatus) {
        val pending = status.pending ?: return
        // Reboot detection FIRST: the `answered` check below returns early, and
        // would stop this running in the one case it exists for.
        //
        // Strictly less-than, and only that: the reader's ids are monotonic for
        // the life of its boot, so an id going backwards means it restarted and
        // nothing it says can be matched against what we remember. An id that
        // merely REPEATS is a re-notify of a question still outstanding, and must
        // not clear anything.
        if (pending.req < lastSeenReq) {
            // The counter went backwards or repeated, which means a new Store
            // session on the reader: BleStoreController is constructed fresh
            // every time that screen opens, so its ids restart at 1 each time.
            //
            // Compared against lastSeenReq, not servingReq: servingReq is 0 while
            // idle, so it could never fire, `answered` would keep req 1 forever,
            // and the reader's request 1 would be ignored on every future session.
            answered.clear()
            servingReq = 0
        }
        lastSeenReq = maxOf(lastSeenReq, pending.req)

        if (pending.req == servingReq) return          // a re-notify of what we are on
        if (pending.req in answered) return            // a re-notify of what we answered

        // A newer question: whatever we were preparing is for a page the user
        // has left, and the reader would refuse it as stale.
        job?.cancel()
        servingReq = pending.req
        job = scope.launch { serve(pending) }
    }

    private suspend fun endSession() {
        job?.cancel()
        job = null
        servingReq = 0
        lastSeenReq = 0
        answered.clear()
        source?.reset()
        source = null
        sourceKey = null
        onEvent(StoreEvent.Idle)
    }

    private suspend fun serve(request: PendingRequest) {
        onEvent(StoreEvent.Serving(request.op, request.req, describe(request)))
        try {
            when {
                request.isPage -> servePage(request)
                request.isDetail -> serveDetail(request)
                request.isFetch -> serveFetch(request)
                else -> decline(request, "unsupported request")
            }
            remember(request.req)
        } catch (c: CancellationException) {
            // Superseded or the link dropped. Not answered, not remembered:
            // the reader has already moved to a newer request.
            throw c
        } catch (e: Throwable) {
            remember(request.req)
            decline(request, reasonFor(e))
        } finally {
            if (servingReq == request.req) {
                servingReq = 0
                onEvent(StoreEvent.Idle)
            }
        }
    }

    // ------------------------------------------------------------ catalog_page

    private suspend fun servePage(request: PendingRequest) {
        val ctx = context() ?: return decline(request, "Calibre is not set up in the app")
        val width = request.thumbWidth.takeIf { it > 0 } ?: DeviceThumb.LIST_WIDTH
        val height = request.thumbHeight.takeIf { it > 0 } ?: DeviceThumb.LIST_HEIGHT

        val page = withPrepareBudget(request) {
            sourceFor(ctx).page(request.offset, request.limit)
        }

        if (page.books.isEmpty() && page.total > 0) {
            // The firmware refuses this container outright as `empty catalog
            // page`, so send a reason the user can read instead.
            return decline(request, "no books at that position")
        }

        val items = withPrepareBudget(request) {
            page.books.mapIndexed { i, book ->
                itemFor(
                    book = book,
                    id = sourceFor(ctx).idAt(request.offset + i) ?: book.filename,
                    ctx = ctx,
                    descMax = request.descMax,
                    width = width,
                    height = height,
                )
            }
        }

        val blob = CatalogContainer.page(
            req = request.req,
            offset = request.offset,
            total = page.total,
            items = items,
            descMax = request.descMax,
        )
        ble.uploadBytes(
            data = blob,
            kind = "catalog_page",
            req = request.req,
            commitDone = BleClient.catalogCommitDone(request.req),
        )
        onEvent(
            StoreEvent.Answered(
                request.op,
                "${items.size} book(s) from ${request.offset} of ${page.total}",
            )
        )
    }

    // ---------------------------------------------------------- catalog_detail

    private suspend fun serveDetail(request: PendingRequest) {
        val ctx = context() ?: return decline(request, "Calibre is not set up in the app")
        val id = request.id ?: return decline(request, "no book id in the request")
        val book = sourceFor(ctx).bookFor(id)
            ?: return decline(request, "that book is no longer in the catalogue")
        val width = request.thumbWidth.takeIf { it > 0 } ?: DeviceThumb.DETAIL_WIDTH
        val height = request.thumbHeight.takeIf { it > 0 } ?: DeviceThumb.DETAIL_HEIGHT

        val item = withPrepareBudget(request) {
            itemFor(book, id, ctx, request.descMax, width, height, withMetadata = true)
        }

        val blob = CatalogContainer.detail(request.req, id, item, request.descMax)
        ble.uploadBytes(
            data = blob,
            kind = "catalog_detail",
            req = request.req,
            commitDone = BleClient.catalogCommitDone(request.req),
        )
        onEvent(StoreEvent.Answered(request.op, book.title))
    }

    // ----------------------------------------------------------- catalog_fetch

    private suspend fun serveFetch(request: PendingRequest) {
        val ctx = context() ?: return decline(request, "Calibre is not set up in the app")
        val id = request.id ?: return decline(request, "no book id in the request")
        val book = sourceFor(ctx).bookFor(id)
            ?: return decline(request, "that book is no longer in the catalogue")
        if (book.downloadUrl == null) {
            return decline(request, "Calibre has no EPUB for that book")
        }
        // The reader accepts a `book` upload in store mode only as the answer
        // to the outstanding fetch AND only when `name` matches the name it
        // published, so its name is used verbatim rather than recomputed.
        val name = request.name ?: book.filename
        if (!BleClient.isSafeTransferName(name)) {
            return decline(request, "the reader will not accept that filename")
        }

        val target: File = books.fileFor(book)
        if (!books.isCached(book)) {
            onEvent(StoreEvent.Progress("Fetching \"${book.title}\" from Calibre", 0, 0))
            withPrepareBudget(request) { ctx.opds.download(book, target) }
            // This path fills the offline shelf too — the reader asked for a
            // book and the bytes are now on the phone — so describe it, or the
            // Library would list it under a title guessed from its filename.
            books.remember(book)
        }
        if (!target.exists() || target.length() <= 0L) {
            return decline(request, "the download from Calibre was empty")
        }

        val total = target.length()
        var lastShown = 0L
        ble.upload(target, name, kind = "book", req = request.req) { sent, _ ->
            if (sent - lastShown >= 32 * 1024L || sent == total) {
                lastShown = sent
                onEvent(StoreEvent.Progress("Sending \"${book.title}\"", sent, total))
            }
        }
        onEvent(StoreEvent.Answered(request.op, book.title))
    }

    // ------------------------------------------------------------------ shared

    private suspend fun itemFor(
        book: Book,
        id: String,
        ctx: StoreContext,
        descMax: Int,
        width: Int,
        height: Int,
        /** Detail requests carry the richer metadata; a page row shows none of it. */
        withMetadata: Boolean = false,
    ): CatalogContainer.Item {
        // A cover that will not fetch, decode or fit is not worth failing a
        // page over: the reader draws a row without art, which is also what an
        // entry with no cover at all does.
        val thumb = runCatching {
            covers.deviceThumbnail(book.coverUrl, ctx.user, ctx.pass, width, height)
        }.getOrNull()
        return CatalogContainer.Item(
            id = id,
            title = book.title,
            author = book.author,
            description = CatalogContainer.clampUtf8(book.description, descMax),
            filename = book.filename,
            format = book.format,
            series = if (withMetadata) book.seriesLabel else "",
            publisher = if (withMetadata) book.publisher else "",
            published = if (withMetadata) book.published else "",
            language = if (withMetadata) book.language else "",
            tags = if (withMetadata) book.tags else emptyList(),
            size = book.sizeBytes,
            thumbnail = thumb?.bytes,
        )
    }

    private suspend fun sourceFor(ctx: StoreContext): CatalogSource {
        val key = ctx.viewKey
        val existing = source
        if (existing != null && sourceKey == key) return existing
        existing?.reset()
        return CatalogSource(ctx.opds, ctx.feedPath).also {
            source = it
            sourceKey = key
        }
    }

    /**
     * Runs [block] inside what is left of the reader's window. Beyond it the
     * answer would be refused as stale anyway, so the timeout is turned into a
     * `catalog_error` by [serve]'s handler instead of a silent overrun.
     */
    private suspend fun <T> withPrepareBudget(
        request: PendingRequest,
        block: suspend () -> T,
    ): T {
        val budget = (request.timeoutMs - PREPARE_MARGIN_MS).coerceAtLeast(MIN_PREPARE_MS)
        return withTimeout(budget) { block() }
    }

    private suspend fun decline(request: PendingRequest, reason: String) {
        runCatching { ble.sendCatalogError(request.req, reason) }
        onEvent(StoreEvent.Declined(request.op, reason))
    }

    /**
     * Ids are monotonic for the reader's boot now, so this set only ever grows.
     * Bounded so a long session cannot accumulate without limit; the reader only
     * ever re-notifies the request it is currently waiting on, so a short memory
     * is sufficient to suppress duplicates.
     */
    private fun remember(req: Int) {
        answered += req
        while (answered.size > ANSWERED_MEMORY) {
            answered.remove(answered.first())
        }
    }

    /**
     * The reader truncates this to 96 bytes and shows it to the user verbatim
     * under "The phone did not answer", so it has to read as a reason.
     */
    private fun reasonFor(e: Throwable): String = when {
        e is TimeoutCancellationException -> "the phone ran out of time"
        e is BleClient.BleException -> e.message ?: "the transfer failed"
        e is java.net.UnknownHostException -> "cannot find the Calibre server"
        e is java.net.ConnectException -> "cannot reach Calibre"
        e is java.net.SocketTimeoutException -> "Calibre did not respond"
        e is java.io.IOException -> "cannot reach Calibre"
        else -> e.message?.take(80) ?: "the phone could not answer"
    }

    private fun describe(r: PendingRequest): String = when {
        r.isPage -> "books ${r.offset + 1}-${r.offset + r.limit}"
        r.isDetail -> "book details"
        r.isFetch -> r.name ?: "a book"
        else -> r.op
    }
}
