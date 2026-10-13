package dev.bluecarrel.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Where THIS APP's own builds are published, and the one thing the releases API
 * is asked for.
 *
 * Fixed rather than configurable, unlike the reader's firmware page: an app that
 * let its own update source be edited would be a way to install a different app
 * over it. The reader firmware page stays a setting because the reader validates
 * what it is sent (signature, version, digest) before flashing anything.
 *
 * `per_page=10` is the newest ten, which is a page of about 60 KB. The "latest
 * release" endpoint is no use here: every build is published as a PRE-RELEASE
 * (tag `v<version>-test`) and that endpoint ignores pre-releases, so it answers
 * 404 for this repository. The list endpoint includes them, newest first.
 */
const val APP_RELEASES_API =
    "https://api.github.com/repos/jaymart1983/bluecarrel-app/releases?per_page=10"

/** GitHub's hourly cap for unauthenticated requests (about 60) has been hit. */
class RateLimited : IOException("Rate limited by GitHub")

/**
 * A dotted numeric version, compared number by number.
 *
 * The whole point of this type: `"1.0.9" < "1.0.20"` is FALSE as strings, so a
 * string comparison would have called 1.0.9 the newer build and offered a
 * downgrade. (The firmware's own versions are `yyyyMMdd.HHmm` stamps, which do
 * sort as text -- this app's do not.) Missing components count as 0, so 1.0 and
 * 1.0.0 are the same version.
 */
class AppVersion private constructor(private val parts: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /**
         * A release tag or a plain version: `v1.0.20-test`, `v1.0.20`, `1.0.20`.
         * Anything after the numbers (the `-test` every build carries) is a
         * qualifier and is ignored -- pre-release is how this app ships.
         */
        private val SHAPE = Regex("^v?(\\d{1,5}(?:\\.\\d{1,5}){0,3})(?:[-+].*)?$")

        fun parse(raw: String?): AppVersion? {
            val m = SHAPE.find(raw?.trim() ?: return null) ?: return null
            return AppVersion(m.groupValues[1].split('.').map { it.toInt() })
        }
    }
}

/**
 * The newest published build of this app, as the releases API describes it.
 *
 * Cached in DataStore between launches (see `AppUpdateStore`), so the badge is
 * right at launch without a request.
 */
data class AppRelease(
    /** The version from the tag, e.g. "1.0.20". */
    val version: String,
    val tag: String,
    /** https URL of the APK asset. */
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    /** https URL of the SHA256SUMS asset, when the release carries one. */
    val sumsUrl: String?,
    /** The `SHA-256: <hex>` line from the release notes; the fallback digest. */
    val notesSha256: String?,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("tag", tag)
        put("apk_url", apkUrl)
        put("apk_name", apkName)
        put("size", sizeBytes)
        put("sums_url", sumsUrl)
        put("notes_sha256", notesSha256)
    }.toString()

    companion object {
        /** Reads back [toJson]; null for anything that will not parse, so a stale cache is simply ignored. */
        fun fromJson(raw: String): AppRelease? = runCatching {
            val j = JSONObject(raw)
            validated(
                AppRelease(
                    version = j.getString("version"),
                    tag = j.getString("tag"),
                    apkUrl = j.getString("apk_url"),
                    apkName = j.getString("apk_name"),
                    sizeBytes = j.getLong("size"),
                    sumsUrl = j.optString("sums_url").ifBlank { null },
                    notesSha256 = j.optString("notes_sha256").ifBlank { null },
                )
            )
        }.getOrNull()

        /**
         * The newest release in the API's answer.
         *
         * PRE-RELEASES ARE INCLUDED ON PURPOSE: every build of this app is
         * published as one, so filtering them out would leave nothing. Drafts are
         * skipped -- they are not published, and an unauthenticated request does
         * not see them anyway. The newest is chosen by comparing parsed versions
         * rather than by taking the first element, so the answer does not depend
         * on GitHub's ordering.
         *
         * Throws [IllegalArgumentException] for an answer with no usable release,
         * which is a different message from "could not reach GitHub".
         */
        fun newest(json: String): AppRelease {
            val arr = JSONArray(json)
            var best: AppRelease? = null
            var bestVersion: AppVersion? = null
            var sawRelease = false
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optBoolean("draft", false)) continue
                sawRelease = true
                val tag = r.optString("tag_name").trim()
                val version = AppVersion.parse(tag) ?: continue
                if (bestVersion != null && version <= bestVersion) continue
                val release = runCatching { fromReleaseObject(r, tag, version.toString()) }.getOrNull() ?: continue
                best = release
                bestVersion = version
            }
            return best ?: throw IllegalArgumentException(
                if (sawRelease) "No published build with an APK" else "No published builds"
            )
        }

        /** Throws when the release has no usable APK asset; that release is then skipped. */
        private fun fromReleaseObject(r: JSONObject, tag: String, version: String): AppRelease {
            val assets = r.optJSONArray("assets") ?: JSONArray()
            var apk: JSONObject? = null
            var sums: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                when {
                    // The first .apk only: a release carries one.
                    apk == null && name.endsWith(".apk", ignoreCase = true) -> apk = a
                    name == "SHA256SUMS" -> sums = a
                }
            }
            val apkAsset = apk ?: throw IllegalArgumentException("no APK asset")
            val notes = r.optString("body")
            return validated(
                AppRelease(
                    version = version,
                    tag = tag,
                    apkUrl = apkAsset.optString("browser_download_url"),
                    apkName = apkAsset.optString("name"),
                    sizeBytes = apkAsset.optLong("size", 0L),
                    sumsUrl = sums?.optString("browser_download_url")?.takeIf { HttpGuard.isHttps(it) },
                    notesSha256 = NOTES_DIGEST.find(notes)?.groupValues?.get(1)?.lowercase(),
                )
            )
        }

        /**
         * Everything downstream relies on: https asset URLs, a size inside the
         * cap, and a version made of digits and dots (which is also what makes
         * the staged filename safe to build from it).
         */
        private fun validated(r: AppRelease): AppRelease {
            require(HttpGuard.isHttps(r.apkUrl)) { "the APK is not published over https" }
            require(r.version.matches(Regex("[0-9.]{1,24}"))) { "malformed version" }
            require(r.apkName.matches(Regex("[A-Za-z0-9._-]{1,120}\\.apk"))) { "malformed APK name" }
            require(r.sizeBytes > 0 && r.sizeBytes <= HttpGuard.APK_MAX) { "the APK is the wrong size" }
            require(r.notesSha256 == null || r.notesSha256.matches(HEX64)) { "malformed SHA-256 in the notes" }
            return r
        }

        /** The `SHA-256: <hex>` line every release's notes carry. */
        private val NOTES_DIGEST = Regex("SHA-?256\\s*:\\s*([0-9a-fA-F]{64})")
        private val HEX64 = Regex("[0-9a-f]{64}")

        /**
         * The digest for [apkName] out of a `sha256sum` style SHA256SUMS file:
         * `<64 hex>  <filename>` per line. Null when the file does not mention it.
         */
        fun digestFromSums(text: String, apkName: String): String? {
            for (line in text.lineSequence()) {
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) continue
                val hex = parts[0].lowercase()
                // "*name" is the binary-mode marker sha256sum writes.
                val name = parts[1].trim().removePrefix("*")
                if (name == apkName && hex.matches(HEX64)) return hex
            }
            return null
        }
    }
}

/**
 * Handing a downloaded APK to Android's own installer.
 *
 * What protects the phone here is ANDROID, not this app: the installer refuses
 * an APK signed with a different key than the installed one, so a build that is
 * not the same developer's cannot replace this app whatever it claims. The
 * SHA-256 this app checks first is for a corrupted or truncated download -- a
 * half-downloaded APK that the installer would reject with "App not installed"
 * and no explanation.
 */
object ApkInstall {

    const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * Where a downloaded update is staged: app-private storage, never shared
     * storage. An APK another app can rewrite between the checksum and the
     * install is an APK that was not really checked.
     */
    fun stagingDir(context: Context): File =
        File(context.filesDir, "updates").apply { mkdirs() }

    /** [version] is digits and dots only (see `AppRelease.validated`), so this name is safe. */
    fun stagedApk(context: Context, version: String): File =
        File(stagingDir(context), "bluecarrel-$version.apk")

    /** Drops staged APKs from earlier attempts, except [keep]. */
    fun clearStaged(context: Context, keep: String? = null) {
        stagingDir(context).listFiles()?.forEach { f ->
            if (f.isFile && f.name != keep) runCatching { f.delete() }
        }
    }

    /**
     * The user has allowed installs from this app.
     *
     * Required from Android 8; minSdk here is 29, so it is always asked. Without
     * it the installer opens on a dead end, which is why this is checked before
     * the intent rather than after.
     */
    fun allowed(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** The system screen that grants it, for this app specifically. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * ACTION_VIEW on the APK through FileProvider, which is what opens the
     * system installer and lets it ask the user. Null when the file has gone or
     * the provider will not grant it.
     *
     * Needs the `updates` entry in res/xml/file_paths.xml and the
     * REQUEST_INSTALL_PACKAGES permission in the manifest.
     */
    fun installIntent(context: Context, apk: File): Intent? {
        if (!apk.exists() || apk.length() == 0L) return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrNull() ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/**
 * Refuses a response that ended up somewhere other than https.
 *
 * GitHub's `browser_download_url` redirects to its object store, and OkHttp
 * follows https -> http redirects by default. The URL that was checked is not
 * necessarily the URL that answered, so the one that answered is checked too.
 */
fun requireHttpsResponse(resp: Response) {
    if (!resp.request.url.isHttps) throw IOException("the download was redirected to a plain-http address")
}
