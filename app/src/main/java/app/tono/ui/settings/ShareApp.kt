package app.tono.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.tono.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Ways to pass Tono on to someone else. */
object ShareApp {
    /** True when the installed app is one APK file (split installs can't be shared as a single file). */
    fun canSendFile(context: Context): Boolean = context.applicationInfo.splitSourceDirs.isNullOrEmpty()

    /**
     * Share sheet with this app's own APK, for Quick Share, Bluetooth or a chat app.
     * The installed APK is copied into the cache first so it can go through the FileProvider.
     */
    suspend fun fileIntent(context: Context): Intent = withContext(Dispatchers.IO) {
        val src = File(context.applicationInfo.sourceDir)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "tono-${BuildConfig.VERSION_NAME}.apk")
        if (!out.isFile || out.length() != src.length()) {
            dir.listFiles()?.forEach { it.delete() } // copies of older versions
            src.copyTo(out, overwrite = true)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, out.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(out.name, uri) // lets the read permission reach the picked app
        Intent.createChooser(send, "Send Tono")
    }

    /** Page with the latest release's APK, or null when this build has no release repo. */
    fun downloadUrl(repo: String): String? =
        if (repo.contains('/')) "https://github.com/$repo/releases/latest" else null

    /** Share sheet with a link to the latest release, or null when this build has no release repo. */
    fun linkIntent(repo: String): Intent? {
        val url = downloadUrl(repo) ?: return null
        val text = "Tono makes ringtones from any song. Get it here: $url"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, "Share Tono")
    }
}
