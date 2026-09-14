package dev.bluecarrel.app.data

import android.graphics.Bitmap
import java.io.File
import java.util.Collections

/**
 * Disk cache of ready-to-draw 1-bit BMP cover thumbnails (see [DeviceThumb] for
 * the byte format).
 *
 * Lives in `cacheDir/device-thumbs/<key>-<w>x<h>.bmp`, keyed by the same cover
 * key [CoverCache] uses plus the geometry, because the Store asks for two
 * sizes: 72x108 for a `catalog_page` row and 144x216 for a `catalog_detail`
 * cover. Keying on size rather than sharing one file means a page turn never
 * evicts the detail cover it just used, and a firmware that changes `thumb_w` /
 * `thumb_h` simply starts writing new files instead of serving wrong-sized ones.
 *
 * The OS may evict the whole directory whenever it likes — that is fine, every
 * file is regenerable from the cover cache, and regenerating one costs a few
 * milliseconds.
 *
 * No eviction policy of our own: a list entry is 1358 bytes and a detail cover
 * 4382, so an entire large library is a couple of megabytes. The only staleness
 * that matters is a geometry change, and that is handled by the filename plus
 * the length check in [read] — a file that is not exactly the expected length
 * is treated as absent and rewritten.
 */
class DeviceThumbCache(cacheDir: File) {

    private val dir = File(cacheDir, "device-thumbs").apply { mkdirs() }

    /** Keys known to be on disk at the right size — saves a stat per bind. */
    private val present: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    private fun nameFor(key: String, width: Int, height: Int) = "$key-${width}x$height.bmp"

    private fun fileFor(key: String, width: Int, height: Int) =
        File(dir, nameFor(key, width, height))

    /** The cached thumbnail for [key] at this geometry, or null if absent/stale. */
    fun read(key: String, width: Int, height: Int): DeviceThumbnail? {
        val expected = DeviceThumb.bmpBytes(width, height).toLong()
        val f = fileFor(key, width, height)
        if (f.length() != expected) {
            present.remove(nameFor(key, width, height))
            return null
        }
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (bytes.size.toLong() != expected) return null
        present.add(nameFor(key, width, height))
        return DeviceThumbnail(width, height, DeviceThumb.strideOf(width), bytes)
    }

    /** True if [key] already has a thumbnail at this geometry. */
    fun has(key: String, width: Int, height: Int): Boolean {
        val name = nameFor(key, width, height)
        if (name in present) return true
        val ok = fileFor(key, width, height).length() ==
            DeviceThumb.bmpBytes(width, height).toLong()
        if (ok) present.add(name)
        return ok
    }

    /**
     * Renders and stores the device form of [source] unless it is already
     * cached. Called on the cover-fetch path so the conversion is paid once,
     * off the store screen's page turns. Blocking; call from `Dispatchers.IO`.
     */
    fun ensure(key: String, source: Bitmap, width: Int, height: Int): Boolean {
        if (has(key, width, height)) return true
        val bytes = runCatching { DeviceThumb.render(source, width, height) }.getOrNull()
            ?: return false
        val tmp = File(dir, "$key-${width}x$height.tmp")
        val target = fileFor(key, width, height)
        val ok = runCatching {
            tmp.writeBytes(bytes)
            target.delete()
            // Rename so a half-written file can never be read as a thumbnail.
            tmp.renameTo(target)
        }.getOrDefault(false)
        if (!ok) runCatching { tmp.delete() } else present.add(nameFor(key, width, height))
        return ok
    }
}
