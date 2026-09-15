package dev.bluecarrel.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where one sync spent its time: a line per step with when it started (seconds
 * after the sync began), how long it took and the bytes it moved.
 *
 * Steps from concurrent work (kosync, the catalogue, covers) overlap the
 * reader's steps; the start offset is what shows that. Written from the main
 * thread and from Bluetooth callbacks, so the list is synchronised.
 */
class SyncTrace(val startedAt: Long = System.currentTimeMillis()) {

    data class Step(
        val name: String,
        val startedAt: Long,
        val durationMs: Long,
        val bytes: Long,
        val note: String,
    )

    private val steps = mutableListOf<Step>()

    @Volatile
    var finishedAt: Long = 0L
        private set

    fun add(name: String, startedAt: Long, durationMs: Long, bytes: Long = 0L, note: String = "") {
        synchronized(steps) {
            if (steps.size < MAX_STEPS) steps += Step(name, startedAt, durationMs.coerceAtLeast(0L), bytes, note)
        }
    }

    /** A moment rather than a span: a PHY report, a priority request. */
    fun event(name: String, note: String) = add(name, System.currentTimeMillis(), 0L, 0L, note)

    fun finish() {
        if (finishedAt == 0L) finishedAt = System.currentTimeMillis()
    }

    /** Nothing but link events was recorded. */
    fun isEmpty(): Boolean = synchronized(steps) { steps.all { it.durationMs == 0L && it.bytes == 0L } }

    fun lines(): List<String> {
        val snapshot = synchronized(steps) { steps.sortedBy { it.startedAt } }
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(startedAt))
        val head = if (finishedAt > 0L) "sync $clock, ${duration(finishedAt - startedAt)} total"
        else "sync $clock, running"
        return listOf(head) + snapshot.map(::lineFor)
    }

    private fun lineFor(s: Step): String = buildString {
        append(String.format(Locale.US, "%+.1f ", (s.startedAt - startedAt) / 1000.0))
        append(s.name)
        if (s.bytes > 0L) append(' ').append(size(s.bytes))
        if (s.durationMs > 0L || s.bytes > 0L) append(' ').append(duration(s.durationMs))
        if (s.bytes > 0L && s.durationMs > 0L) {
            append(' ').append(String.format(Locale.US, "%.1f KB/s", s.bytes * 1000.0 / s.durationMs / 1024.0))
        }
        if (s.note.isNotBlank()) append("  ").append(s.note)
    }

    companion object {
        private const val MAX_STEPS = 150

        fun duration(ms: Long): String =
            if (ms < 1000L) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)

        fun size(bytes: Long): String =
            if (bytes < 1024L) "$bytes B" else String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }
}
