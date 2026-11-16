package app.tono.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 audio effect applied during export: linear fade in/out plus a constant gain
 * (volume leveling). Works on 16-bit or float PCM, which is what Transformer feeds effects.
 */
@UnstableApi
class FadeGainProcessor(
    private val clipDurationMs: Long,
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val gain: Float,
) : BaseAudioProcessor() {

    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var totalFrames = 0L
    private var fadeInFrames = 0L
    private var fadeOutFrames = 0L
    private var frame = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        val rate = inputAudioFormat.sampleRate.toLong()
        totalFrames = clipDurationMs * rate / 1000
        fadeInFrames = fadeInMs * rate / 1000
        fadeOutFrames = fadeOutMs * rate / 1000
        frame = 0
        return inputAudioFormat // same format out; we only scale samples
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val inLe = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            while (inLe.remaining() >= 4 * channels) {
                val m = AudioMath.fadeMultiplier(frame, totalFrames, fadeInFrames, fadeOutFrames) * gain
                repeat(channels) { out.putFloat((inLe.getFloat() * m).coerceIn(-1f, 1f)) }
                frame++
            }
        } else {
            while (inLe.remaining() >= 2 * channels) {
                val m = AudioMath.fadeMultiplier(frame, totalFrames, fadeInFrames, fadeOutFrames) * gain
                repeat(channels) {
                    val v = (inLe.getShort() * m).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(v.toShort())
                }
                frame++
            }
        }
        // Drop any trailing partial frame (shouldn't happen with well-formed PCM).
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    // A flush restarts the stream (Transformer never seeks inside our clip), so the fade
    // position starts over too. Every flush in Media3 1.11 arrives here (the no-argument
    // onFlush() is deprecated and only called from the default of this overload).
    override fun onFlush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) {
        frame = 0
    }

    override fun onReset() {
        frame = 0
    }
}
