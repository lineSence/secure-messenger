package dev.sm.messenger.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ссылки-приглашения на телефоне: запись и чтение, пределы, сроки, порча строки (ADR-83). */
class InviteLinksTest {
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

    private fun id(b: Int) = ByteArray(32) { b.toByte() }

    private fun out(b: Int, expires: Long = 1000) = OutLink(ByteArray(16) { b.toByte() }, 10, expires, id(b), id(b + 1))

    private fun inl(name: String, expires: Long = 1000) =
        InLink(name, ByteArray(33) { 5 }, id(7), "SMN1:AB", expires, OwnQueue(3, id(8), id(9)))

    @Test
    fun roundTrip() {
        val kv = MemKv()
        val links = InviteLinks(kv)
        val started = OutLink(
            ByteArray(16) { 1 }, 10, 1000, id(2), id(3), OutStage.Started, "05ab", ByteArray(33) { 4 }, id(5), "SMN1:CD",
        )
        assertTrue(links.putOut(out(9)))
        assertTrue(links.putOut(started))
        val got = links.outs().single { it.stage == OutStage.Started }
        assertEquals("05ab", got.contact)
        assertEquals("SMN1:CD", got.peerNode)
        assertArrayEquals(id(5), got.peerSender)
        assertArrayEquals(id(2), got.recipientId)
        val a = inl("05cd").let {
            InLink(it.inviter, it.identityKey, it.inviterSender, it.inviterNode, it.expires, it.queue, InStage.Ask, "Оля", ByteArray(32) { 6 })
        }
        assertTrue(links.putIn(a))
        val i = links.ins().single()
        assertEquals(InStage.Ask, i.stage)
        assertEquals("Оля", i.name)
        assertEquals(3L, i.queue.period)
        assertArrayEquals(id(9), i.queue.senderId)
    }

    @Test
    fun replacesAndLimits() {
        val links = InviteLinks(MemKv())
        repeat(InviteLinks.MAX_OUT) { assertTrue(links.putOut(out(it))) }
        assertFalse(links.putOut(out(100)))
        assertTrue(links.putOut(out(0, expires = 5)))
        assertEquals(InviteLinks.MAX_OUT, links.outs().size)
        assertEquals(5L, links.outs().single { it.secret[0] == 0.toByte() }.expires)
        links.removeOut(ByteArray(16) { 0 })
        assertEquals(InviteLinks.MAX_OUT - 1, links.outs().size)
        repeat(InviteLinks.MAX_IN) { assertTrue(links.putIn(inl("05$it"))) }
        assertFalse(links.putIn(inl("05x")))
        links.removeIn("050")
        assertEquals(InviteLinks.MAX_IN - 1, links.ins().size)
    }

    @Test
    fun prunesByStage() {
        val links = InviteLinks(MemKv())
        links.putOut(out(1, expires = 100))
        links.putOut(OutLink(ByteArray(16) { 2 }, 0, 100, id(2), id(3), OutStage.Started, "05a", ByteArray(0), id(4)))
        links.putOut(OutLink(ByteArray(16) { 3 }, 0, 100, id(2), id(3), OutStage.Done, "05b", ByteArray(0), id(4)))
        links.putIn(inl("05c", expires = 100))
        assertEquals(0, links.prune(99).first.size)
        assertEquals(listOf(OutStage.Active), links.prune(100).first.map { it.stage })
        val (o, i) = links.prune(100 + InviteLinks.GRACE_SECS)
        assertEquals(listOf(OutStage.Started), o.map { it.stage })
        assertEquals(listOf("05c"), i.map { it.inviter })
        assertEquals(listOf(OutStage.Done), links.outs().map { it.stage })
        assertEquals(1, links.prune(100 + InviteLinks.DONE_KEEP_SECS).first.size)
        assertTrue(links.outs().isEmpty())
    }

    @Test
    fun corruptRowIsEmpty() {
        val kv = MemKv()
        val links = InviteLinks(kv)
        links.putOut(out(1))
        val k = kv.rows.keys.single()
        kv.rows[k] = kv.rows.getValue(k).copyOf(20)
        assertTrue(links.outs().isEmpty())
        kv.rows[k] = byteArrayOf(2, 0)
        assertTrue(links.outs().isEmpty())
        kv.rows[k] = byteArrayOf(1, 1) + ByteArray(200)
        assertTrue(links.outs().isEmpty())
    }
}
