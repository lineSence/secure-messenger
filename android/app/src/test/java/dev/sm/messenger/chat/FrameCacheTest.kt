package dev.sm.messenger.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Кэш кадров: одно шифрование на сообщение и подтверждение, повтор узнаётся (ADR-79 п.1). */
class FrameCacheTest {
    /**
     * `kv` в памяти: ключи — hex, порча и сбой записи — по флагам. [session] — состояние «сессии
     * Signal» в той же базе: [atomic] откатывает его вместе со строками, как SQLite.
     */
    private class MemKv : FrameKv {
        val rows = HashMap<String, ByteArray>()
        val corrupt = HashSet<String>()
        var session = 0
        var failPut: String? = null

        fun key(table: String, id: ByteArray) = table + "/" + id.joinToString("") { "%02x".format(it) }

        override fun get(table: String, id: ByteArray): ByteArray? {
            val k = key(table, id)
            if (k in corrupt) throw CorruptFrame()
            return rows[k]
        }

        override fun put(table: String, id: ByteArray, value: ByteArray) {
            if (table == failPut) throw IllegalStateException("диск полон")
            rows[key(table, id)] = value
            corrupt.remove(key(table, id))
        }

        override fun <T : Any> atomic(block: () -> T?): T? {
            val saved = HashMap(rows) to session
            val r = try {
                block()
            } catch (e: IllegalStateException) {
                rows.clear()
                rows.putAll(saved.first)
                session = saved.second
                throw e
            }
            if (r == null) {
                rows.clear()
                rows.putAll(saved.first)
                session = saved.second
            }
            return r
        }
    }

    /**
     * «Сессия»: каждое шифрование — новый шаг цепочки, кадр = шаг ‖ открытый текст. Шаг пишется
     * в базу до того, как шифрование может не удаться (как запись libsignal до ошибки).
     */
    private class Chain(private val kv: MemKv) {
        val steps get() = kv.session
        var fail = false

        fun encrypt(plain: ByteArray): ByteArray? {
            kv.session++
            if (fail) return null
            return byteArrayOf(kv.session.toByte()) + plain
        }
    }

    private val kv = MemKv()
    private val chain = Chain(kv)
    private val cache = FrameCache(kv) { _, p -> chain.encrypt(p) }

    private fun id(n: Int) = ByteArray(FrameCache.ID_BYTES) { n.toByte() }

    private val alice = "05" + "aa".repeat(32)
    private val bob = "05" + "bb".repeat(32)
    private val plain = "привет".toByteArray()

    @Test
    fun mailboxDeliveryNeedsAnActualSignalAcknowledgement() {
        val id = id(90)
        assertEquals(false, cache.isDelivered(alice, id))
        cache.outFrame(alice, id, plain)
        assertEquals(false, cache.isDelivered(alice, id))
        cache.delivered(alice, id)
        assertTrue(cache.isDelivered(alice, id))
        assertEquals(false, cache.isDelivered(bob, id))
    }

    @Test
    fun bluetoothAndNodeRetriesUseTheSameCiphertext() {
        val id = id(91)
        val ble = (cache.outFrame(alice, id, plain) as OutFrame.Ready).frame
        val node = (cache.outFrame(alice, id, plain) as OutFrame.Ready).frame
        assertArrayEquals(ble, node)
        assertEquals(1, chain.steps)
    }

    private fun frame(o: OutFrame?): ByteArray? = (o as? OutFrame.Ready)?.frame

    @Test
    fun outgoingIsEncryptedOnceAndRepeatsSendTheSameFrame() {
        val first = frame(cache.outFrame(alice, id(1), plain))
        repeat(1000) { assertArrayEquals(first, frame(cache.outFrame(alice, id(1), plain))) }
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
    fun failedEncryptionIsNotCachedAndRolledBack() {
        chain.fail = true
        assertNull(cache.outFrame(alice, id(1), plain))
        assertEquals(0, chain.steps)
        chain.fail = false
        assertTrue(frame(cache.outFrame(alice, id(1), plain)) != null)
        assertEquals(1, chain.steps)
    }

    @Test
    fun failedWriteRollsBackTheStep() {
        kv.failPut = FrameCache.CT
        try {
            cache.outFrame(alice, id(1), plain)
            fail("сбой записи должен дойти до вызывающего")
        } catch (_: IllegalStateException) {
            // ожидаемо: кадр не отдан
        }
        assertEquals(0, chain.steps)
        kv.failPut = null
        assertArrayEquals(byteArrayOf(1) + plain, frame(cache.outFrame(alice, id(1), plain)))
        assertEquals(1, chain.steps)
    }

    @Test
    fun failedAckWriteRollsBackTheStep() {
        kv.failPut = FrameCache.ACK
        try {
            cache.ackFrame(alice, id(7)) { "ack".toByteArray() }
            fail("сбой записи должен дойти до вызывающего")
        } catch (_: IllegalStateException) {
            // ожидаемо
        }
        kv.failPut = null
        assertArrayEquals(byteArrayOf(1) + "ack".toByteArray(), cache.ackFrame(alice, id(7)) { "ack".toByteArray() })
        assertEquals(1, chain.steps)
    }

    @Test
    fun deliveredLeavesTombstoneAndIsNeverReEncrypted() {
        cache.outFrame(alice, id(1), plain)
        cache.delivered(alice, id(1))
        assertTrue(kv.rows.values.all { it.isEmpty() })
        repeat(10) { assertEquals(OutFrame.Delivered, cache.outFrame(alice, id(1), plain)) }
        assertEquals(1, chain.steps)
    }

    @Test
    fun deliveredBeforeFirstAttemptIsNotEncrypted() {
        cache.delivered(alice, id(1))
        assertEquals(OutFrame.Delivered, cache.outFrame(alice, id(1), plain))
        assertEquals(0, chain.steps)
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
        val msg = frame(cache.outFrame(alice, id(1), plain))
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
    fun removeForgetsOnlyThatContact() {
        val q = AckQueue(max = 256)
        q.add(a, id(1))
        q.add("05bb", id(2))
        q.remove(a)
        assertEquals(setOf("05bb"), q.pending())
        val out = ArrayList<ByteArray>()
        q.drain(a, out, 10) { it }
        assertTrue(out.isEmpty())
        q.remove("05cc")
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
