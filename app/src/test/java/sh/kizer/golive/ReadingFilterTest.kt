package sh.kizer.golive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingFilterTest {
    private val go = "10:BD:A3:E5:10:1A"

    @Test
    fun storesEachPacketIdOnce() {
        val f = ReadingFilter()
        assertTrue(f.accept(go, 7, 0))
        assertFalse(f.accept(go, 7, 155))
        assertFalse(f.accept(go, 7, 12_000))
        assertTrue(f.accept(go, 8, 61_000))
    }

    @Test
    fun samePacketIdLongAfterIsNew() {
        val f = ReadingFilter(repeatGapMs = 1000)
        assertTrue(f.accept(go, 3, 0))
        assertFalse(f.accept(go, 3, 1000))
        assertTrue(f.accept(go, 3, 2001))
    }

    @Test
    fun rememberedReadingIsNotStoredAgain() {
        val f = ReadingFilter()
        f.remember(go, 42, 10_000)
        assertFalse(f.accept(go, 42, 20_000))
        assertTrue(f.accept("AA:BB:CC:DD:EE:FF", 42, 20_000))
    }
}
