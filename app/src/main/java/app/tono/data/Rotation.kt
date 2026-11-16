package app.tono.data

import kotlin.random.Random

enum class RotationInterval(val label: String, val days: Long) {
    Daily("Every day", 1),
    Weekly("Every week", 7),
}

/** Switches the phone's ringtone between a set of saved tones on a schedule. */
data class RotationConfig(
    val enabled: Boolean = false,
    val toneIds: Set<Long> = emptySet(),
    val interval: RotationInterval = RotationInterval.Daily,
    /** Random order (never the same tone twice in a row) vs. A–Z in turn. */
    val shuffle: Boolean = true,
    val lastToneId: Long? = null,
    val lastChangedMs: Long = 0,
)

object RotationOrder {
    /**
     * The tone to switch to next. [ids] is the rotation in A–Z order; [current] is the ringtone now
     * (or the last one rotation set). Shuffle never repeats [current] when there's a choice.
     */
    fun next(ids: List<Long>, current: Long?, shuffle: Boolean, random: Random = Random): Long? {
        if (ids.isEmpty()) return null
        if (ids.size == 1) return ids[0]
        if (shuffle) return ids.filter { it != current }.random(random)
        val i = ids.indexOf(current)
        return ids[(i + 1) % ids.size]
    }
}
