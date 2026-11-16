package app.tono.data

import android.net.Uri

/** A song the user can cut a tone from. */
data class Song(
    val url: String,
    val title: String,
    val artist: String,
    /** Seconds; 0 or negative when unknown. */
    val durationSec: Long,
    val source: SongSource,
    /** For filtering/sorting; may be absent (YouTube Music song results have no dates). */
    val viewCount: Long = -1,
    val uploadedEpochSec: Long? = null,
    val isLive: Boolean = false,
    val isOfficial: Boolean = false,
    /** For on-device files. */
    val localUri: Uri? = null,
    /** Album art / video thumbnail. */
    val thumbnailUrl: String? = null,
)

/** A song whose audio is already in the cache, for the Downloads list. */
data class DownloadedSong(
    val song: Song,
    val file: java.io.File,
    val sizeBytes: Long,
    val savedAtMs: Long,
)

enum class SongSource(val label: String) { YouTube("YouTube"), Device("On device") }

/** What a saved tone is used for. Maps to RingtoneManager types and MediaStore folders. */
enum class ToneUse(val label: String, val hint: String, val buttonLabel: String) {
    Ringtone("Ringtone", "Incoming calls", "Set as ringtone"),
    Notification("Notification", "Messages and alerts", "Set as notification sound"),
    Alarm("Alarm", "Wake-up sound", "Set as alarm sound"),
}

fun formatDuration(totalSec: Long): String {
    if (totalSec <= 0) return "–:––"
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 42_300 ms -> "0:42", or "0:42.3" with [tenths]. */
fun formatMs(ms: Long, tenths: Boolean = false): String {
    val clamped = ms.coerceAtLeast(0)
    val m = clamped / 60_000
    val s = (clamped % 60_000) / 1000
    return if (tenths) "%d:%02d.%d".format(m, s, (clamped % 1000) / 100) else "%d:%02d".format(m, s)
}

private val releaseTag = Regex("""\s*[(\[]\s*from\b(?:[^()\[\]]|\([^()]*\))*[)\]]""", RegexOption.IGNORE_CASE)

/** Default name for a tone: the song title without "(From "Movie")" tags. */
fun toneName(songTitle: String): String =
    songTitle.replace(releaseTag, "").replace(Regex("""\s+-\s*$"""), "").trim().ifEmpty { songTitle.trim() }
