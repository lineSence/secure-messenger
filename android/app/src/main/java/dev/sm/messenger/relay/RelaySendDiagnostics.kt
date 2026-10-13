package dev.sm.messenger.relay

import dev.sm.messenger.diagnostics.DiagnosticResult
import java.util.concurrent.LinkedBlockingQueue

/** Sender decisions use closed result codes only, never addresses, keys or node records. */
internal object RelaySendDiagnostics {
    fun sweep(
        outgoing: LinkedBlockingQueue<String>,
        waiting: () -> Set<String>,
        online: () -> Boolean,
        send: (String) -> Unit,
        record: (DiagnosticResult) -> Unit,
    ): Boolean {
        record(DiagnosticResult.Start)
        var finished = false
        try {
            val first = outgoing.poll()
            val targets = LinkedHashSet<String>()
            if (first != null) targets.add(first) else targets.addAll(waiting())
            outgoing.drainTo(targets)
            if (!online()) {
                record(DiagnosticResult.NoNetwork)
                finished = true
                return false
            }
            for (address in targets) send(address)
            record(if (targets.isEmpty()) DiagnosticResult.Empty else DiagnosticResult.Ok)
            finished = true
            return true
        } finally {
            // Diagnose even an unchecked worker failure; do not swallow or reinterpret it.
            if (!finished) record(DiagnosticResult.Failed)
        }
    }

    fun <P : Any, R : Any> route(
        peer: () -> P?,
        client: (P) -> R?,
        send: (P, R) -> Unit,
        record: (DiagnosticResult) -> Unit,
    ) {
        val target = peer() ?: return record(DiagnosticResult.NoPeer)
        val relay = client(target) ?: return record(DiagnosticResult.NoNode)
        send(target, relay)
    }
}