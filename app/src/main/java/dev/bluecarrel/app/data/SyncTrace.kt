package dev.bluecarrel.app.data

import org.json.JSONObject
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

    private fun lineFor(s: Step): String =
        String.format(Locale.US, "%+.1f ", (s.startedAt - startedAt) / 1000.0) +
            stepText(s.name, s.bytes, s.durationMs, s.note)

    companion object {
        private const val MAX_STEPS = 150

        fun duration(ms: Long): String =
            if (ms < 1000L) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)

        fun size(bytes: Long): String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        }

        /** One step without its offset: "upload firmware 4.5 MB 150.0 s 30.0 KB/s  frame 500 ×9346". */
        fun stepText(name: String, bytes: Long, durationMs: Long, note: String): String = buildString {
            append(name)
            if (bytes > 0L) append(' ').append(size(bytes))
            if (durationMs > 0L || bytes > 0L) append(' ').append(duration(durationMs))
            if (bytes > 0L && durationMs > 0L) {
                append(' ').append(String.format(Locale.US, "%.1f KB/s", bytes * 1000.0 / durationMs / 1024.0))
            }
            if (note.isNotBlank()) append("  ").append(note)
        }

        /** A step outside any sync, with its clock time: the Diagnostics "last transfer" line. */
        fun clockLine(at: Long, name: String, bytes: Long, durationMs: Long, note: String): String =
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at)) + " " + stepText(name, bytes, durationMs, note)
    }
}

/**
 * How the last sync ended, for the line the sync bar keeps afterwards: a sync
 * is over too quickly to catch while it runs. The clock is left to the screen,
 * which knows the user's 12/24-hour setting.
 */
data class SyncSummary(
    val finishedAt: Long,
    val durationMs: Long,
    val result: Result,
    /** "1 book sent, 2 positions moved", "up to date", "reader disconnected". */
    val detail: String,
    /** Something reached the reader, or failed to. "Up to date" did neither. */
    val changed: Boolean,
) {
    enum class Result { DONE, INCOMPLETE, STOPPED }

    fun line(clock: String): String = when (result) {
        Result.DONE ->
            "Synced $clock · ${String.format(Locale.getDefault(), "%.1f s", durationMs / 1000.0)} · $detail"
        Result.INCOMPLETE -> "Sync incomplete $clock · $detail"
        Result.STOPPED -> "Sync stopped $clock · $detail"
    }

    fun toJson(): String = JSONObject()
        .put("finished_at", finishedAt)
        .put("duration_ms", durationMs)
        .put("result", result.name)
        .put("detail", detail)
        .put("changed", changed)
        .toString()

    companion object {
        fun fromJson(json: String): SyncSummary? = runCatching {
            val j = JSONObject(json)
            SyncSummary(
                finishedAt = j.getLong("finished_at"),
                durationMs = j.optLong("duration_ms"),
                result = Result.valueOf(j.getString("result")),
                detail = j.getString("detail"),
                changed = j.optBoolean("changed"),
            )
        }.getOrNull()
    }
}
