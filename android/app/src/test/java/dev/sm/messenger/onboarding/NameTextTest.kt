package dev.sm.messenger.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ONB-07: имя режется по code points, суррогатная пара не разрывается. */
class NameTextTest {
    @Test
    fun takeCodePointsKeepsSurrogatePair() {
        assertEquals("😀", takeCodePoints("😀😀", 1))
        assertEquals("a😀", takeCodePoints("a😀b", 2))
    }

    @Test
    fun takeCodePointsHandlesPlainAndEmpty() {
        assertEquals("abc", takeCodePoints("abc", 32))
        assertEquals("", takeCodePoints("", 32))
        assertEquals("", takeCodePoints("abc", 0))
    }

    @Test
    fun firstCodePointReturnsWholeEmoji() {
        assertEquals("😀", firstCodePoint("😀 Оля"))
        assertEquals("О", firstCodePoint("  Оля"))
        assertNull(firstCodePoint("   "))
    }
}
