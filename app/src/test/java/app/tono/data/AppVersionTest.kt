package app.tono.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test fun newerPatchMinorMajor() {
        assertTrue(AppVersion.isNewer("0.2.2", "0.2.1"))
        assertTrue(AppVersion.isNewer("0.3.0", "0.2.9"))
        assertTrue(AppVersion.isNewer("1.0.0", "0.99.99"))
    }

    @Test fun numbersNotText() = assertTrue(AppVersion.isNewer("0.10.0", "0.9.0"))

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(AppVersion.isNewer("0.2.1", "0.2.1"))
        assertFalse(AppVersion.isNewer("0.2.0", "0.2.1"))
    }

    @Test fun tagPrefixAndMissingParts() {
        assertEquals(0, AppVersion.compare("v1.2", "1.2.0"))
        assertTrue(AppVersion.isNewer("v1.3", "1.2.9"))
        assertEquals(0, AppVersion.compare("1.2.0-beta", "1.2.0"))
    }
}
