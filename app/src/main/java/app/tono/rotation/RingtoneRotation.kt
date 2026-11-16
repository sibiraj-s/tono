package app.tono.rotation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tono.audio.SavedTone
import app.tono.container
import app.tono.data.RotationInterval
import app.tono.data.RotationOrder
import app.tono.data.ToneUse
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Ringtone rotation: a WorkManager job (survives reboots) that switches the ringtone to the next
 * tone in the user's set, early in the morning so it never changes mid-call.
 */
object RingtoneRotation {
    private const val WORK = "ringtone-rotation"
    private const val CHANGE_HOUR = 4

    fun schedule(context: Context, interval: RotationInterval) {
        val now = ZonedDateTime.now()
        var next = now.withHour(CHANGE_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<RotationWorker>(interval.days, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Switches to the next tone now. Returns it, or null when there's nothing to switch to or no permission. */
    suspend fun rotate(context: Context): SavedTone? {
        val c = context.container
        val cfg = c.prefs.rotation.first()
        if (!c.toneStore.canChangeSystemSounds()) return null
        val tones = c.toneStore.list().filter { it.id in cfg.toneIds }.sortedBy { it.title.lowercase() }
        val current = c.toneStore.currentDefaultIds()[ToneUse.Ringtone] ?: cfg.lastToneId
        val nextId = RotationOrder.next(tones.map { it.id }, current, cfg.shuffle) ?: return null
        val tone = tones.first { it.id == nextId }
        c.toneStore.setAsDefault(tone.uri, ToneUse.Ringtone)
        c.prefs.updateRotation { it.copy(lastToneId = tone.id, lastChangedMs = System.currentTimeMillis()) }
        return tone
    }
}

class RotationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cfg = applicationContext.container.prefs.rotation.first()
        if (cfg.enabled) runCatching { RingtoneRotation.rotate(applicationContext) }
        // Never retry: a skipped day just means the next scheduled change.
        return Result.success()
    }
}
