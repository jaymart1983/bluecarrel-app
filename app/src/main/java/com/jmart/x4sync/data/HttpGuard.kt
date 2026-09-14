package com.jmart.x4sync.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The network rules every HTTP client in the app follows (security v2, section 4):
 * HTTPS only, credentials only to the configured server, and a size cap on every
 * response.
 */
object HttpGuard {
    const val OPDS_FEED_MAX = 5L * 1024 * 1024
    const val COVER_MAX = 2L * 1024 * 1024
    const val KOSYNC_MAX = 256L * 1024
    const val BOOK_MAX = 300L * 1024 * 1024
    const val MANIFEST_MAX = 64L * 1024
    const val FIRMWARE_MAX = 8L * 1024 * 1024

    /** An absolute https:// URL that parses. */
    fun isHttps(url: String): Boolean {
        val v = url.trim()
        return v.startsWith("https://", ignoreCase = true) && v.toHttpUrlOrNull() != null
    }

    /**
     * True when [url] may carry the account's credentials: https, and the same
     * scheme, host and port as [configured]. Anything else (a cover on a CDN, a
     * feed link to another server, plain http) is fetched without them.
     */
    fun sameOrigin(url: HttpUrl, configured: String): Boolean {
        val base = configured.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps && base.isHttps &&
            url.scheme == base.scheme && url.host == base.host && url.port == base.port
    }

    fun sameOrigin(url: String, configured: String): Boolean =
        url.toHttpUrlOrNull()?.let { sameOrigin(it, configured) } ?: false

    class TooLarge(what: String) : IOException("$what is too large")

    /** Refuses a body whose Content-Length is over [max]. */
    fun checkDeclared(resp: Response, max: Long, what: String) {
        val declared = resp.body?.contentLength() ?: -1L
        if (declared > max) throw TooLarge(what)
    }

    /** The whole body, capped at [max] bytes by Content-Length and by count. */
    fun bytes(resp: Response, max: Long, what: String = "Response"): ByteArray {
        checkDeclared(resp, max, what)
        val body = resp.body ?: return ByteArray(0)
        CappedInputStream(body.byteStream(), max, what).use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            return out.toByteArray()
        }
    }

    fun string(resp: Response, max: Long, what: String = "Response"): String =
        String(bytes(resp, max, what), Charsets.UTF_8)

    /** Throws [TooLarge] once more than [max] bytes have been read. */
    class CappedInputStream(
        input: InputStream,
        private val max: Long,
        private val what: String,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) bump(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) bump(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) bump(s)
            return s
        }

        private fun bump(n: Long) {
            count += n
            if (count > max) throw TooLarge(what)
        }
    }
}
