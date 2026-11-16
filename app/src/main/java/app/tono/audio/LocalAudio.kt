package app.tono.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.tono.data.Song
import app.tono.data.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Imports an audio file picked from the device ("On device" tab) into the cache. */
class LocalAudio(private val context: Context, cacheDir: File) {

    /** Copies of picked files live here, apart from YouTube downloads, and are cleaned up on start. */
    private val dir = File(cacheDir, "local")

    suspend fun describe(uri: Uri): Song = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "Audio file"
        var title = name.substringBeforeLast('.')
        var artist = "On device"
        var durationSec = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            durationSec = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000
        } catch (_: Exception) {
            // Unreadable tags are fine; the file name is enough.
        } finally {
            runCatching { retriever.release() }
        }
        Song(url = uri.toString(), title = title, artist = artist, durationSec = durationSec,
            source = SongSource.Device, localUri = uri)
    }

    suspend fun copyToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val ext = name(uri)?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 } ?: "audio"
        val out = File(dir, "local-${sha1(uri.toString())}.$ext")
        if (out.length() > 0) {
            // Mark it (and its waveform) as just used, so cleanup() keeps files you're still using.
            val now = System.currentTimeMillis()
            out.setLastModified(now)
            File(out.path + ".wave").takeIf { it.exists() }?.setLastModified(now)
            return@withContext out
        }
        val tmp = File(dir, out.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("Couldn't open that file.")
        if (!tmp.renameTo(out)) throw IOException("Couldn't copy that file.")
        out
    }

    /** Drops copies (and their waveforms) not used for a day. */
    suspend fun cleanup(maxAgeMs: Long = 24L * 3600 * 1000) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.filter { it.lastModified() < cutoff || it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun sha1(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private fun name(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
