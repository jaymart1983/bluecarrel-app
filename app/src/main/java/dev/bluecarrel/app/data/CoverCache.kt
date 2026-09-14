package dev.bluecarrel.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Loads and caches OPDS cover thumbnails.
 *
 * Rolled by hand rather than pulling in Coil/Glide for one image size: CWA's
 * cover endpoints sit behind the same HTTP Basic auth as the rest of /opds, so
 * an image loader would need a custom OkHttp client injected anyway. Doing it
 * directly keeps the auth in one place and adds no dependency.
 *
 * Three tiers:
 *  - memory: LruCache sized to a fraction of the app heap, for scrolling
 *  - disk:   cacheDir/covers, so covers survive a restart and an offline launch
 *            still renders the library
 *  - device: cacheDir/device-thumbs, the 1-bit form the e-ink reader blits
 *            (see [DeviceThumb]). Produced on the fetch path, not on request:
 *            the reader's store screen must not pay a decode-and-dither per
 *            page turn, and every cover the UI shows will be asked for sooner
 *            or later. Failure to produce one is never fatal to the UI cover.
 *
 * Decoded at a bounded size — these are list thumbnails, not full-page art, and
 * full-resolution covers would evict each other out of the memory tier almost
 * immediately.
 */
class CoverCache(
    private val http: OkHttpClient,
    private val cacheDir: File,
    /** The configured server. Credentials go only to its origin. */
    private val serverUrl: () -> String = { "" },
) {
    private val memory: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(
            ((Runtime.getRuntime().maxMemory() / 1024).toInt() / 8).coerceAtLeast(4096)
        ) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

    private val dir = File(cacheDir, "covers").apply { mkdirs() }

    private val deviceThumbs = DeviceThumbCache(cacheDir)

    private fun keyOf(url: String) = url.hashCode().toString().replace("-", "n")

    suspend fun get(url: String, user: String, pass: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = keyOf(url)
            memory.get(key)?.let { return@withContext it.alsoAsDeviceThumb(key) }

            val onDisk = File(dir, key)
            if (onDisk.exists() && onDisk.length() > 0) {
                decode(onDisk.readBytes())?.also { memory.put(key, it) }
                    ?.let { return@withContext it.alsoAsDeviceThumb(key) }
            }

            val bytes = runCatching {
                val req = Request.Builder().url(url)
                // A cover anywhere but the configured server goes without credentials.
                if (HttpGuard.sameOrigin(url, serverUrl())) {
                    req.header("Authorization", Credentials.basic(user, pass))
                }
                http.newCall(req.build()).execute().use { r ->
                    if (!r.isSuccessful) null else HttpGuard.bytes(r, HttpGuard.COVER_MAX, "Cover")
                }
            }.getOrNull() ?: return@withContext null

            runCatching { onDisk.writeBytes(bytes) }
            decode(bytes)?.also { memory.put(key, it) }?.alsoAsDeviceThumb(key)
        }

    /**
     * The reader-ready 1-bit thumbnail for [book]'s cover, rendering (and, if
     * need be, downloading the cover first) when it is not already cached.
     *
     * For the BLE store screen. Suspending and safe to call from
     * `Dispatchers.IO`; it switches there itself. Returns null when the book
     * has no cover, the fetch fails, or the image will not decode — the
     * firmware is expected to draw its own placeholder in that case, since it
     * can do that far more cheaply than we can ship one over BLE.
     */
    suspend fun deviceThumbnail(
        book: Book,
        user: String,
        pass: String,
        width: Int = DeviceThumb.LIST_WIDTH,
        height: Int = DeviceThumb.LIST_HEIGHT,
    ): DeviceThumbnail? = deviceThumbnail(book.coverUrl, user, pass, width, height)

    /**
     * As above, addressed by cover URL. [width] / [height] come straight from
     * the `thumb_w` / `thumb_h` the reader published in its request, so a
     * firmware that changes its layout is followed rather than second-guessed.
     */
    suspend fun deviceThumbnail(
        coverUrl: String?,
        user: String,
        pass: String,
        width: Int = DeviceThumb.LIST_WIDTH,
        height: Int = DeviceThumb.LIST_HEIGHT,
    ): DeviceThumbnail? =
        withContext(Dispatchers.IO) {
            val url = coverUrl ?: return@withContext null
            if (width <= 0 || height <= 0) return@withContext null
            val key = keyOf(url)
            deviceThumbs.read(key, width, height)?.let { return@withContext it }
            // Cover miss (or a size we have not rendered yet): get() populates
            // the memory and disk tiers plus the two warm device geometries.
            val bitmap = get(url, user, pass) ?: return@withContext null
            deviceThumbs.ensure(key, bitmap, width, height)
            deviceThumbs.read(key, width, height)
        }

    /**
     * True when the 1-bit form the reader blits is already on disk.
     *
     * Lets the prefetcher skip work without touching the network or decoding
     * anything, which is what makes re-warming an unchanged catalogue free.
     */
    fun hasDeviceThumb(coverUrl: String?, width: Int, height: Int): Boolean {
        val url = coverUrl ?: return false
        return deviceThumbs.has(keyOf(url), width, height)
    }

    /** Bounded decode: covers render at list-thumbnail size, never full art. */
    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outHeight / sample > TARGET_PX * 2) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    /**
     * Produce the list-sized device form off the same decode, then hand the
     * bitmap back untouched. Cheap (a scale plus one error-diffusion pass over
     * 72x108) and a no-op once cached, so it stays on the caller's IO
     * dispatcher rather than needing a scope. The larger detail cover is only
     * rendered when the reader actually asks for one.
     */
    private fun Bitmap.alsoAsDeviceThumb(key: String): Bitmap = also {
        runCatching {
            deviceThumbs.ensure(key, it, DeviceThumb.LIST_WIDTH, DeviceThumb.LIST_HEIGHT)
        }
    }

    companion object { private const val TARGET_PX = 160 }
}
