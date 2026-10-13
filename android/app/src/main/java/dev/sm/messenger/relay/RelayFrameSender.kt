package dev.sm.messenger.relay

/** Только готовый блоб из общей очереди; Bluetooth здесь не является условием доступности узла. */
internal class RelayOutgoing(val blob: ByteArray, val messageId: ByteArray?)

internal class RelayFrameSender(
    private val send: (ByteArray) -> Unit,
    private val accepted: (ByteArray) -> Unit,
) {
    fun deliver(frames: List<RelayOutgoing>) {
        for (frame in frames) {
            send(frame.blob)
            frame.messageId?.let(accepted)
        }
    }
}
