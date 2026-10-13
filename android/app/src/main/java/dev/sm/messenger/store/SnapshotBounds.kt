package dev.sm.messenger.store

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Bounds before allocating ciphertext or invoking AEAD/native snapshot parsers. */
internal object SnapshotBounds {
    const val SEEN_PEERS = 128
    const val SEEN_IDS = 10_000
    const val OUTBOX_BYTES = 13 + 1000 * 97 + 32 * 1024 * 1024 + 29
    const val SEEN_BYTES = 5 + SEEN_PEERS * 36 + SEEN_IDS * 16 + 29

    fun read(input: InputStream, size: Long, max: Int): ByteArray {
        if (size < 0 || size > max) throw IOException("snapshot exceeds bound")
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val n = input.read(data, offset, data.size - offset)
            if (n <= 0) throw IOException("snapshot truncated")
            offset += n
        }
        if (input.read() != -1) throw IOException("snapshot grew during read")
        return data
    }

    class SeenShape(val peers: Set<String>, val ids: Int)

    /** Global native Seen bound, not merely a per-peer truncation after allocation. */
    fun seenShape(data: ByteArray): SeenShape? {
        if (data.size < 5 || data.size > SEEN_BYTES - 29) return null
        val b = ByteBuffer.wrap(data)
        if (b.get() != 1.toByte()) return null
        val peers = b.int
        if (peers !in 0..SEEN_PEERS) return null
        val keys = HashSet<String>()
        var total = 0
        repeat(peers) {
            if (b.remaining() < 36) return null
            val mailbox = ByteArray(32).also(b::get)
            val count = b.int
            if (count !in 1..SEEN_IDS || count > SEEN_IDS - total || count > b.remaining() / 16) return null
            total += count
            b.position(b.position() + count * 16)
            keys.add(mailbox.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
        if (b.hasRemaining()) return null
        return SeenShape(keys, total)
    }
}
