package dev.sm.messenger.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Свои очереди на контакт: две последние по периоду, порча строки, период 30 дней (ADR-137). */
class OwnQueuesTest {
    private class MemKv : RelayKv {
        val rows = HashMap<String, ByteArray>()

        private fun key(table: String, id: ByteArray) = table + "/" + String(id, Charsets.UTF_8)

        override fun get(table: String, id: ByteArray) = rows[key(table, id)]

        override fun put(table: String, id: ByteArray, value: ByteArray) {
            rows[key(table, id)] = value
        }

        override fun delete(table: String, id: ByteArray) {
            rows.remove(key(table, id))
        }
    }

    private fun q(period: Long, b: Int) = OwnQueue(period, ByteArray(32) { b.toByte() }, ByteArray(32) { (b + 1).toByte() })

    @Test
    fun keepsTwoNewest() {
        val s = OwnQueues(MemKv())
        assertTrue(s.get("a").isEmpty())
        assertTrue(s.put("a", q(5, 1)))
        assertTrue(s.put("a", q(6, 2)))
        assertTrue(s.put("a", q(7, 3)))
        assertEquals(listOf(7L, 6L), s.get("a").map { it.period })
        assertFalse(s.put("a", q(5, 4)))
        assertTrue(s.put("a", q(7, 9)))
        val got = s.get("a")
        assertEquals(listOf(7L, 6L), got.map { it.period })
        assertArrayEquals(ByteArray(32) { 9 }, got[0].recipientId)
        assertArrayEquals(ByteArray(32) { 10 }, got[0].senderId)
        assertTrue(s.get("b").isEmpty())
        s.delete("a")
        assertTrue(s.get("a").isEmpty())
    }

    @Test
    fun badRowIsEmpty() {
        val kv = MemKv()
        val s = OwnQueues(kv)
        s.put("a", q(1, 1))
        val key = "relay_own/a"
        val row = kv.rows.getValue(key)
        kv.rows[key] = row.copyOf(row.size - 1)
        assertTrue(s.get("a").isEmpty())
        kv.rows[key] = row.copyOf().also { it[0] = 2 }
        assertTrue(s.get("a").isEmpty())
        kv.rows[key] = row.copyOf().also { it[1] = 0x80.toByte() }
        assertTrue(s.get("a").isEmpty())
    }

    @Test
    fun periodIsThirtyDays() {
        assertEquals(2_592_000L, OwnQueues.PERIOD_SECS)
        assertEquals(0L, OwnQueues.period(2_591_999))
        assertEquals(1L, OwnQueues.period(2_592_000))
    }
}
