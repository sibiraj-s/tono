package app.tono.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainNotesTest {
    @Test fun changesetsEntryBecomesPlainLines() {
        val md = """
            ### Minor Changes

            - 17e9827: Loop preview on the trim screen.

            ### Patch Changes

            - [a1b2c3d](https://github.com/o/r/commit/a1b2c3d): Contact ringtone wasn't cleared.
        """.trimIndent()
        assertEquals(
            "Minor Changes\n\n• Loop preview on the trim screen.\n\nPatch Changes\n\n• Contact ringtone wasn't cleared.",
            plainNotes(md),
        )
    }
}
