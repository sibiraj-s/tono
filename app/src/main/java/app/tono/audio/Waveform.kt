package app.tono.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Loudness envelope of a whole song, one bucket per [bucketMs].
 * [peak] and [rms] are 0..1 (full scale).
 */
class Waveform(
    val bucketMs: Int,
    val peak: FloatArray,
    val rms: FloatArray,
    val durationMs: Long,
) {
    val size: Int get() = peak.size

    fun indexAt(ms: Long): Int = (ms / bucketMs).toInt().coerceIn(0, (size - 1).coerceAtLeast(0))

    companion object {
        const val DEFAULT_BUCKET_MS = 50
        private const val TAG = "TonoWaveform"

        /** How many parts of the song are decoded at once. */
        var parallelism: Int = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors() / 2))

        /**
         * Decodes [file] to PCM and builds the envelope on background threads.
         * [onProgress] gets 0..1 as decoding moves through the song.
         */
        suspend fun analyze(
            file: File,
            bucketMs: Int = DEFAULT_BUCKET_MS,
            useCache: Boolean = true,
            onProgress: (Float) -> Unit = {},
        ): Waveform = withContext(Dispatchers.Default) {
            val cache = File(file.parentFile, file.name + ".wave")
            if (useCache) readCache(cache, file, bucketMs)?.let { onProgress(1f); return@withContext it }
            val started = SystemClock.elapsedRealtime()
            decode(file, bucketMs, onProgress).also {
                Log.i(TAG, "Analyzed ${it.durationMs / 1000}s of audio in ${SystemClock.elapsedRealtime() - started} ms ($parallelism parts)")
                if (useCache) runCatching { writeCache(cache, file, it) }
            }
        }

        // Saved next to the downloaded audio so reopening a song skips decoding.
        private const val CACHE_VERSION = 3 // 3: peak is per-channel max (was the channel mix)

        private fun writeCache(cache: File, audio: File, wf: Waveform) {
            java.io.DataOutputStream(cache.outputStream().buffered()).use { out ->
                out.writeInt(CACHE_VERSION); out.writeLong(audio.length()); out.writeInt(wf.bucketMs)
                out.writeLong(wf.durationMs); out.writeInt(wf.size)
                for (v in wf.peak) out.writeFloat(v)
                for (v in wf.rms) out.writeFloat(v)
            }
        }

        private fun readCache(cache: File, audio: File, bucketMs: Int): Waveform? = runCatching {
            if (!cache.exists()) return null
            java.io.DataInputStream(cache.inputStream().buffered()).use { inp ->
                if (inp.readInt() != CACHE_VERSION || inp.readLong() != audio.length() || inp.readInt() != bucketMs) return null
                val duration = inp.readLong()
                val n = inp.readInt()
                val peak = FloatArray(n) { inp.readFloat() }
                val rms = FloatArray(n) { inp.readFloat() }
                Waveform(bucketMs, peak, rms, duration)
            }
        }.getOrNull()

        private suspend fun decode(file: File, bucketMs: Int, onProgress: (Float) -> Unit): Waveform {
            val (mime, durationUs) = probe(file)
            if (durationUs <= 0) {
                // Unknown length: can't split, decode in one pass.
                val acc = TimeAccumulator(bucketMs, 0)
                decodeRange(file, mime, 0, Long.MAX_VALUE, acc, onProgress)
                onProgress(1f)
                return acc.toWaveform(acc.lastMs())
            }
            val parts = if (durationUs < 20_000_000) 1 else parallelism
            val totalMs = durationUs / 1000
            val progress = FloatArray(parts)
            val accs = coroutineScope {
                (0 until parts).map { i ->
                    async(Dispatchers.Default) {
                        val from = durationUs * i / parts
                        val to = if (i == parts - 1) Long.MAX_VALUE else durationUs * (i + 1) / parts
                        val acc = TimeAccumulator(bucketMs, totalMs)
                        decodeRange(file, mime, from, to, acc) { p ->
                            synchronized(progress) { progress[i] = p; onProgress(progress.sum() / parts) }
                        }
                        acc
                    }
                }.awaitAll()
            }
            onProgress(1f)
            return TimeAccumulator.merge(accs).toWaveform(totalMs)
        }

        private fun probe(file: File): Pair<String, Long> {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        val d = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                        return mime to d
                    }
                }
            } finally {
                ex.release()
            }
            throw IOException("No audio track in file.")
        }

        /** Prefer an in-process software decoder (no IPC per buffer), then any software one. */
        private fun createDecoder(mime: String, format: MediaFormat): MediaCodec {
            val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .sortedBy { info ->
                    when {
                        "inproc" in info.name -> 0
                        info.isSoftwareOnly -> 1
                        else -> 2
                    }
                }
            for (info in candidates) {
                val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: continue
                try {
                    codec.configure(format, null, null, 0)
                    return codec
                } catch (_: Exception) {
                    codec.release()
                }
            }
            return MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0) }
        }

        /** Decodes [fromUs, toUs) into [acc]; frames are placed by timestamp, so parts can run in parallel. */
        private suspend fun decodeRange(
            file: File,
            mime: String,
            fromUs: Long,
            toUs: Long,
            acc: TimeAccumulator,
            onProgress: (Float) -> Unit,
        ) {
            val extractor = MediaExtractor()
            // Released in every case, including when no decoder can be set up for this file.
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                acc.configure(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), false)

                val endUs = if (toUs == Long.MAX_VALUE) format.getLongOrZero(MediaFormat.KEY_DURATION) else toUs
                val span = endUs - fromUs
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastPct = -1
                val codec = createDecoder(mime, format)
                try {
                    codec.start()
                    while (!outputDone) {
                        coroutineContext.ensureActive()
                        var fed = false
                        while (!inputDone) {
                            val inIndex = codec.dequeueInputBuffer(0)
                            if (inIndex < 0) break
                            val t = extractor.sampleTime
                            val size = if (t < 0 || t >= toUs) -1 else extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, t, 0)
                                extractor.advance()
                                fed = true
                                if (span > 0) {
                                    val pct = ((t - fromUs) * 100 / span).toInt().coerceIn(0, 100)
                                    if (pct > lastPct) { lastPct = pct; onProgress(pct / 100f) }
                                }
                            }
                        }
                        // Drain everything that's ready; only wait when there was nothing to feed.
                        var timeout = if (fed) 0L else 5_000L
                        while (true) {
                            val outIndex = codec.dequeueOutputBuffer(info, timeout)
                            timeout = 0
                            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val f = codec.outputFormat
                                acc.configure(
                                    f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                    f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT,
                                )
                                continue
                            }
                            if (outIndex < 0) break
                            if (info.size > 0) {
                                val out = codec.getOutputBuffer(outIndex)!!
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                acc.consume(out.order(ByteOrder.LITTLE_ENDIAN), info.presentationTimeUs, fromUs, toUs)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                                break
                            }
                        }
                    }
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }

        private fun MediaFormat.getLongOrZero(key: String) = if (containsKey(key)) getLong(key) else 0L
    }
}

/**
 * Per-bucket peak and sum of squares, with frames placed by their timestamp so several
 * decoders can fill separate parts of the song. Plain fields and bulk copies keep the
 * per-sample loop fast (it runs ~20M times for a 4-minute song).
 */
private class TimeAccumulator(private val bucketMs: Int, totalMs: Long) {
    var peak = FloatArray((totalMs / bucketMs + 2).toInt().coerceAtLeast(16))
    var sumSq = DoubleArray(peak.size)
    var count = IntArray(peak.size)
    var maxBucket = -1
    private var sampleRate = 48_000
    private var channels = 2
    private var pcmFloat = false
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)

    fun configure(sampleRate: Int, channels: Int, pcmFloat: Boolean) {
        this.sampleRate = sampleRate
        this.channels = channels
        this.pcmFloat = pcmFloat
    }

    fun consume(buf: java.nio.ByteBuffer, ptsUs: Long, fromUs: Long, toUs: Long) {
        val ch = channels
        val n: Int
        if (pcmFloat) {
            val fb = buf.asFloatBuffer()
            n = fb.remaining()
            if (floats.size < n) floats = FloatArray(n)
            fb.get(floats, 0, n)
        } else {
            val sb = buf.asShortBuffer()
            n = sb.remaining()
            if (shorts.size < n) shorts = ShortArray(n)
            sb.get(shorts, 0, n)
        }
        val frames = n / ch
        val usPerFrame = 1_000_000.0 / sampleRate
        val bucketUs = bucketMs * 1000.0
        var f = 0
        while (f < frames) {
            val t = ptsUs + f * usPerFrame
            if (t < fromUs) { f++; continue } // pre-roll before this part
            if (t >= toUs) break // belongs to the next part
            val b = (t / bucketUs).toInt()
            ensure(b)
            // The run of frames inside bucket b, in one tight loop.
            val bucketEndUs = (b + 1) * bucketUs
            var p = peak[b]
            var s = sumSq[b]
            var c = count[b]
            while (f < frames) {
                val tf = ptsUs + f * usPerFrame
                if (tf >= bucketEndUs || tf >= toUs) break
                val base = f * ch
                // Loudness (RMS) from the channel mix; peak from the loudest single channel, because
                // volume leveling applies its gain per channel and must not clip any of them.
                var a = 0f
                val v: Float = if (pcmFloat) {
                    var m = 0f
                    for (k in 0 until ch) {
                        val x = floats[base + k]
                        m += x
                        val ax = if (x < 0) -x else x
                        if (ax > a) a = ax
                    }
                    m / ch
                } else {
                    var m = 0
                    for (k in 0 until ch) {
                        val x = shorts[base + k].toInt()
                        m += x
                        val ax = if (x < 0) -x else x
                        if (ax > a) a = ax.toFloat()
                    }
                    a /= 32768f
                    m / (32768f * ch)
                }
                if (a > p) p = a
                s += v * v
                c++
                f++
            }
            peak[b] = p
            sumSq[b] = s
            count[b] = c
            if (b > maxBucket) maxBucket = b
        }
    }

    fun ensure(b: Int) {
        if (b < peak.size) return
        val size = maxOf(b + 1, peak.size * 2)
        peak = peak.copyOf(size)
        sumSq = sumSq.copyOf(size)
        count = count.copyOf(size)
    }

    fun lastMs(): Long = (maxBucket + 1).toLong() * bucketMs

    fun toWaveform(totalMs: Long): Waveform {
        if (maxBucket < 0) throw IOException("Couldn't read any audio from the file.")
        val n = ((totalMs + bucketMs - 1) / bucketMs).toInt().coerceIn(1, peak.size)
        val rms = FloatArray(n) { i -> if (count[i] > 0) sqrt(sumSq[i] / count[i]).toFloat() else 0f }
        return Waveform(bucketMs, peak.copyOf(n), rms, totalMs)
    }

    companion object {
        fun merge(parts: List<TimeAccumulator>): TimeAccumulator {
            val first = parts.first()
            for (other in parts.drop(1)) {
                if (other.peak.size > first.peak.size) first.ensure(other.peak.size - 1)
                for (i in other.peak.indices) {
                    if (other.count[i] == 0) continue
                    first.peak[i] = max(first.peak[i], other.peak[i])
                    first.sumSq[i] += other.sumSq[i]
                    first.count[i] += other.count[i]
                }
                first.maxBucket = max(first.maxBucket, other.maxBucket)
            }
            return first
        }
    }
}
