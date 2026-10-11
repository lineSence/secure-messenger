package dev.sm.messenger.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Кэш кадров: одно шифрование на сообщение и подтверждение, повтор узнаётся (ADR-79 п.1). */
class FrameCacheTest {
    /** `kv` в памяти: ключи — hex, порча и сбой записи — по флагам. */
    private class MemKv : FrameKv {
        val rows = HashMap<String, ByteArray>()
        val corrupt = HashSet<String>()

        fun key(table: String, id: ByteArray) = table + "/" + id.joinToString("") { "%02x".format(it) }

        override fun get(table: String, id: ByteArray): ByteArray? {
            val k = key(table, id)
            if (k in corrupt) throw CorruptFrame()
            return rows[k]
        }

        override fun put(table: String, id: ByteArray, value: ByteArray) {
            rows[key(table, id)] = value
        }

        override fun delete(table: String, id: ByteArray) {
            rows.remove(key(table, id))
            corrupt.remove(key(table, id))
        }
    }

    /** «Сессия»: каждое шифрование — новый шаг цепочки, кадр = шаг ‖ открытый текст. */
    private class Chain {
        var steps = 0
        var fail = false

        fun encrypt(plain: ByteArray): ByteArray? {
            if (fail) return null
            steps++
            return byteArrayOf(steps.toByte()) + plain
        }
    }

    private val kv = MemKv()
    private val chain = Chain()
    private val cache = FrameCache(kv) { _, p -> chain.encrypt(p) }

    private fun id(n: Int) = ByteArray(FrameCache.ID_BYTES) { n.toByte() }

    private val alice = "05" + "aa".repeat(32)
    private val bob = "05" + "bb".repeat(32)
    private val plain = "привет".toByteArray()

    @Test
    fun outgoingIsEncryptedOnceAndRepeatsSendTheSameFrame() {
        val first = cache.outFrame(alice, id(1), plain)
        repeat(1000) { assertArrayEquals(first, cache.outFrame(alice, id(1), plain)) }
        assertEquals(1, chain.steps)
    }

    @Test
    fun eachMessageAndContactIsOneStep() {
        cache.outFrame(alice, id(1), plain)
        cache.outFrame(alice, id(2), plain)
        cache.outFrame(bob, id(1), plain)
        cache.outFrame(alice, id(1), plain)
        assertEquals(3, chain.steps)
    }

    @Test
    fun failedEncryptionIsNotCached() {
        chain.fail = true
        assertNull(cache.outFrame(alice, id(1), plain))
        chain.fail = false
        assertTrue(cache.outFrame(alice, id(1), plain) != null)
        assertEquals(1, chain.steps)
    }

    @Test
    fun deliveredDropsTheFrame() {
        cache.outFrame(alice, id(1), plain)
        cache.delivered(alice, id(1))
        assertTrue(kv.rows.isEmpty())
    }

    @Test
    fun corruptFrameIsNotReEncrypted() {
        cache.outFrame(alice, id(1), plain)
        kv.corrupt += kv.key(FrameCache.CT, FrameCache.cacheKey(alice, id(1)))
        try {
            cache.outFrame(alice, id(1), plain)
            fail("испорченная строка должна дать CorruptFrame")
        } catch (_: CorruptFrame) {
            // ожидаемо
        }
        assertEquals(1, chain.steps)
    }

    @Test
    fun ackIsEncryptedOnce() {
        var built = 0
        val make = { built++; "ack".toByteArray() }
        val first = cache.ackFrame(alice, id(7), make)
        assertArrayEquals(first, cache.ackFrame(alice, id(7), make))
        assertEquals(1, chain.steps)
        assertEquals(1, built)
    }

    @Test
    fun ackAndMessageWithTheSameIdDoNotCollide() {
        val msg = cache.outFrame(alice, id(1), plain)
        val ack = cache.ackFrame(alice, id(1)) { "ack".toByteArray() }
        assertTrue(!msg.contentEquals(ack))
        assertEquals(2, chain.steps)
    }

    @Test
    fun replayIsRecognisedWithSenderAndId() {
        val digest = FrameCache.sha256("кадр".toByteArray())
        cache.remember(digest, alice, id(3))
        val (address, got) = cache.replayed(digest)!!
        assertEquals(alice, address)
        assertArrayEquals(id(3), got)
        assertNull(cache.replayed(FrameCache.sha256("другой".toByteArray())))
    }

    @Test
    fun corruptOrShortReplayRowIsIgnored() {
        val digest = FrameCache.sha256("кадр".toByteArray())
        kv.put(FrameCache.RX, digest, id(3))
        assertNull(cache.replayed(digest))
        kv.corrupt += kv.key(FrameCache.RX, digest)
        assertNull(cache.replayed(digest))
    }

    @Test
    fun sha256IsStable() {
        val h = FrameCache.sha256(ByteArray(0)).joinToString("") { "%02x".format(it) }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", h)
    }
}

/** Очередь подтверждений: ничего не теряется при отказе, предел кадров соблюдается. */
class AckQueueTest {
    private val a = "05aa"
    private fun id(n: Int) = byteArrayOf(n.toByte())

    @Test
    fun deduplicatesAndEvictsOldest() {
        val q = AckQueue(max = 2)
        q.add(a, id(1))
        q.add(a, id(1))
        q.add(a, id(2))
        q.add(a, id(3))
        val out = ArrayList<ByteArray>()
        q.drain(a, out, 10) { it }
        assertEquals(listOf(2, 3), out.map { it[0].toInt() })
        assertTrue(q.pending().isEmpty())
    }

    @Test
    fun respectsFrameLimitAndKeepsTheRest() {
        val q = AckQueue(max = 256)
        repeat(40) { q.add(a, id(it)) }
        val out = ArrayList<ByteArray>()
        q.drain(a, out, 32) { it }
        assertEquals(32, out.size)
        assertEquals(setOf(a), q.pending())
        out.clear()
        q.drain(a, out, 32) { it }
        assertEquals(8, out.size)
        assertTrue(q.pending().isEmpty())
    }

    @Test
    fun failedFrameStaysQueued() {
        val q = AckQueue(max = 256)
        q.add(a, id(1))
        q.add(a, id(2))
        val out = ArrayList<ByteArray>()
        q.drain(a, out, 32) { if (it[0].toInt() == 1) null else it }
        assertEquals(listOf(2), out.map { it[0].toInt() })
        out.clear()
        q.drain(a, out, 32) { it }
        assertEquals(listOf(1), out.map { it[0].toInt() })
    }

    @Test
    fun corruptAckIsDropped() {
        val q = AckQueue(max = 256)
        q.add(a, id(1))
        q.drain(a, ArrayList(), 32) { throw CorruptFrame() }
        assertTrue(q.pending().isEmpty())
    }

    @Test
    fun storageErrorKeepsUnsentAcksAndReadyFrames() {
        val q = AckQueue(max = 256)
        (1..3).forEach { q.add(a, id(it)) }
        val out = ArrayList<ByteArray>()
        try {
            q.drain(a, out, 32) { if (it[0].toInt() == 2) throw IllegalStateException("диск") else it }
            fail("ошибка хранилища должна дойти до вызывающего")
        } catch (_: IllegalStateException) {
            // ожидаемо
        }
        assertEquals(listOf(1), out.map { it[0].toInt() })
        out.clear()
        q.drain(a, out, 32) { it }
        assertEquals(listOf(2, 3), out.map { it[0].toInt() })
    }

    @Test
    fun unknownContactIsNoOp() {
        val q = AckQueue(max = 256)
        val out = ArrayList<ByteArray>()
        q.drain("05bb", out, 32) { it }
        assertTrue(out.isEmpty())
        assertTrue(q.pending().isEmpty())
    }
}
