package app.tono.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure helpers on loudness envelopes; no Android types so they're unit-testable. */
object AudioMath {

    /**
     * Start (ms) of the loudest window of [windowMs] — a cheap stand-in for "the chorus".
     * Skips the first 8% of the song so long, loud intros don't win.
     */
    fun loudestWindowStartMs(rms: FloatArray, bucketMs: Int, windowMs: Long, durationMs: Long): Long {
        val n = rms.size
        val win = (windowMs / bucketMs).toInt().coerceAtLeast(1)
        if (n <= win) return 0
        val minStart = (n * 0.08).toInt().coerceAtMost(n - win)
        var sum = 0.0
        for (i in minStart until minStart + win) sum += rms[i] * rms[i]
        var best = sum
        var bestStart = minStart
        for (start in minStart + 1..n - win) {
            val outV = rms[start - 1]
            val inV = rms[start + win - 1]
            sum += inV * inV - outV * outV
            if (sum > best) { best = sum; bestStart = start }
        }
        // Back up 1 s so the tone starts just before the loud part rather than mid-hit.
        val startMs = bestStart.toLong() * bucketMs - 1000
        return startMs.coerceIn(0, max(0, durationMs - windowMs))
    }

    /**
     * Gain that brings the selection to a common loudness without clipping.
     * Target ≈ -14 dBFS RMS, never more than +12 dB, never pushing peaks above -0.3 dBFS.
     */
    fun levelingGain(peak: FloatArray, rms: FloatArray, from: Int, toExclusive: Int): Float {
        if (toExclusive <= from) return 1f
        var p = 0f
        var sumSq = 0.0
        for (i in from until toExclusive) {
            p = max(p, peak[i])
            sumSq += rms[i] * rms[i]
        }
        val r = sqrt(sumSq / (toExclusive - from)).toFloat()
        if (r <= 1e-4f || p <= 1e-4f) return 1f
        val target = 0.2f
        val byLoudness = target / r
        val byPeak = 0.966f / p
        return min(min(byLoudness, byPeak), 4f).coerceAtLeast(0.25f)
    }

    /** Linear fade multiplier for the frame at [frame] of [totalFrames]. */
    fun fadeMultiplier(frame: Long, totalFrames: Long, fadeInFrames: Long, fadeOutFrames: Long): Float {
        var m = 1f
        if (fadeInFrames > 0 && frame < fadeInFrames) m = min(m, frame.toFloat() / fadeInFrames)
        val fromEnd = totalFrames - frame
        if (fadeOutFrames > 0 && fromEnd < fadeOutFrames) m = min(m, (fromEnd.coerceAtLeast(0)).toFloat() / fadeOutFrames)
        return m
    }
}
