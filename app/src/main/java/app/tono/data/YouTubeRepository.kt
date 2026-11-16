package app.tono.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Downloaded audio plus the song details YouTube reported. */
class DownloadedAudio(val file: File, val song: Song)

/** A playable audio stream URL and the User-Agent YouTube expects for it. */
class StreamSource(val url: String, val userAgent: String, val song: Song)

/** A page of search results plus the handle to fetch the next one. */
class SearchPage(
    val songs: List<Song>,
    internal val query: SearchQueryHandler,
    internal val nextPage: Page?,
    internal val songsOnly: Boolean,
) {
    val hasMore: Boolean get() = nextPage != null
}

/**
 * YouTube / YouTube Music access via NewPipeExtractor: search, stream URLs for previews,
 * and downloading an audio-only stream to the cache for trimming.
 */
class YouTubeRepository(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {
    private val yt get() = ServiceList.YouTube
    private val audioDir get() = File(cacheDir, "audio").apply { mkdirs() }

    suspend fun search(query: String, filters: SearchFilters): SearchPage = withContext(Dispatchers.IO) {
        val contentFilter = if (filters.songsOnly) {
            YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        } else {
            YoutubeSearchQueryHandlerFactory.VIDEOS
        }
        val handler = yt.searchQHFactory.fromQuery(searchQuery(query, filters), listOf(contentFilter), "")
        val info = SearchInfo.getInfo(yt, handler)
        SearchPage(
            songs = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toSong(filters.songsOnly) },
            query = handler,
            nextPage = info.nextPage,
            songsOnly = filters.songsOnly,
        )
    }

    suspend fun more(page: SearchPage): SearchPage = withContext(Dispatchers.IO) {
        val next = page.nextPage ?: return@withContext SearchPage(emptyList(), page.query, null, page.songsOnly)
        val items = SearchInfo.getMoreItems(yt, page.query, next)
        SearchPage(
            songs = items.items.filterIsInstance<StreamInfoItem>().map { it.toSong(page.songsOnly) },
            query = page.query,
            nextPage = items.nextPage,
            songsOnly = page.songsOnly,
        )
    }

    /** The already-downloaded audio for [song], if any. */
    fun cachedFile(song: Song): File? {
        val prefix = sha1(song.url) + "-"
        return audioDir.listFiles { f ->
            f.name.startsWith(prefix) && f.length() > 0 && (f.extension == "m4a" || f.extension == "webm")
        }?.firstOrNull()
    }

    /** [song] with the details YouTube returned filled in. Keeps song.url: it's the download cache key. */
    private fun StreamInfo.resolve(song: Song): Song = song.copy(
        title = name ?: song.title,
        artist = uploaderName.orEmpty().removeSuffix(" - Topic").ifEmpty { song.artist },
        durationSec = duration.takeIf { it > 0 } ?: song.durationSec,
        thumbnailUrl = song.thumbnailUrl ?: thumbnails.maxByOrNull { it.height }?.url,
    )

    /** Resolves the best audio stream for [song] without downloading it (used for previews). */
    suspend fun resolveStream(song: Song, filters: SearchFilters): StreamSource = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(yt, song.url)
        if (info.streamType.isLive()) throw IOException("Live streams can't be made into tones.")
        val stream = pickAudioStream(info.audioStreams, filters)
            ?: throw IOException("No playable audio found for this song.")
        val resolved = info.resolve(song)
        StreamSource(stream.content, userAgentFor(stream.content), resolved)
    }

    /**
     * Downloads the best matching audio-only stream into the cache and returns the file.
     * [onProgress] receives 0f..1f, or -1f when the size is unknown.
     */
    suspend fun downloadAudio(
        song: Song,
        filters: SearchFilters,
        onProgress: (Float) -> Unit,
    ): DownloadedAudio = withContext(Dispatchers.IO) {
        // Already downloaded this song? Skip asking YouTube again.
        cachedFile(song)?.let { cached ->
            onProgress(1f)
            return@withContext DownloadedAudio(cached, readMeta(cached) ?: song)
        }

        val info = StreamInfo.getInfo(yt, song.url)
        if (info.streamType.isLive()) throw IOException("Live streams can't be made into tones.")
        val stream = pickAudioStream(info.audioStreams, filters)
            ?: throw IOException("No downloadable audio found for this video.")
        val resolved = info.resolve(song)

        val ext = when (stream.format) {
            MediaFormat.M4A -> "m4a"
            MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS -> "webm"
            else -> stream.format?.suffix ?: "audio"
        }
        val out = File(audioDir, "${sha1(song.url)}-${stream.itag}.$ext")
        // A temp file per attempt: a cancelled attempt that's still winding down can never write
        // into the file a retry is filling. Leftovers are removed at startup (cleanupPartials).
        val tmp = File(audioDir, "${out.name}.${System.nanoTime()}.part")
        val total = stream.itagItem?.contentLength?.takeIf { it > 0 } ?: -1L
        val userAgent = userAgentFor(stream.content)
        val job = coroutineContext[Job]

        try {
            // YouTube throttles large single requests, so fetch in ranged chunks.
            java.io.RandomAccessFile(tmp, "rw").use { sink ->
                var pos = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val req = okhttp3.Request.Builder()
                        .url(stream.content)
                        .header("User-Agent", userAgent)
                        .header("Range", "bytes=$pos-${pos + CHUNK - 1}")
                        .build()
                    val call = http.newCall(req)
                    // Reads below block; cancelling the coroutine cancels the request so they stop at once.
                    val cancelHook = job?.invokeOnCompletion { if (it != null) call.cancel() }
                    val readThisChunk = try {
                        call.execute().use { resp ->
                            when {
                                resp.code == 416 -> -1L // past the end
                                !resp.isSuccessful -> throw IOException("Download failed (HTTP ${resp.code}).")
                                else -> {
                                    // 200 = the server ignored Range and is sending the whole file: start over
                                    // at byte 0 so it isn't appended after the chunks we already have.
                                    val whole = resp.code == 200
                                    if (whole) { pos = 0; sink.setLength(0) }
                                    sink.seek(pos)
                                    var n = 0L
                                    resp.body.byteStream().use { src ->
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val r = src.read(buf)
                                            if (r < 0) break
                                            sink.write(buf, 0, r)
                                            n += r
                                            if (total > 0) onProgress(((pos + n).toFloat() / total).coerceIn(0f, 1f))
                                        }
                                    }
                                    if (whole) { pos = n; -1L } else n
                                }
                            }
                        }
                    } finally {
                        cancelHook?.dispose()
                    }
                    if (readThisChunk < 0) break
                    pos += readThisChunk
                    if (total > 0) {
                        // Known size: a short response just means "continue from here", until we have it all.
                        if (pos >= total) break
                        if (readThisChunk == 0L) throw IOException("The download stopped early. Try again.")
                    } else {
                        if (readThisChunk < CHUNK) break // unknown size: a short chunk is the end
                        onProgress(-1f)
                    }
                }
            }
            if (tmp.length() == 0L) throw IOException("Downloaded file is empty.")
            if (total > 0 && tmp.length() < total) throw IOException("The download stopped early. Try again.")
            if (!tmp.renameTo(out)) throw IOException("Couldn't save the download.")
        } catch (e: Throwable) {
            tmp.delete() // never leave (or later cache) a partial file, including on cancel
            throw e
        }
        runCatching { writeMeta(out, resolved) }
        onProgress(1f)
        DownloadedAudio(out, resolved)
    }

    // ---------- Downloads list ----------

    /** Every downloaded song we have details for, newest first. */
    suspend fun listDownloads(): List<DownloadedSong> = withContext(Dispatchers.IO) {
        audioDir.listFiles { f -> f.extension == "m4a" || f.extension == "webm" }.orEmpty()
            .filter { it.length() > 0 }
            .mapNotNull { file ->
                val song = readMeta(file) ?: return@mapNotNull null
                DownloadedSong(song, file, file.length(), File(file.path + ".json").lastModified())
            }
            .sortedByDescending { it.savedAtMs }
    }

    /** Startup housekeeping: partial downloads left by a crash or a cancel. */
    suspend fun cleanupPartials() = withContext(Dispatchers.IO) {
        audioDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /** Removes the audio and its saved details and waveform. */
    suspend fun deleteDownload(d: DownloadedSong) = withContext(Dispatchers.IO) {
        listOf(d.file, File(d.file.path + ".json"), File(d.file.path + ".wave")).forEach { it.delete() }
    }

    private fun writeMeta(audio: File, song: Song) {
        val json = JSONObject()
            .put("url", song.url)
            .put("title", song.title)
            .put("artist", song.artist)
            .put("durationSec", song.durationSec)
            .put("thumbnailUrl", song.thumbnailUrl ?: JSONObject.NULL)
        File(audio.path + ".json").writeText(json.toString())
    }

    private fun readMeta(audio: File): Song? = runCatching {
        val json = JSONObject(File(audio.path + ".json").readText())
        Song(
            url = json.getString("url"),
            title = json.getString("title"),
            artist = json.optString("artist"),
            durationSec = json.optLong("durationSec"),
            source = SongSource.YouTube,
            isOfficial = true,
            thumbnailUrl = json.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
        )
    }.getOrNull()

    private fun userAgentFor(url: String): String =
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            NewPipeDownloader.BROWSER_USER_AGENT
        }

    private fun StreamInfoItem.toSong(fromMusic: Boolean): Song {
        val uploader = uploaderName.orEmpty()
        return Song(
            url = url,
            title = name,
            artist = uploader.removeSuffix(" - Topic"),
            durationSec = duration,
            source = SongSource.YouTube,
            viewCount = viewCount,
            uploadedEpochSec = runCatching { uploadDate?.instant?.epochSecond }.getOrNull(),
            isLive = streamType.isLive(),
            isOfficial = fromMusic || isUploaderVerified || uploader.endsWith(" - Topic"),
            thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
        )
    }

    private fun StreamType?.isLive() =
        this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

    companion object {
        private const val CHUNK = 2L * 1024 * 1024

        /** kbps for a stream; NewPipe reports averageBitrate in kbps and bitrate in bps. */
        fun kbps(s: AudioStream): Int = when {
            s.averageBitrate > 0 -> s.averageBitrate
            s.bitrate > 0 -> s.bitrate / 1000
            else -> 0
        }

        /**
         * Progressive, original-language audio only. Prefer streams meeting the minimum quality,
         * then the preferred format, then the highest bitrate. Falls back to the best available.
         */
        fun pickAudioStream(streams: List<AudioStream>, f: SearchFilters): AudioStream? {
            val usable = streams.filter {
                it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    (it.format == MediaFormat.M4A || it.format == MediaFormat.WEBMA || it.format == MediaFormat.WEBMA_OPUS)
            }
            if (usable.isEmpty()) return null
            val original = usable.filter {
                it.audioTrackType?.name.let { t -> t == null || t == "ORIGINAL" }
            }.ifEmpty { usable }

            fun matchesFormat(s: AudioStream) = when (f.format) {
                FormatPref.M4a -> s.format == MediaFormat.M4A
                FormatPref.Opus -> s.format == MediaFormat.WEBMA_OPUS || s.format == MediaFormat.WEBMA
            }
            // 5 kbps slack: YouTube's "128 kbps" AAC often reports ~127.
            val goodEnough = original.filter { kbps(it) >= f.minQuality.kbps - 5 }.ifEmpty { original }
            return goodEnough.sortedWith(
                compareByDescending<AudioStream> { matchesFormat(it) }.thenByDescending { kbps(it) },
            ).first()
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
