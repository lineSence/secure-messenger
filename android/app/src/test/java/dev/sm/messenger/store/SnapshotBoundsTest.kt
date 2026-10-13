package dev.sm.messenger.store

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SnapshotBoundsTest {
    @Test fun hostileSizeRejectedBeforeReadingOrAllocatingPayload() {
        val stream = object : InputStream() {
            override fun read(): Int = error("must reject before read")
        }
        for (max in listOf(SnapshotBounds.OUTBOX_BYTES, SnapshotBounds.SEEN_BYTES)) {
            try {
                SnapshotBounds.read(stream, Long.MAX_VALUE, max)
                fail("oversized snapshot accepted")
            } catch (_: IOException) { /* expected */ }
        }
    }

    @Test fun truncationAndConcurrentGrowthAreRejected() {
        for ((bytes, length) in listOf(byteArrayOf(1) to 2L, byteArrayOf(1, 2) to 1L)) {
            try {
                SnapshotBounds.read(ByteArrayInputStream(bytes), length, 10)
                fail("unstable snapshot accepted")
            } catch (_: IOException) { /* expected */ }
        }
    }

    @Test fun exactBoundaryIsAllowed() {
        val bytes = ByteArray(100) { it.toByte() }
        assertArrayEquals(bytes, SnapshotBounds.read(ByteArrayInputStream(bytes), 100, 100))
    }

    private fun seen(vararg counts: Int): ByteArray {
        val n = counts.sum()
        val b = ByteBuffer.allocate(5 + counts.size * 36 + n * 16)
        b.put(1).putInt(counts.size)
        counts.forEachIndexed { index, count ->
            b.put(ByteArray(32) { index.toByte() }).putInt(count)
            b.put(ByteArray(count * 16))
        }
        return b.array()
    }

    @Test fun globalSeenBoundNotPerPeerOnly() {
        assertNotNull(SnapshotBounds.seenShape(seen(5000, 5000)))
        assertNull(SnapshotBounds.seenShape(seen(5001, 5000)))
        assertNull(SnapshotBounds.seenShape(seen(*IntArray(129) { 1 })))
    }

    @Test fun negativeOversizedCountsTruncationAndTrailingBytesRejected() {
        assertNull(SnapshotBounds.seenShape(ByteBuffer.allocate(5).put(1).putInt(Int.MAX_VALUE).array()))
        assertNull(SnapshotBounds.seenShape(ByteBuffer.allocate(41).put(1).putInt(1).put(ByteArray(32)).putInt(-1).array()))
        assertNull(SnapshotBounds.seenShape(seen(1).dropLast(1).toByteArray()))
        assertNull(SnapshotBounds.seenShape(seen(1) + byteArrayOf(0)))
        assertNotNull(SnapshotBounds.seenShape(seen()))
    }
}
