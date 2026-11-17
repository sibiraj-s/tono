package app.tono.audio

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * Samsung Clock gives each alarm its own sound and never plays the default alarm sound, so
 * setting the default changes nothing there, not even for new alarms. Other apps can't read or
 * change its alarms either: the user has to pick the tone under an alarm's Sound in Clock.
 */
class ClockApp(private val context: Context) {

    fun isSamsungClock(): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(SAMSUNG_CLOCK) != null
    }.getOrDefault(false)

    /**
     * Opens Samsung Clock on its alarm list, or on its last tab if that's refused. [activity] must
     * be an Activity context. Returns false when Clock couldn't be opened.
     */
    fun openSamsungAlarms(activity: Context): Boolean {
        val alarmList = Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(SAMSUNG_CLOCK)
        val app = runCatching { context.packageManager.getLaunchIntentForPackage(SAMSUNG_CLOCK) }.getOrNull()
        return listOfNotNull(alarmList, app).any { runCatching { activity.startActivity(it) }.isSuccess }
    }

    companion object {
        const val SAMSUNG_CLOCK = "com.sec.android.app.clockpackage"

        /**
         * What to tell the user after saving [title] as an alarm tone, or null when the usual
         * message fits (not Samsung, and the default wasn't changed).
         */
        fun alarmHint(title: String, isDefault: Boolean, samsungClock: Boolean): String? = when {
            samsungClock && isDefault ->
                "“$title” is your default alarm sound, but Samsung Clock gives each alarm its own sound. " +
                    "In Clock, open an alarm, tap Sound and pick “$title”."
            samsungClock -> "Samsung Clock gives each alarm its own sound. In Clock, open an alarm, tap Sound and pick “$title”."
            isDefault ->
                "“$title” is your default alarm sound. If an alarm plays something else, " +
                    "open it in your Clock app and pick “$title” as its sound."
            else -> null
        }
    }
}
