package dev.sm.messenger.contacts

/** ADR-151: обмен существующими сообщениями почты, пока соединение сопряжения ещё открыто. */
internal interface PairMailboxPort {
    fun prepare(): ByteArray?
    fun frames(): List<ByteArray>
    fun hasPeer(): Boolean
    fun delivered(id: ByteArray): Boolean
}

internal sealed interface MailboxStep {
    class Send(val token: Long, val frames: List<ByteArray>) : MailboxStep
    class Complete(val ready: Boolean) : MailboxStep
    data object Wait : MailboxStep
}

/** Только поток сопряжения; подтверждение GATT не заменяет подтверждение внутри Signal. */
internal class PairMailboxExchange(private val port: PairMailboxPort, private val startedAt: Long, private val prepareOnStep: Boolean = true) {
    private var prepared = false
    private var message: ByteArray? = null
    private var token = 0L
    private var inFlight = 0
    private var writtenFrames = BooleanArray(0)
    private var complete: MailboxStep.Complete? = null

    fun step(now: Long): MailboxStep {
        complete?.let { return it }
        if (now - startedAt >= TIMEOUT_MS) return finish(false)
        if (!prepared && prepareOnStep) prepared(port.prepare())
        complete?.let { return it }
        if (!prepared) return MailboxStep.Wait
        if (inFlight > 0) return MailboxStep.Wait
        val frames = port.frames().take(MAX_FRAMES)
        if (frames.isNotEmpty()) {
            token++
            inFlight = frames.size
            writtenFrames = BooleanArray(frames.size)
            return MailboxStep.Send(token, frames)
        }
        val id = message ?: return finish(false)
        return if (port.hasPeer() && port.delivered(id)) finish(true) else MailboxStep.Wait
    }

    /** IO result is published on the frame worker, never from a cancelled generation. */
    fun prepared(id: ByteArray?) {
        if (prepared || complete != null) return
        prepared = true
        message = id
        if (id == null) finish(false)
    }

    /** Вызывается только после подтверждения последней части кадра GATT. */
    fun written(batch: Long) {
        val index = writtenFrames.indexOfFirst { !it }
        written(batch, index)
    }

    fun written(batch: Long, index: Int) {
        if (batch != token || index !in writtenFrames.indices || writtenFrames[index]) return
        writtenFrames[index] = true
        inFlight--
    }

    private fun finish(ready: Boolean): MailboxStep.Complete =
        MailboxStep.Complete(ready).also { complete = it }

    companion object {
        const val TIMEOUT_MS = 60_000L
        const val MAX_FRAMES = 8
    }
}
