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

    @Test(expected = dev.sm.messenger.store.SecureDb.CorruptRow::class)
    fun corruptIncomingCannotBeMistakenForTerminalOwnership() {
        val kv = MemKv()
        val links = InviteLinks(kv)
        assertTrue(links.putIn(inl("owner")))
        links.prepareOwnCard("owner", byteArrayOf(1))
        kv.rows["relay_invite/in"] = byteArrayOf(2, 1)
        links.ins()
    }

    @Test
    fun retryBackoffIsBoundedMonotonicAndPreventsDuplicateActiveWork() {
        val backoff = InviteRetryBackoff()
        assertTrue(backoff.acquire("offline", 0))
        assertFalse(backoff.acquire("offline", 999_999))
        assertTrue(backoff.acquire("healthy", 0))
        backoff.finish("offline", 100)
        assertFalse(backoff.acquire("offline", 100 + InviteRetryBackoff.MIN_DELAY - 1))
        assertTrue(backoff.acquire("offline", 100 + InviteRetryBackoff.MIN_DELAY))
        backoff.finish("offline", 50_000)
        assertFalse(backoff.acquire("offline", 50_000))
        backoff.retain(setOf("healthy"))
        assertTrue(backoff.acquire("offline", 0))
        repeat(InviteLinks.MAX_IN + InviteLinks.MAX_OUT - 2) { assertTrue(backoff.acquire("key/$it", 0)) }
        assertFalse(backoff.acquire("overflow", 0))
    }

    @Test
    fun ownCardSurvivesRestartAndPruneUntilExplicitTerminalRelease() {
        val kv = MemKv()
        val links = InviteLinks(kv)
        val own = byteArrayOf(3, 4, 5)
        val sealed = byteArrayOf(7, 8, 9)
        assertTrue(links.putIn(inl("owner", expires = 100)))
        links.prepareCard("owner", sealed)
        links.prepareOwnCard("owner", own)
        val restarted = InviteLinks(kv)
        assertArrayEquals(sealed, restarted.card("owner"))
        assertArrayEquals(own, restarted.ownCard("owner"))
        assertEquals(1, restarted.prune(100 + InviteLinks.GRACE_SECS).second.size)
        assertTrue(restarted.card("owner") == null)
        // Deleting retry state is not permission to drop registry ownership silently.
        assertArrayEquals(own, restarted.ownCard("owner"))
        restarted.releaseOwnCard("owner")
        assertTrue(InviteLinks(kv).ownCard("owner") == null)
    }

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
        kv.rows[k] = byteArrayOf(3, 0)
        assertTrue(links.outs().isEmpty())
        kv.rows[k] = byteArrayOf(1, 1) + ByteArray(200)
        assertTrue(links.outs().isEmpty())
    }

    @Test
    fun codedMarkSurvivesAndOldRowsAreOpen() {
        val kv = MemKv()
        val links = InviteLinks(kv)
        val coded = OutLink(ByteArray(16) { 7 }, 10, 1000, id(7), id(8), coded = true)
        assertTrue(links.putOut(coded))
        assertTrue(links.putOut(out(9)))
        assertEquals(setOf(true, false), links.outs().map { it.coded }.toSet())
        assertTrue(links.outs().single { it.secret[0] == 7.toByte() }.coded)
        // Строка версии 1 (до кода к ссылке): без последнего байта пометки, версия 1 — читается как «без кода».
        val oldKv = MemKv()
        val writer = InviteLinks(oldKv)
        writer.putOut(coded)
        val k = oldKv.rows.keys.single()
        val v2 = oldKv.rows.getValue(k)
        oldKv.rows[k] = byteArrayOf(1) + v2.copyOfRange(1, v2.size - 1)
        val read = InviteLinks(oldKv).outs().single()
        assertFalse(read.coded)
        assertArrayEquals(coded.secret, read.secret)
    }
    @Test
    fun preparedFramesSurviveRestartAndRemovalClearsOnlyTheirOwner() {
        val kv = MemKv()
        val links = InviteLinks(kv)
        val own = out(1)
        val other = out(2)
        links.putOut(own)
        links.putOut(other)
        links.putIn(inl("peer"))
        links.prepareStart(own.secret, byteArrayOf(10, 11))
        links.prepareStart(other.secret, byteArrayOf(12))
        links.prepareCard("peer", byteArrayOf(20))
        links.prepareReply("peer", byteArrayOf(30, 31))
        val restarted = InviteLinks(kv)
        assertArrayEquals(byteArrayOf(10, 11), restarted.start(own.secret))
        assertArrayEquals(byteArrayOf(20), restarted.card("peer"))
        assertArrayEquals(byteArrayOf(30, 31), restarted.reply("peer"))
        restarted.removeOut(own.secret)
        restarted.removeIn("peer")
        assertTrue(restarted.start(own.secret) == null)
        assertTrue(restarted.card("peer") == null)
        assertTrue(restarted.reply("peer") == null)
        assertArrayEquals(byteArrayOf(12), restarted.start(other.secret))
    }

    @Test
    fun legacyRowsHaveNoPreparedFrameAndPruneCleansJournal() {
        val links = InviteLinks(MemKv())
        val own = out(1, expires = 100)
        links.putOut(own)
        links.putIn(inl("peer", expires = 100))
        assertTrue(links.start(own.secret) == null)
        assertTrue(links.card("peer") == null)
        assertTrue(links.reply("peer") == null)
        links.prepareStart(own.secret, byteArrayOf(1))
        links.prepareCard("peer", byteArrayOf(2))
        links.prepareReply("peer", byteArrayOf(3))
        links.prune(100 + InviteLinks.GRACE_SECS)
        assertTrue(links.start(own.secret) == null)
        assertTrue(links.card("peer") == null)
        assertTrue(links.reply("peer") == null)
    }

}
