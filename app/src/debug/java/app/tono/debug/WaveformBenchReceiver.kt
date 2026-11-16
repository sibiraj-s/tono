package app.tono.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.tono.audio.Waveform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only benchmark:
 *   adb shell am broadcast -n app.tono/.debug.WaveformBenchReceiver --es file <name in cache/audio>
 * Logs under tag TonoBench. Bypasses the waveform cache.
 */
class WaveformBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("file") ?: return
        val file = File(File(context.cacheDir, "audio"), name)
        intent.getIntExtra("parts", 0).takeIf { it > 0 }?.let { Waveform.parallelism = it }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val wf = Waveform.analyze(file, useCache = false)
                Log.i("TonoBench", "parts=${Waveform.parallelism} $name:${wf.durationMs / 1000}s audio, ${wf.size} buckets in ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (e: Exception) {
                Log.e("TonoBench", "failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
