package dev.sm.messenger.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Журнал отправки: запись переживает кодирование, чужое и обрезанное не разбирается. */
class SendEntryTest {
    private val address = "05" + "ab".repeat(32)

    @Test
    fun roundTrip() {
        val e = SendEntry(ByteArray(16) { it.toByte() }, address, byteArrayOf(9, 8, 7))
        val d = SendEntry.decode(e.encode())!!
        assertArrayEquals(e.id, d.id)
        assertEquals(address, d.address)
        assertArrayEquals(e.blob, d.blob)
    }

    @Test
    fun emptyBlobIsAllowed() {
        val d = SendEntry.decode(SendEntry(byteArrayOf(1), address, ByteArray(0)).encode())!!
        assertEquals(0, d.blob.size)
    }

    @Test
    fun rejectsForeignAndTruncated() {
        val good = SendEntry(ByteArray(16), address, byteArrayOf(1)).encode()
        assertNull(SendEntry.decode(ByteArray(0)))
        assertNull(SendEntry.decode(byteArrayOf(2) + good.copyOfRange(1, good.size)))
        // Обрезано посреди адреса.
        assertNull(SendEntry.decode(good.copyOfRange(0, 2 + 16 + 1 + 10)))
        // Нулевые длины.
        assertNull(SendEntry.decode(byteArrayOf(1, 0, 1, 0x41)))
        assertNull(SendEntry.decode(byteArrayOf(1, 1, 5, 0)))
        // Управляющий символ в адресе.
        assertNull(SendEntry.decode(byteArrayOf(1, 1, 5, 1, 0x0A)))
    }
}
