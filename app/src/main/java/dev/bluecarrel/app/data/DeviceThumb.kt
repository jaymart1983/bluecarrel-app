package dev.bluecarrel.app.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * A cover rendered into the form the reader parses.
 *
 * [bytes] is a complete 1-bit Windows BMP file, exactly
 * [DeviceThumb.bmpBytes] long. [stride] is the BMP row stride (4-byte
 * aligned), not the packed-bit stride — see [DeviceThumb].
 */
data class DeviceThumbnail(
    val width: Int,
    val height: Int,
    val stride: Int,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DeviceThumbnail && width == other.width && height == other.height &&
            stride == other.stride && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        ((width * 31 + height) * 31 + stride) * 31 + bytes.contentHashCode()
}

/**
 * Converts cover art to the 1-bit BMP the e-ink reader draws.
 *
 * ## Wire format — read this before changing anything
 *
 * The reader does **not** take a bare packed bitmap. `BleCatalog::parseContainer`
 * copies each thumbnail out of the container into its own `.bmp` on the card
 * (rejecting anything whose first two bytes are not `BM`), and the Store screen
 * then draws it with the ordinary `Bitmap` + `GfxRenderer::drawBitmap1Bit`
 * path. So what we ship is a real BMP file:
 *
 *  - 14-byte `BITMAPFILEHEADER`, 40-byte `BITMAPINFOHEADER`, then a 2-entry
 *    palette — **index 0 black `0x000000`, index 1 white `0xFFFFFF`** — so pixel
 *    data starts at offset 62.
 *  - `biBitCount = 1`, `biCompression = BI_RGB (0)`, `biPlanes = 1`.
 *  - `biHeight` positive, i.e. **bottom-up**: the first row in the file is the
 *    *last* row of the image.
 *  - **MSB first** inside each byte: bit 7 of a row's byte 0 is pixel x=0.
 *  - **A set bit is palette index 1, which is white.** A clear bit is black
 *    ink. That is the opposite of the "1 = ink" convention this file used
 *    before it emitted BMPs, and getting it backwards produces a
 *    photographic negative rather than an error.
 *  - Row stride is the BMP-mandated `((width * 1 + 31) / 32) * 4`, **not**
 *    `(width + 7) / 8`.
 *
 * ### The stride the protocol document gets wrong
 *
 * `docs/ble-transfer-protocol.md` and the comments on `BleCatalog.h` quote a
 * row stride of 9 bytes for 72 px (total 1034) and 18 for 144 px (total 3950).
 * Those numbers omit BMP's mandatory 4-byte row alignment. The firmware's own
 * reader computes `rowBytes = (width * bpp + 31) / 32 * 4` in
 * `Bitmap::parseHeaders` and then reads exactly that many bytes per row, so a
 * 9-byte-stride file would desynchronise after row 0 and run short at the end.
 * The `THUMB_EXPECTED_BYTES` / `COVER_EXPECTED_BYTES` constants that carry the
 * wrong figures are declared and never used — nothing validates a thumbnail's
 * length against them, and the container's `thumb` field is the only length
 * that matters. So this emits standards-correct BMPs: 12-byte rows at 72 px
 * (1358 bytes) and 20-byte rows at 144 px (4382 bytes), both comfortably under
 * the 8192-byte `MAX_THUMB_BYTES` cap the firmware does enforce.
 *
 * ## Geometry
 *
 * Two sizes, both from `BleCatalog.h`: [LIST_WIDTH] x [LIST_HEIGHT] for a
 * `catalog_page` row and [DETAIL_WIDTH] x [DETAIL_HEIGHT] for a
 * `catalog_detail` cover. The device sends the size it wants in `thumb_w` /
 * `thumb_h` on every request, so [render] takes them as parameters and these
 * constants are only the defaults and the cache's warm sizes.
 *
 * ## Dithering
 *
 * Default is **Atkinson**, not Floyd-Steinberg. Atkinson pushes only 6/8 of
 * each pixel's error to its neighbours; the missing quarter is thrown away,
 * which clips highlights to pure paper and shadows to pure ink. On a small
 * 1-bit thumbnail that reads as *contrast* — faces and title text stay legible
 * — where Floyd-Steinberg, which conserves all error, turns the same cover
 * into an even mid-grey stipple and grows the directional "worm" artifacts
 * that a 72x108 crop has no room to hide. [floydSteinberg] is kept (and
 * selectable) because it is the better choice for smooth gradients.
 *
 * [dither], [floydSteinberg], [atkinson] and [toBmp] are pure: 8-bit grey in,
 * BMP bytes out, no Android types. Only [render] touches `android.graphics`.
 */
object DeviceThumb {

    /** `BleCatalog::THUMB_WIDTH` — the `catalog_page` list row. */
    const val LIST_WIDTH = 72

    /** `BleCatalog::THUMB_HEIGHT`. */
    const val LIST_HEIGHT = 108

    /**
     * The shelf row's cover on the reader, drawn 1:1 and never scaled.
     *
     * Must match BleCatalog::ROW_COVER_WIDTH/HEIGHT exactly. A 1-bit dither
     * encodes tone in where the pixels fall, so resampling one is destruction
     * rather than resizing -- sending the detail size and letting the reader
     * shrink it produced a black block with a few stray dots.
     */
    const val ROW_WIDTH = 88
    const val ROW_HEIGHT = 132

    /** `BleCatalog::COVER_WIDTH` — the `catalog_detail` cover. */
    const val DETAIL_WIDTH = 144

    /** `BleCatalog::COVER_HEIGHT`. */
    const val DETAIL_HEIGHT = 216

    /** 14-byte file header + 40-byte info header + two 4-byte palette entries. */
    const val HEADER_BYTES = 62

    /** BMP row stride: 1bpp rows padded up to a 4-byte boundary. */
    fun strideOf(width: Int): Int = ((width + 31) / 32) * 4

    /** Total size of the BMP file [render] produces at this geometry. */
    fun bmpBytes(width: Int, height: Int): Int = HEADER_BYTES + strideOf(width) * height

    /** Mid-grey split point used by both error-diffusion kernels. */
    private const val THRESHOLD = 128

    enum class Dither { ATKINSON, FLOYD_STEINBERG }

    val DEFAULT_DITHER = Dither.ATKINSON

    /**
     * Full pipeline: scale [src] into a [width] x [height] frame on white,
     * preserving aspect ratio, dither to 1 bit, and wrap it in a BMP.
     *
     * Fit-inside-on-white rather than centre-crop: covers carry their title in
     * the top or bottom band and cropping to fill would routinely cut it off.
     */
    fun render(
        src: Bitmap,
        width: Int = LIST_WIDTH,
        height: Int = LIST_HEIGHT,
        mode: Dither = DEFAULT_DITHER,
    ): ByteArray = toBmp(dither(toGrayscale(src, width, height), width, height, mode), width, height)

    /**
     * Scales and letterboxes [src] into a [width] x [height] grid of 0..255
     * luminance values (0 = black), white where the cover does not reach.
     */
    fun toGrayscale(src: Bitmap, width: Int = LIST_WIDTH, height: Int = LIST_HEIGHT): IntArray {
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.WHITE)

        val sw = src.width.coerceAtLeast(1)
        val sh = src.height.coerceAtLeast(1)
        val scale = minOf(width.toFloat() / sw, height.toFloat() / sh)
        val dw = (sw * scale)
        val dh = (sh * scale)
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, Rect(0, 0, sw, sh), RectF(left, top, left + dw, top + dh), paint)

        val argb = IntArray(width * height)
        frame.getPixels(argb, 0, width, 0, 0, width, height)
        frame.recycle()

        val gray = IntArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            // Rec. 709 luma, integer weights out of 1024.
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (218 * r + 732 * g + 74 * b) shr 10
        }
        return gray
    }

    /**
     * Error-diffusion dither of [gray] (0..255, one entry per pixel, row-major,
     * top row first) into BMP pixel rows: [strideOf] bytes per row, MSB first,
     * **set bit = white**, still in top-down order. [toBmp] flips them.
     * [gray] is not modified.
     */
    fun dither(
        gray: IntArray,
        width: Int,
        height: Int,
        mode: Dither = DEFAULT_DITHER,
    ): ByteArray {
        require(gray.size >= width * height) { "gray too small for ${width}x$height" }
        return when (mode) {
            Dither.ATKINSON -> atkinson(gray, width, height)
            Dither.FLOYD_STEINBERG -> floydSteinberg(gray, width, height)
        }
    }

    /** Atkinson: 1/8 of the error to six neighbours, the other 2/8 discarded. */
    fun atkinson(gray: IntArray, width: Int, height: Int): ByteArray =
        diffuse(gray, width, height) { buf, x, y, err ->
            val e = err / 8
            spread(buf, width, height, x + 1, y, e)
            spread(buf, width, height, x + 2, y, e)
            spread(buf, width, height, x - 1, y + 1, e)
            spread(buf, width, height, x, y + 1, e)
            spread(buf, width, height, x + 1, y + 1, e)
            spread(buf, width, height, x, y + 2, e)
        }

    /** Floyd-Steinberg: 7/16, 3/16, 5/16, 1/16 — conserves the whole error. */
    fun floydSteinberg(gray: IntArray, width: Int, height: Int): ByteArray =
        diffuse(gray, width, height) { buf, x, y, err ->
            spread(buf, width, height, x + 1, y, err * 7 / 16)
            spread(buf, width, height, x - 1, y + 1, err * 3 / 16)
            spread(buf, width, height, x, y + 1, err * 5 / 16)
            spread(buf, width, height, x + 1, y + 1, err / 16)
        }

    /**
     * Wraps top-down [pixels] rows (as [dither] produces them) in a 1-bit BMP:
     * file header, info header, the black/white palette, then the rows written
     * bottom-up because `biHeight` is positive.
     */
    fun toBmp(pixels: ByteArray, width: Int, height: Int): ByteArray {
        val stride = strideOf(width)
        require(pixels.size >= stride * height) { "pixel data too small for ${width}x$height" }
        val imageBytes = stride * height
        val out = ByteArray(HEADER_BYTES + imageBytes)

        // --- BITMAPFILEHEADER (14 bytes) ---
        out[0] = 'B'.code.toByte()
        out[1] = 'M'.code.toByte()
        putLe32(out, 2, out.size)          // bfSize
        putLe32(out, 6, 0)                 // bfReserved1/2
        putLe32(out, 10, HEADER_BYTES)     // bfOffBits

        // --- BITMAPINFOHEADER (40 bytes) ---
        putLe32(out, 14, 40)               // biSize
        putLe32(out, 18, width)            // biWidth
        putLe32(out, 22, height)           // biHeight, positive = bottom-up
        putLe16(out, 26, 1)                // biPlanes
        putLe16(out, 28, 1)                // biBitCount
        putLe32(out, 30, 0)                // biCompression = BI_RGB
        putLe32(out, 34, imageBytes)       // biSizeImage
        putLe32(out, 38, 2835)             // biXPelsPerMeter (72 dpi)
        putLe32(out, 42, 2835)             // biYPelsPerMeter
        putLe32(out, 46, 2)                // biClrUsed — the 2-entry palette
        putLe32(out, 50, 2)                // biClrImportant

        // --- palette: BGRA, index 0 black, index 1 white ---
        // The firmware reads these and derives a luminance per index, so this
        // is what decides which bit value is ink. Swapping them inverts the
        // image with no error anywhere.
        out[54] = 0; out[55] = 0; out[56] = 0; out[57] = 0
        out[58] = 0xFF.toByte(); out[59] = 0xFF.toByte(); out[60] = 0xFF.toByte(); out[61] = 0

        for (y in 0 until height) {
            System.arraycopy(
                pixels, y * stride,
                out, HEADER_BYTES + (height - 1 - y) * stride,
                stride,
            )
        }
        return out
    }

    private inline fun diffuse(
        gray: IntArray,
        width: Int,
        height: Int,
        kernel: (IntArray, Int, Int, Int) -> Unit,
    ): ByteArray {
        val buf = gray.copyOf(width * height)
        val stride = strideOf(width)
        val out = ByteArray(stride * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val old = buf[row + x]
                val black = old < THRESHOLD
                if (!black) {
                    // Palette index 1 is white, so a *set* bit is paper. The
                    // array starts zeroed, which is therefore all-black; every
                    // non-ink pixel has to be written.
                    val i = y * stride + (x shr 3)
                    out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
                kernel(buf, x, y, old - if (black) 0 else 255)
            }
        }
        return out
    }

    private fun spread(buf: IntArray, width: Int, height: Int, x: Int, y: Int, err: Int) {
        if (x < 0 || x >= width || y < 0 || y >= height) return
        val i = y * width + x
        buf[i] = (buf[i] + err).coerceIn(-255, 510)
    }

    private fun putLe16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        out[at + 2] = ((value ushr 16) and 0xFF).toByte()
        out[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
