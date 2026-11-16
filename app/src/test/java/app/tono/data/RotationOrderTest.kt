package app.tono.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class RotationOrderTest {
    private val ids = listOf(1L, 2L, 3L)

    @Test fun inOrderAdvancesAndWraps() {
        assertEquals(2L, RotationOrder.next(ids, 1L, shuffle = false))
        assertEquals(1L, RotationOrder.next(ids, 3L, shuffle = false))
        assertEquals(1L, RotationOrder.next(ids, 99L, shuffle = false)) // current isn't in the set
        assertEquals(1L, RotationOrder.next(ids, null, shuffle = false))
    }

    @Test fun shuffleNeverRepeatsCurrent() {
        val r = Random(7)
        repeat(200) { assertNotEquals(2L, RotationOrder.next(ids, 2L, shuffle = true, random = r)) }
    }

    @Test fun edgeCases() {
        assertNull(RotationOrder.next(emptyList(), 1L, shuffle = true))
        assertEquals(5L, RotationOrder.next(listOf(5L), 5L, shuffle = true))
    }
}
