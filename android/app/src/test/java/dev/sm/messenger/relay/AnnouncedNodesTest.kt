package dev.sm.messenger.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Какую запись узла получил контакт (п.11), очистка почты при удалении контакта, части очередей (ADR-114). */
class AnnouncedNodesTest {
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

    @Test
    fun newNodeRecordIsNotCurrentUntilMarked() {
        val a = AnnouncedNodes(MemKv())
        assertFalse(a.isCurrent("c1", "SMN1:one"))
        a.mark("c1", "SMN1:one")
        assertTrue(a.isCurrent("c1", "SMN1:one"))
        assertFalse(a.isCurrent("c1", "SMN1:two"))
        assertFalse(a.isCurrent("c2", "SMN1:one"))
        a.mark("c1", "SMN1:two")
        assertTrue(a.isCurrent("c1", "SMN1:two"))
        assertFalse(a.isCurrent("c1", "SMN1:one"))
        a.delete("c1")
        assertFalse(a.isCurrent("c1", "SMN1:two"))
    }

    @Test
    fun forgetRemovesAllRowsOfContactOnly() {
        val kv = MemKv()
        val own = OwnQueues(kv)
        val told = AnnouncedNodes(kv)
        val id = ByteArray(32) { 1 }
        own.put("c1", OwnQueue(5, id, id))
        own.put("c2", OwnQueue(5, id, id))
        told.mark("c1", "n")
        told.mark("c2", "n")
        kv.put(MailboxTables.PEER, "c1".toByteArray(), byteArrayOf(1))
        kv.put(MailboxTables.PEER, "c2".toByteArray(), byteArrayOf(1))
        MailboxTables.forget("c1", kv::delete)
        assertTrue(own.get("c1").isEmpty())
        assertFalse(told.isCurrent("c1", "n"))
        assertEquals(null, kv.get(MailboxTables.PEER, "c1".toByteArray()))
        assertEquals(1, own.get("c2").size)
        assertTrue(told.isCurrent("c2", "n"))
        assertEquals(1, kv.get(MailboxTables.PEER, "c2".toByteArray())?.size)
    }

    @Test
    fun manyContactsAreReadInParts() {
        val items = (1..100).toList()
        assertEquals(1, QueueRounds.parts(0, 60))
        assertEquals(1, QueueRounds.parts(60, 60))
        assertEquals(2, QueueRounds.parts(61, 60))
        assertEquals(2, QueueRounds.parts(100, 60))
        assertTrue(QueueRounds.part(emptyList<Int>(), 60, 0).isEmpty())
        val first = QueueRounds.part(items, 60, 0)
        val second = QueueRounds.part(items, 60, 1)
        assertEquals(60, first.size)
        assertEquals(40, second.size)
        assertEquals(items, first + second)
        // Круг замыкается, а лишний номер части — по модулю.
        assertEquals(first, QueueRounds.part(items, 60, 2))
        assertEquals(items, QueueRounds.part(items, 100, 5))
    }
}
