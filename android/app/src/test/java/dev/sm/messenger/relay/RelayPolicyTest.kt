package dev.sm.messenger.relay

import java.security.KeyPairGenerator
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Перебор доменов (ADR-86), сроки опроса и повторов (ADR-82, ADR-85, ADR-93), пин (ADR-72). */
class RelayPolicyTest {
    @Test
    fun blockedOrUnreachableSwitchesDomainAtOnce() {
        val r = DomainRotation(3, 2, now = 0)
        assertTrue(r.onFailure(RelayFailure.Blocked, 1))
        assertEquals(0, r.current)
        assertTrue(r.onFailure(RelayFailure.Unreachable, 2))
        assertEquals(1, r.current)
    }

    @Test
    fun timeoutSwitchesOnlyAfterFreeze() {
        val r = DomainRotation(2, 0, now = 0)
        r.onSuccess(10_000)
        assertFalse(r.onFailure(RelayFailure.Timeout, 10_000 + DomainRotation.FREEZE_MS - 1))
        assertEquals(0, r.current)
        assertTrue(r.onFailure(RelayFailure.Timeout, 10_000 + DomainRotation.FREEZE_MS))
        assertEquals(1, r.current)
        // Новому домену — свои 120 с.
        assertFalse(r.onFailure(RelayFailure.Timeout, 10_000 + DomainRotation.FREEZE_MS + 1))
    }

    @Test
    fun nodeRefusalKeepsDomain() {
        val r = DomainRotation(2, 1, now = 0)
        assertFalse(r.onFailure(RelayFailure.Rejected, 1_000_000))
        assertFalse(r.onFailure(RelayFailure.NoQueue, 1_000_000))
        assertEquals(1, r.current)
    }

    @Test
    fun pollBacksOffToFifteenMinutesAndResetsOnMail() {
        val s = RelaySchedule { 0.5 }
        assertEquals(RelaySchedule.POLL_MIN_MS, s.pollDelayMs())
        repeat(10) { s.onQuiet() }
        assertEquals(RelaySchedule.POLL_MAX_MS, s.pollDelayMs())
        s.onMail()
        assertEquals(RelaySchedule.POLL_MIN_MS, s.pollDelayMs())
    }

    @Test
    fun pollJitterStaysWithinQuarter() {
        val low = RelaySchedule { 0.0 }.pollDelayMs()
        val high = RelaySchedule { 0.999_999 }.pollDelayMs()
        assertEquals((RelaySchedule.POLL_MIN_MS * 0.75).toLong(), low)
        assertTrue(high in RelaySchedule.POLL_MIN_MS..(RelaySchedule.POLL_MIN_MS * 1.25).toLong())
    }

    @Test
    fun retryGrowsToFiveMinutesAndResets() {
        val s = RelaySchedule { 0.999_999 }
        val first = s.retryDelayMs()
        assertEquals(RelaySchedule.RETRY_MIN_MS, first)
        val delays = List(20) { s.retryDelayMs() }
        assertTrue(delays.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(delays.last() in (RelaySchedule.RETRY_MAX_MS - 1)..RelaySchedule.RETRY_MAX_MS)
        s.onSuccess()
        assertEquals(RelaySchedule.RETRY_MIN_MS, s.retryDelayMs())
        assertEquals(RelaySchedule.RETRY_MIN_MS, RelaySchedule { 0.0 }.retryDelayMs())
    }

    @Test
    fun channelLivesFiveToFifteenMinutes() {
        assertEquals(RelaySchedule.CHANNEL_MIN_MS, RelaySchedule { 0.0 }.channelLifetimeMs())
        val max = RelaySchedule { 0.999_999 }.channelLifetimeMs()
        assertTrue(max in RelaySchedule.CHANNEL_MIN_MS until RelaySchedule.CHANNEL_MAX_MS)
    }

    @Test
    fun pinMatchesSpkiHashOnly() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val key = gen.generateKeyPair().public
        val other = gen.generateKeyPair().public
        val pin = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        assertTrue(spkiPinned(key, listOf(ByteArray(32), pin)))
        assertFalse(spkiPinned(other, listOf(pin)))
    }

    @Test
    fun endpointRejectsUnsafeValues() {
        val pin = listOf(ByteArray(32))
        NodeEndpoint(listOf("a.example.org", "b-2.example.net"), 443, "/x7_k-9", pin)
        val bad = listOf<() -> Unit>(
            { NodeEndpoint(emptyList(), 443, "/p", pin) },
            { NodeEndpoint(listOf("1.2.3.4"), 443, "/p", pin) },
            { NodeEndpoint(listOf("Upper.example"), 443, "/p", pin) },
            { NodeEndpoint(listOf("user@host.example"), 443, "/p", pin) },
            { NodeEndpoint(listOf("host.example:80"), 443, "/p", pin) },
            { NodeEndpoint(listOf("-a.example"), 443, "/p", pin) },
            { NodeEndpoint(listOf("a.example"), 0, "/p", pin) },
            { NodeEndpoint(listOf("a.example"), 443, "p", pin) },
            { NodeEndpoint(listOf("a.example"), 443, "/p?q", pin) },
            { NodeEndpoint(listOf("a.example"), 443, "/../p", pin) },
            { NodeEndpoint(listOf("a.example"), 443, "/p", emptyList()) },
            { NodeEndpoint(listOf("a.example"), 443, "/p", listOf(ByteArray(31))) },
        )
        bad.forEachIndexed { i, f ->
            assertTrue("случай $i", runCatching(f).exceptionOrNull() is IllegalArgumentException)
        }
    }
}
