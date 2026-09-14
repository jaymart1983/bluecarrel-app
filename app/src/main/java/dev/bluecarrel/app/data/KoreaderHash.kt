package dev.bluecarrel.app.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * KOReader's document identifier, as both CrossPoint and Calibre-Web Automated
 * compute it: a partial MD5 over 1 KB chunks at 12 escalating offsets.
 *
 * Ported from lib/KOReaderSync/KOReaderDocumentId.{h,cpp}.
 *
 * KOReader's other mode, a plain MD5 of the filename, is deliberately not
 * implemented. CWA's sync server looks a document up in `book_format_checksums`,
 * whose rows are the *content* partial-MD5
 * (`cps/progress_syncing/checksums/koreader.py`). It has no notion of a filename
 * hash, so a filename id stores progress under an id nothing else ever asks for,
 * and reading it back silently returns "no progress" forever.
 *
 * The first offset is 0, not 256. KOReader's Lua computes offsets as
 * `bit.lshift(1024, 2 * i)` from i = -1, and LuaJIT masks the shift count to its
 * low 5 bits: 2 * -1 = -2 masks to 30, so `1024 << 30` overflows 32 bits to
 * **0**. CrossPoint's getOffset() returns 0 for i < 0, and CWA reproduces the
 * same masking explicitly. All three agree on 0, 1K, 4K, 16K, ... — only
 * CrossPoint's own header comment (which claims 256) is wrong. [fromContent]
 * reproduces CWA's stored checksum exactly.
 *
 * The catch is that the hash needs the bytes, so a book must be downloaded
 * before its progress can be looked up.
 */
object KoreaderHash {

    private const val CHUNK_SIZE = 1024
    private const val OFFSET_COUNT = 12

    /**
     * Offset for index i. Mirrors CrossPoint's getOffset() and CWA's masked
     * LuaJIT shift, both of which yield 0 for i < 0.
     */
    private fun offsetFor(i: Int): Long =
        if (i < 0) 0L else CHUNK_SIZE.toLong() shl (2 * i)

    fun fromContent(file: File): String {
        val digest = md5()
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val buf = ByteArray(CHUNK_SIZE)
            // i = -1 .. 10, i.e. OFFSET_COUNT iterations
            for (i in -1 until OFFSET_COUNT - 1) {
                val offset = offsetFor(i)
                if (offset >= size) continue
                raf.seek(offset)
                val toRead = minOf(CHUNK_SIZE.toLong(), size - offset).toInt()
                val read = raf.read(buf, 0, toRead)
                if (read > 0) digest.update(buf, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun md5() = MessageDigest.getInstance("MD5")

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
