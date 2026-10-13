package dev.sm.messenger.contacts

import dev.sm.messenger.chat.FrameCache
import dev.sm.messenger.chat.FrameKv
import org.junit.Assert.*
import org.junit.Test

/** Uses the state-owner FrameCache after integration, never substitutes a local failure for ACK. */
class PairCompletionSecurityTest {
    @Test fun terminalFailureCannotCompleteButAuthenticatedAckCan() {
        val rows = HashMap<String, ByteArray>()
        val kv = object : FrameKv {
            override fun get(table: String, id: ByteArray) = rows[table + id.contentToString()]
            override fun put(table: String, id: ByteArray, value: ByteArray) { rows[table + id.contentToString()] = value }
            override fun <T : Any> atomic(block: () -> T?): T? = block()
        }
        val cache = FrameCache(kv) { _, _ -> byteArrayOf(7) }
        val id = ByteArray(16) { 1 }
        fun exchange() = PairMailboxExchange(object : PairMailboxPort {
            override fun prepare() = id
            override fun frames() = emptyList<ByteArray>()
            override fun hasPeer() = true
            override fun delivered(id: ByteArray) = cache.isDelivered("peer", id)
        }, 0)
        cache.failed("peer", id)
        assertFalse(cache.isDelivered("peer", id))
        val failed = exchange()
        assertSame(MailboxStep.Wait, failed.step(1))
        assertFalse((failed.step(60_000) as MailboxStep.Complete).ready)
        cache.delivered("peer", id)
        assertTrue(cache.isDelivered("peer", id))
        assertTrue((exchange().step(1) as MailboxStep.Complete).ready)
    }
}
