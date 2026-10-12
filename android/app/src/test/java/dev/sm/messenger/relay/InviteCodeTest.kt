package dev.sm.messenger.relay

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Код к ссылке: список слов, выбор, разбор ввода (ADR-142). Argon2id (JNI) здесь не запускается. */
class InviteCodeTest {
    @Test
    fun wordListIsSound() {
        val words = InviteWords.ALL
        assertEquals(2048, words.size)
        assertEquals(2048, words.toSet().size)
        assertTrue(words.all { w -> w.length in 3..12 && w.all { it in 'а'..'я' } })
    }

    @Test
    fun generateGivesFourDistinctWords() {
        val random = SecureRandom()
        repeat(200) {
            val code = InviteCode.generate(random)
            assertEquals(InviteCode.WORD_COUNT, code.size)
            assertEquals(code.size, code.toSet().size)
            assertTrue(code.all { it in InviteWords.ALL })
        }
    }

    @Test
    fun parseForgivesFormatting() {
        val w = InviteWords.ALL
        val code = listOf(w[0], w[1], w[2], w[3])
        assertEquals(code, InviteCode.parse(code.joinToString(" ")))
        assertEquals(code, InviteCode.parse(code.joinToString(" · ")))
        assertEquals(code, InviteCode.parse("  " + code.joinToString(", ").uppercase() + ". "))
        assertEquals(code, InviteCode.parse(code.joinToString("-")))
        assertEquals(code, InviteCode.parse(code.joinToString("\n")))
        assertEquals(code, InviteCode.parse(InviteCode.display(code)))
    }

    @Test
    fun parseRejectsWrongInput() {
        val w = InviteWords.ALL
        assertNull(InviteCode.parse(""))
        assertNull(InviteCode.parse("${w[0]} ${w[1]} ${w[2]}"))
        assertNull(InviteCode.parse("${w[0]} ${w[1]} ${w[2]} ${w[3]} ${w[4]}"))
        assertNull(InviteCode.parse("${w[0]} ${w[1]} ${w[2]} аааабв"))
        assertNotNull(InviteCode.parse("${w[0]} ${w[1]} ${w[2]} ${w[3]}"))
    }

    @Test
    fun passwordIsWordsWithSpaces() {
        val code = listOf(InviteWords.ALL[5], InviteWords.ALL[6], InviteWords.ALL[7], InviteWords.ALL[8])
        assertArrayEquals(code.joinToString(" ").toByteArray(Charsets.UTF_8), InviteCode.password(code))
    }
}
