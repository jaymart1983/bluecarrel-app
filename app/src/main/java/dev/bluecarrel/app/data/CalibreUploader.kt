package dev.bluecarrel.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Uploads an EPUB to Calibre-Web Automated through its web upload form.
 *
 * /upload takes no HTTP Basic auth: it wants a Flask-Login session and a CSRF
 * token. So this signs in the way a browser does -- GET /login for a token,
 * POST the form, GET a signed-in page for a fresh token, POST the file -- on a
 * client of its own:
 *
 *  - cookies live in an in-memory jar that takes and gives cookies only for the
 *    configured origin, and is dropped when the upload ends;
 *  - redirects are followed by hand, and only while they stay on that origin;
 *  - the server must be https ([HttpGuard]);
 *  - nothing here logs, and no error message carries a password, cookie or token.
 *
 * CWA imports asynchronously (its ingest folder), so success here means Calibre
 * took the file, not that the book is in the catalogue yet.
 */
class CalibreUploader(
    http: OkHttpClient,
    /** The CWA base, e.g. https://books.example.com ([Config.base]). */
    baseUrl: String,
    private val user: String,
    private val pass: String,
) {
    /** Calibre refused: sign-in failed, no form, or the upload was turned down. */
    class Refused(message: String) : IOException(message)

    private val origin = baseUrl.trim().trimEnd('/')
    private val jar = OriginCookieJar(origin)
    private val client = http.newBuilder()
        .cookieJar(jar)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Signs in, uploads [file] as [filename], and signs nothing out: the jar is discarded. */
    suspend fun upload(file: File, filename: String) = withContext(Dispatchers.IO) {
        if (!HttpGuard.isHttps(origin)) throw Refused(HTTPS_REQUIRED)
        try {
            val loginPage = get("/login")
            val loginToken = csrfToken(loginPage.second) ?: throw Refused("Calibre sign-in page not found")

            val form = FormBody.Builder()
                .add("username", user)
                .add("password", pass)
                .add("remember_me", "on")
                .add("csrf_token", loginToken)
                .build()
            post("/login", form).use { resp ->
                val next = resp.header("Location")?.let { resolve(resp.request.url, it) }
                if (next != null && next.encodedPath.trimEnd('/').endsWith("/login")) throw Refused("Calibre sign-in failed")
            }

            // Signed in only if a signed-in page comes back instead of the login form.
            val home = get("/")
            if (home.first.encodedPath.trimEnd('/').endsWith("/login")) throw Refused("Calibre sign-in failed")
            val token = csrfToken(home.second) ?: throw Refused("Calibre has no upload form for this account")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("csrf_token", token)
                .addFormDataPart("btn-upload", filename, file.asRequestBody(EPUB.toMediaType()))
                .build()
            val request = Request.Builder()
                .url("$origin/upload")
                .header("X-CSRFToken", token)
                .header("Referer", "$origin/")
                .header("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isRedirect || !resp.isSuccessful) throw Refused("Calibre did not accept the book")
                val text = HttpGuard.string(resp, PAGE_MAX, "Calibre response")
                val location = runCatching { JSONObject(text).optString("location") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw Refused("Calibre did not accept the book")
                // A refused upload sends the browser back to the start page; an
                // accepted one on to the task list (or the book).
                val to = resolve(resp.request.url, location)
                val home = origin.toHttpUrlOrNull()?.encodedPath?.trim('/') ?: ""
                if (to == null || to.encodedPath.trim('/') == home) throw Refused("Calibre did not accept the book")
            }
        } finally {
            jar.clear()
        }
    }

    /** GET, following same-origin redirects. The final URL and the page, capped. */
    private fun get(path: String): Pair<HttpUrl, String> {
        var url = ("$origin$path").toHttpUrlOrNull() ?: throw Refused("Server URL is not a valid address")
        repeat(MAX_REDIRECTS) {
            if (!HttpGuard.sameOrigin(url, origin)) throw Refused("Calibre redirected to another server")
            val req = Request.Builder().url(url).header("Accept", "text/html").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isRedirect) {
                    url = resp.header("Location")?.let { resolve(resp.request.url, it) }
                        ?: throw Refused("Calibre sent a bad redirect")
                    return@repeat
                }
                if (!resp.isSuccessful) throw IOException("Calibre answered ${resp.code}")
                return url to HttpGuard.string(resp, PAGE_MAX, "Calibre page")
            }
        }
        throw Refused("Calibre redirected too many times")
    }

    private fun post(path: String, body: FormBody): Response {
        val url = ("$origin$path").toHttpUrlOrNull() ?: throw Refused("Server URL is not a valid address")
        if (!HttpGuard.sameOrigin(url, origin)) throw Refused(HTTPS_REQUIRED)
        return client.newCall(
            Request.Builder().url(url).header("Referer", "$origin/").post(body).build()
        ).execute()
    }

    private fun resolve(base: HttpUrl, href: String): HttpUrl? = base.resolve(href)

    /** The value of the page's hidden `csrf_token` input, whichever order its attributes are in. */
    private fun csrfToken(html: String): String? {
        val tag = Regex("<input[^>]*name=[\"']csrf_token[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)?.value
            ?: return null
        return Regex("value=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
    }

    /** Cookies for [origin] only, in memory, gone with [clear]. */
    private class OriginCookieJar(private val origin: String) : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (!HttpGuard.sameOrigin(url, origin)) return
            val now = System.currentTimeMillis()
            synchronized(this.cookies) {
                for (c in cookies) {
                    this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                    if (c.expiresAt > now) this.cookies += c
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!HttpGuard.sameOrigin(url, origin)) return emptyList()
            val now = System.currentTimeMillis()
            return synchronized(cookies) { cookies.filter { it.expiresAt > now && it.matches(url) } }
        }

        fun clear() = synchronized(cookies) { cookies.clear() }
    }

    private companion object {
        const val EPUB = "application/epub+zip"
        const val PAGE_MAX = 2L * 1024 * 1024
        const val MAX_REDIRECTS = 5
    }
}
