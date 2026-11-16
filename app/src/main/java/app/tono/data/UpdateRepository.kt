package app.tono.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.tono.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A published version on GitHub Releases that has an APK attached. */
data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 from GitHub, when it provides one. */
    val sha256: String?,
    val pageUrl: String,
)

/** Checks GitHub Releases for a newer Tono, downloads the APK and hands it to the system installer. */
class UpdateRepository(private val context: Context, private val http: OkHttpClient) {
    val repo: String = BuildConfig.UPDATE_REPO
    val currentVersion: String = BuildConfig.VERSION_NAME
    val enabled: Boolean get() = repo.contains('/')

    private val dir get() = File(context.cacheDir, "updates")

    /** The latest release if it is newer than this app, else null. */
    suspend fun checkForUpdate(): AppRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Tono-Android")
            .build()
        val body = http.newCall(req).execute().use { res ->
            when {
                res.code == 404 -> return@withContext null // no releases yet
                res.code == 403 || res.code == 429 -> throw IOException("GitHub is limiting update checks. Try again in an hour.")
                !res.isSuccessful -> throw IOException("Couldn’t reach GitHub (error ${res.code}). Try again later.")
            }
            res.body.string()
        }
        val json = JSONObject(body)
        val version = json.optString("tag_name").removePrefix("v")
        if (version.isBlank() || !AppVersion.isNewer(version, currentVersion)) return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null
        AppRelease(
            version = version,
            notes = json.optString("body").trim(),
            apkUrl = apk.getString("browser_download_url"),
            apkName = apk.getString("name"),
            sizeBytes = apk.optLong("size"),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            pageUrl = json.optString("html_url"),
        )
    }

    /** Downloads the APK into the cache and checks it really is a newer Tono signed with the same key. */
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val out = File(dir, "tono-${release.version}.apk")
        dir.listFiles()?.forEach { if (it != out) it.delete() } // older downloads
        val tmp = File(dir, "${out.name}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val req = Request.Builder().url(release.apkUrl).header("User-Agent", "Tono-Android").build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("The download failed (error ${res.code}). Try again.")
                val total = res.body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                res.body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && hash != release.sha256) {
                throw IOException("The download was damaged. Try again.")
            }
            verify(tmp)
            out.delete()
            if (!tmp.renameTo(out)) throw IOException("Couldn’t save the update. Try again.")
            out
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun verify(apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw IOException("The downloaded file isn’t a valid app. Try again.")
        if (archive.packageName != context.packageName) throw IOException("The downloaded file isn’t Tono.")
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) throw IOException("You already have this version or newer.")
        val newKeys = archive.signingInfo?.apkContentsSigners?.toSet()
        val myKeys = installed.signingInfo?.apkContentsSigners?.toSet()
        // Android refuses updates signed with a different key; say why instead of a generic failure.
        if (!newKeys.isNullOrEmpty() && !myKeys.isNullOrEmpty() && newKeys != myKeys) {
            throw IOException("This update is signed with a different key than the installed app, so Android won’t install it over this one.")
        }
    }

    /** Android asks once whether Tono may install apps; true when that's already allowed. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** A finished download for [version], if one is still in the cache. */
    fun downloaded(version: String): File? = File(dir, "tono-$version.apk").takeIf { it.isFile }

    /** Removes leftover APKs once the update is installed. */
    fun cleanup() {
        dir.listFiles()?.forEach { f ->
            val v = f.name.removePrefix("tono-").removeSuffix(".apk").removeSuffix(".part")
            if (f.name.endsWith(".part") || !AppVersion.isNewer(v, currentVersion)) f.delete()
        }
    }
}
