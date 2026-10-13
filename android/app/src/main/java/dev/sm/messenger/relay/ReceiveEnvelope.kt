package dev.sm.messenger.relay

/** A valid sequence is enough to discard/ACK an empty poison head; never invent a sequence. */
internal fun receiveEnvelope(data: ByteArray, queues: Int): Received {
    if (data.size < 9) throw RelayException(RelayFailure.Blocked, "блоб без номера")
    val queue = data[0].toInt() and 0xff
    if (queue >= queues) throw RelayException(RelayFailure.Blocked, "номер очереди вне списка")
    var seq = 0L
    for (i in 1..8) seq = (seq shl 8) or (data[i].toLong() and 0xff)
    return Received(queue, seq, data.copyOfRange(9, data.size))
}
