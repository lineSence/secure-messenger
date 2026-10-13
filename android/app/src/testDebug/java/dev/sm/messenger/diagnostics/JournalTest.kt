package dev.sm.messenger.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class JournalTest {
    private val now = 10L * JournalCore.MAX_AGE_MS
    private fun entry(at: Long = now, value: Int = 0) = JournalEntry(at, 3000, 7, 9, DiagnosticEvent.InviteOpen, DiagnosticResult.Ok, value)
    private class Memory : JournalStorage {
        @Volatile var bytes: ByteArray? = null
        var writes = 0
        var broken = false
        override fun load(): ByteArray? = bytes?.clone()
        override fun save(bytes: ByteArray) {
            if (broken) throw IOException("secret must never reach report")
            this.bytes = bytes.clone()
            writes++
        }
    }

    @Test fun disabledByDefault() {
        val store = Memory()
        val core = JournalCore(store) { now }
        core.load()
        core.append(entry())
        assertFalse(core.enabled)
        assertTrue(core.snapshot().isEmpty())
    }
    @Test fun enabledAndEventsSurviveRestart() {
        val store = Memory()
        val first = JournalCore(store) { now }
        first.load(); first.toggle(true); first.append(entry()); first.losses(2); first.snapshot()
        val restarted = JournalCore(store) { now }
        restarted.load()
        assertTrue(restarted.enabled)
        assertEquals(listOf(entry()), restarted.snapshot())
        assertEquals(2L, restarted.lost)
    }
    @Test fun toggleOffKeepsExistingButRejectsNew() {
        val core = JournalCore(Memory()) { now }
        core.toggle(true); core.append(entry()); core.toggle(false); core.append(entry(value = 1))
        assertEquals(listOf(entry()), core.snapshot())
    }
    @Test fun ringHasHardBounds() {
        val store = Memory()
        val core = JournalCore(store) { now }
        core.toggle(true)
        repeat(2500) { core.append(entry(value = it)) }
        val entries = core.snapshot()
        assertEquals(JournalCore.MAX_ENTRIES, entries.size)
        assertEquals(500, entries.first().value)
        assertTrue(store.bytes!!.size <= JournalCore.MAX_BYTES)
    }
    @Test fun oldAndFutureEventsAreRemovedOnReadAndRestart() {
        var time = now
        val store = Memory()
        val core = JournalCore(store) { time }
        core.toggle(true); core.append(entry()); core.toggle(false)
        time += JournalCore.MAX_AGE_MS + 1
        assertTrue(core.snapshot().isEmpty())
        val reopened = JournalCore(store) { time }; reopened.load()
        assertTrue(reopened.snapshot().isEmpty())
        reopened.toggle(true); reopened.append(entry(time)); time--
        assertTrue(reopened.snapshot().isEmpty())
    }
    @Test fun clearPreservesSettingAndClearsLosses() {
        val core = JournalCore(Memory()) { now }
        core.toggle(true); core.append(entry()); core.losses(5); core.clear()
        assertTrue(core.enabled); assertTrue(core.snapshot().isEmpty()); assertEquals(0L, core.lost)
    }
    @Test fun corruptSnapshotIsNotOverwritten() {
        val store = Memory(); store.bytes = byteArrayOf(1, 2, 3)
        val original = store.bytes!!.clone()
        val core = JournalCore(store) { now }
        core.load(); core.toggle(true); core.clear(); core.append(entry())
        assertTrue(core.unavailable); assertFalse(core.enabled); assertEquals(0, store.writes)
        assertArrayEquals(original, store.bytes)
    }
    @Test fun oversizeSnapshotIsRefused() {
        val store = Memory(); store.bytes = ByteArray(JournalCore.MAX_BYTES + 1)
        val core = JournalCore(store) { now }; core.load()
        assertTrue(core.unavailable); assertEquals(0, store.writes)
    }
    @Test fun writeFailureDisablesJournalAndKeepsLastStoredSnapshot() {
        val store = Memory()
        val core = JournalCore(store) { now }; core.toggle(true); core.append(entry())
        val before = store.bytes!!.clone(); store.broken = true; core.append(entry(value = 1))
        assertTrue(core.unavailable); assertFalse(core.enabled); assertTrue(core.snapshot().isEmpty())
        assertArrayEquals(before, store.bytes)
    }
    @Test fun unsafeNumericFieldsAreRefused() {
        val core = JournalCore(Memory()) { now }; core.toggle(true)
        assertThrows(IllegalArgumentException::class.java) { core.append(entry(value = Int.MAX_VALUE)) }
        assertTrue(core.snapshot().isEmpty())
    }
    @Test fun exportCannotIncludeUntrustedVersionOrSha() {
        val secret = "uncensored:i/SECRET https://private.example/path KEY PASSWORD"
        val text = JournalReport.text(listOf(entry()), 2, 567, 36, secret, now, secret)
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("example")); assertFalse(text.contains("PASSWORD"))
        assertTrue(text.contains("version=unknown")); assertTrue(text.contains("source_sha=unknown"))
        assertTrue(text.contains("build_code=567")); assertTrue(text.contains("lost_events=2"))
    }
    @Test fun eventApiNeverAcceptsTextOrPayload() {
        val forbidden = setOf(String::class.java, ByteArray::class.java, Throwable::class.java)
        for (m in Diagnostics::class.java.declaredMethods.filter { it.name == "record" || it.name == "begin" }) {
            assertTrue(m.parameterTypes.none { it in forbidden })
        }
    }
    @Test fun reportCorrelatesAttemptWithoutContactIdentifiers() {
        val start = entry().copy(elapsed = 1000, result = DiagnosticResult.Start)
        val end = entry().copy(elapsed = 1250)
        val text = JournalReport.text(listOf(start, end), 0, 1, 36, "a".repeat(40), now)
        assertTrue(text.contains("1250 250 7 9 InviteOpen Ok 0"))
    }
    @Test fun exportSuccessRequiresSuccessfulClose() {
        val data = byteArrayOf(1, 2, 3)
        val stream = ByteArrayOutputStream()
        assertTrue(JournalExport.write(data) { stream }); assertArrayEquals(data, stream.toByteArray())
        assertFalse(JournalExport.write(data) { object : ByteArrayOutputStream() { override fun close() { throw IOException("close") } } })
        assertFalse(JournalExport.write(data) { object : OutputStream() { override fun write(b: Int) { throw IOException("write") } } })
        assertFalse(JournalExport.write(data) { null })
        assertFalse(JournalExport.write(data) { throw SecurityException("uri") })
    }
    @Test fun boundedAsyncQueueReportsLossesAndDoesNotBlockCaller() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val memory = Memory()
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val store = object : JournalStorage {
            override fun load() = memory.load()
            override fun save(bytes: ByteArray) {
                if (block.get()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                memory.save(bytes)
            }
        }
        val writer = JournalWriter(store, clock = { now }, elapsed = { 3000 })
        try {
            assertTrue(writer.toggle(true).get(5, TimeUnit.SECONDS).enabled)
            block.set(true)
            writer.record(DiagnosticEvent.InviteOpen, DiagnosticResult.Start, 1, 0)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(1000) { writer.record(DiagnosticEvent.RelaySend, DiagnosticResult.Ok, 1, it) }
            assertTrue(writer.snapshot().get(1, TimeUnit.SECONDS).busy)
            block.set(false); release.countDown()
            var view = writer.snapshot().get(5, TimeUnit.SECONDS)
            var retries = 0
            while (view.busy && retries++ < 100) { Thread.sleep(5); view = writer.snapshot().get(5, TimeUnit.SECONDS) }
            assertFalse(view.busy); assertTrue(view.lost > 0); assertTrue(view.entries.size <= 130)
        } finally { release.countDown(); writer.close() }
    }
    @Test fun concurrentEventsRemainWellFormed() {
        val writer = JournalWriter(Memory(), clock = { now })
        try {
            writer.toggle(true).get(5, TimeUnit.SECONDS)
            val threads = List(4) { Thread { repeat(10) { writer.record(DiagnosticEvent.ChatQueue, DiagnosticResult.Ok, 0, it) } } }
            threads.forEach { it.start() }; threads.forEach { it.join() }
            val view = writer.snapshot().get(5, TimeUnit.SECONDS)
            assertEquals(40, view.entries.size); assertEquals(0L, view.lost)
        } finally { writer.close() }
    }
}
