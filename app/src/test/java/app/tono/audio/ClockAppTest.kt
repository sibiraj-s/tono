package app.tono.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockAppTest {
    @Test fun samsungAfterSettingDefault() {
        val hint = ClockApp.alarmHint("Wake", isDefault = true, samsungClock = true)!!
        assertTrue(hint.startsWith("“Wake” is your default alarm sound, but Samsung Clock"))
        assertTrue(hint.endsWith("tap Sound and pick “Wake”."))
    }

    @Test fun samsungWithoutDefault() = assertEquals(
        "Samsung Clock gives each alarm its own sound. In Clock, open an alarm, tap Sound and pick “Wake”.",
        ClockApp.alarmHint("Wake", isDefault = false, samsungClock = true),
    )

    @Test fun otherClockAfterSettingDefault() =
        assertTrue(ClockApp.alarmHint("Wake", isDefault = true, samsungClock = false)!!.startsWith("“Wake” is your default alarm sound. If an alarm"))

    @Test fun otherClockWithoutDefaultUsesUsualMessage() = assertNull(ClockApp.alarmHint("Wake", isDefault = false, samsungClock = false))
}
