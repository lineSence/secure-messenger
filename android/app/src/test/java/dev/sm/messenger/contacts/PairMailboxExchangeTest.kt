package dev.sm.messenger.contacts

import org.junit.Assert.*
import org.junit.Test

class PairMailboxExchangeTest {
    private class Port : PairMailboxPort {
        val id = byteArrayOf(1)
        var available = true
        var peer = false
        var ack = false
        var prepares = 0
        val queued = ArrayDeque<ByteArray>()
        override fun prepare(): ByteArray? { prepares++; return id.takeIf { available } }
        override fun frames(): List<ByteArray> = buildList {
            while (queued.isNotEmpty() && size < PairMailboxExchange.MAX_FRAMES) add(queued.removeFirst())
        }
        override fun hasPeer(): Boolean = peer
        override fun delivered(id: ByteArray): Boolean = ack && id.contentEquals(this.id)
    }

    @Test fun gattWriteAloneDoesNotCompleteExchange() {
        val p = Port().apply { queued.add(byteArrayOf(2)); peer = true }
        val e = PairMailboxExchange(p, 0)
        val batch = e.step(0) as MailboxStep.Send
        assertSame(MailboxStep.Wait, e.step(1))
        e.written(batch.token)
        assertSame(MailboxStep.Wait, e.step(2))
        p.ack = true
        assertTrue((e.step(3) as MailboxStep.Complete).ready)
        assertEquals(1, p.prepares)
    }

    @Test fun deliveredMailboxWithoutPeerAddressIsNotReady() {
        val p = Port().apply { ack = true }
        val e = PairMailboxExchange(p, 0)
        assertSame(MailboxStep.Wait, e.step(1))
        p.peer = true
        assertTrue((e.step(2) as MailboxStep.Complete).ready)
    }

    @Test fun sendsPeerAcknowledgementBeforeClosingEvenIfOwnMessageWasDelivered() {
        val p = Port().apply { peer = true; ack = true; queued.add(byteArrayOf(9)) }
        val e = PairMailboxExchange(p, 0)
        val batch = e.step(1) as MailboxStep.Send
        assertSame(MailboxStep.Wait, e.step(2))
        e.written(batch.token)
        assertTrue((e.step(3) as MailboxStep.Complete).ready)
    }

    @Test fun wrongOrOldBatchCallbackCannotCompleteNewBatch() {
        val p = Port().apply { queued.add(byteArrayOf(2)) }
        val e = PairMailboxExchange(p, 0)
        val first = e.step(0) as MailboxStep.Send
        e.written(first.token + 1)
        assertSame(MailboxStep.Wait, e.step(1))
        e.written(first.token)
        p.queued.add(byteArrayOf(3))
        val second = e.step(2) as MailboxStep.Send
        e.written(first.token)
        assertSame(MailboxStep.Wait, e.step(3))
        e.written(second.token)
        p.peer = true; p.ack = true
        assertTrue((e.step(4) as MailboxStep.Complete).ready)
    }

    @Test fun noNodeOrPassDoesNotFailSavedQrContactOrReadFrames() {
        val p = Port().apply { available = false; queued.add(byteArrayOf(7)) }
        val e = PairMailboxExchange(p, 0)
        assertFalse((e.step(1) as MailboxStep.Complete).ready)
        assertEquals(1, p.queued.size)
        assertFalse((e.step(2) as MailboxStep.Complete).ready)
        assertEquals(1, p.prepares)
    }

    @Test fun timeoutDoesNotDrainQueuedMailOrPretendDelivery() {
        val p = Port().apply { queued.add(byteArrayOf(2)) }
        val e = PairMailboxExchange(p, 100)
        assertFalse((e.step(100 + PairMailboxExchange.TIMEOUT_MS) as MailboxStep.Complete).ready)
        assertEquals(0, p.prepares)
        assertEquals(1, p.queued.size)
    }

    @Test fun lostGattConfirmationHasBoundedWait() {
        val p = Port().apply { queued.add(byteArrayOf(2)) }
        val e = PairMailboxExchange(p, 0)
        assertTrue(e.step(0) is MailboxStep.Send)
        assertSame(MailboxStep.Wait, e.step(PairMailboxExchange.TIMEOUT_MS - 1))
        assertFalse((e.step(PairMailboxExchange.TIMEOUT_MS) as MailboxStep.Complete).ready)
    }

    @Test fun twoQrPhonesExchangeRoutesAndSignalAcknowledgementsBeforeClosing() {
        class Phone : PairMailboxPort {
            lateinit var other: Phone
            val queue = ArrayDeque<ByteArray>()
            var peer = false
            var ack = false
            override fun prepare(): ByteArray { queue.add(byteArrayOf(1)); return byteArrayOf(1) }
            override fun frames(): List<ByteArray> = buildList { while (queue.isNotEmpty()) add(queue.removeFirst()) }
            override fun hasPeer() = peer
            override fun delivered(id: ByteArray) = ack
            fun receive(frame: ByteArray) {
                if (frame[0] == 1.toByte()) { peer = true; queue.add(byteArrayOf(2)) }
                else ack = true
            }
        }
        val a = Phone(); val b = Phone(); a.other = b; b.other = a
        val ea = PairMailboxExchange(a, 0); val eb = PairMailboxExchange(b, 0)
        repeat(8) { n ->
            for ((phone, exchange) in listOf(a to ea, b to eb)) {
                val step = exchange.step(n.toLong())
                if (step is MailboxStep.Send) step.frames.forEach {
                    phone.other.receive(it)
                    exchange.written(step.token)
                }
            }
        }
        assertTrue((ea.step(10) as MailboxStep.Complete).ready)
        assertTrue((eb.step(10) as MailboxStep.Complete).ready)
        assertTrue(a.peer && b.peer && a.ack && b.ack)
    }

    @Test fun batchIsBoundedByPairLinkCapacity() {
        val p = Port().apply { repeat(20) { queued.add(byteArrayOf(it.toByte())) } }
        val e = PairMailboxExchange(p, 0)
        val batch = e.step(0) as MailboxStep.Send
        assertEquals(8, batch.frames.size)
        assertEquals(12, p.queued.size)
    }
}
