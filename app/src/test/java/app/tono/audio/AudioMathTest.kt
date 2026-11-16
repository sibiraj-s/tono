package app.tono.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {

    @Test
    fun `loudest window finds the loud section`() {
        // 100 s of quiet at 50 ms buckets, loud from 60 s to 90 s.
        val bucket = 50
        val rms = FloatArray(2000) { i -> if (i in 1200 until 1800) 0.5f else 0.05f }
        val start = AudioMath.loudestWindowStartMs(rms, bucket, windowMs = 30_000, durationMs = 100_000)
        // Starts 1 s before the loud part.
        assertEquals(59_000L, start)
    }

    @Test
    fun `loudest window ignores a loud intro`() {
        val rms = FloatArray(2000) { i -> if (i < 100) 0.9f else if (i in 1000 until 1600) 0.4f else 0.05f }
        val start = AudioMath.loudestWindowStartMs(rms, 50, 30_000, 100_000)
        assertEquals(49_000L, start)
    }

    @Test
    fun `short songs start at zero`() {
        assertEquals(0L, AudioMath.loudestWindowStartMs(FloatArray(100) { 0.3f }, 50, 30_000, 5_000))
    }

    @Test
    fun `leveling boosts quiet audio but never clips`() {
        val quietPeak = FloatArray(100) { 0.2f }
        val quietRms = FloatArray(100) { 0.05f }
        val g = AudioMath.levelingGain(quietPeak, quietRms, 0, 100)
        assertTrue("gain $g should boost", g > 1f)
        assertTrue("peak after gain must stay under full scale", 0.2f * g <= 0.97f)

        val loudPeak = FloatArray(100) { 0.99f }
        val loudRms = FloatArray(100) { 0.5f }
        assertTrue(AudioMath.levelingGain(loudPeak, loudRms, 0, 100) < 1f)
    }

    @Test
    fun `leveling on silence is neutral`() {
        assertEquals(1f, AudioMath.levelingGain(FloatArray(10), FloatArray(10), 0, 10))
    }

    @Test
    fun `fades ramp linearly`() {
        val total = 1000L
        assertEquals(0f, AudioMath.fadeMultiplier(0, total, 100, 200), 1e-6f)
        assertEquals(0.5f, AudioMath.fadeMultiplier(50, total, 100, 200), 1e-6f)
        assertEquals(1f, AudioMath.fadeMultiplier(500, total, 100, 200), 1e-6f)
        assertEquals(0.5f, AudioMath.fadeMultiplier(900, total, 100, 200), 1e-6f)
        assertEquals(1f, AudioMath.fadeMultiplier(999, total, 0, 0), 1e-6f)
    }
}
