package dev.sm.messenger.relay

import dev.sm.messenger.diagnostics.DiagnosticResult
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.*
import org.junit.Test

class RelaySendDiagnosticsTest {
    @Test fun emptySweepHasStartAndEmptyWithoutGatherOrNetworkSend() {
        val results = ArrayList<DiagnosticResult>()
        assertTrue(RelaySendDiagnostics.sweep(
            LinkedBlockingQueue(), { emptySet() }, { true },
            { fail("empty batch must not gather") }, { results.add(it) },
        ))
        assertEquals(listOf(DiagnosticResult.Start, DiagnosticResult.Empty), results)
    }

    @Test fun offlineSweepIsVisibleAndDurableWaitingRetriesNextSweep() {
        val results = ArrayList<DiagnosticResult>()
        val work = LinkedBlockingQueue<String>().apply { offer("contact") }
        var sent = 0
        assertFalse(RelaySendDiagnostics.sweep(
            work, { setOf("contact") }, { false }, { sent++ }, { results.add(it) },
        ))
        assertEquals(listOf(DiagnosticResult.Start, DiagnosticResult.NoNetwork), results)
        assertEquals(0, sent)
        results.clear()
        assertTrue(RelaySendDiagnostics.sweep(
            work, { setOf("contact") }, { true }, { sent++ }, { results.add(it) },
        ))
        assertEquals(1, sent)
        assertEquals(listOf(DiagnosticResult.Start, DiagnosticResult.Ok), results)
    }

    @Test fun missingPeerIsDistinctFromAnInvalidPeerNode() {
        val results = ArrayList<DiagnosticResult>()
        RelaySendDiagnostics.route<String, String>(
            { null }, { fail("no peer"); null }, { _, _ -> fail("no route") }, { results.add(it) },
        )
        RelaySendDiagnostics.route<String, String>(
            { "peer" }, { null }, { _, _ -> fail("invalid node must remain fail closed") }, { results.add(it) },
        )
        assertEquals(listOf(DiagnosticResult.NoPeer, DiagnosticResult.NoNode), results)
    }

    @Test fun validRouteAloneInvokesGatherAndDelivery() {
        var gathered = false
        RelaySendDiagnostics.route(
            { "peer" }, { "verified client" },
            { peer, relay ->
                assertEquals("peer", peer)
                assertEquals("verified client", relay)
                gathered = true
            },
            { fail("valid route must not report a skip") },
        )
        assertTrue(gathered)
    }

    @Test fun uncheckedFailureIsDiagnosedAndStillPropagates() {
        val results = ArrayList<DiagnosticResult>()
        val failure = IllegalStateException("test worker failure")
        try {
            RelaySendDiagnostics.sweep(
                LinkedBlockingQueue(), { setOf("contact") }, { true }, { throw failure }, { results.add(it) },
            )
            fail("worker failure must not be swallowed")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertEquals(listOf(DiagnosticResult.Start, DiagnosticResult.Failed), results)
    }

    @Test fun startPrecedesWaitingSnapshotSoBlockedSnapshotCanBeIdentified() {
        val results = ArrayList<DiagnosticResult>()
        RelaySendDiagnostics.sweep(
            LinkedBlockingQueue(),
            { assertEquals(listOf(DiagnosticResult.Start), results); emptySet() },
            { true }, { fail("empty batch") }, { results.add(it) },
        )
    }

    @Test fun wakeTargetsAreDeduplicatedAndWaitingIsUsedOnTheNextSweep() {
        val work = LinkedBlockingQueue<String>().apply { offer("a"); offer("a"); offer("b") }
        val sent = ArrayList<String>()
        RelaySendDiagnostics.sweep(work, { setOf("c") }, { true }, { sent.add(it) }, {})
        assertEquals(listOf("a", "b"), sent)
        sent.clear()
        RelaySendDiagnostics.sweep(work, { setOf("c") }, { true }, { sent.add(it) }, {})
        assertEquals(listOf("c"), sent)
    }
}