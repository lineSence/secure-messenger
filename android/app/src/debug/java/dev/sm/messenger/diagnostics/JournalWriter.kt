package dev.sm.messenger.diagnostics

import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class JournalWriter(
    storage: JournalStorage,
    val versionCode: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val random = SecureRandom()
    private val session = random.nextLong() and Long.MAX_VALUE
    private val started = elapsed()
    private val dropped = AtomicLong()
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { r -> Thread(r, "sm-debug-journal").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val core = JournalCore(storage, clock)
    @Volatile var collecting = false
        private set

    init {
        executor.execute {
            runCatching { core.load() }.onFailure { core.markUnavailable() }
            collecting = core.enabled
            if (collecting) record(DiagnosticEvent.AppStart, DiagnosticResult.Ok, 0, 0)
        }
    }

    fun attempt(): Long = random.nextLong() and Long.MAX_VALUE

    fun record(event: DiagnosticEvent, result: DiagnosticResult, attempt: Long, value: Int) {
        if (!collecting) return
        val entry = JournalEntry(
            clock().coerceAtLeast(0), (elapsed() - started).coerceAtLeast(0),
            session, attempt.coerceAtLeast(0), event, result, value.coerceIn(0, JournalCore.MAX_VALUE),
        )
        try {
            executor.execute {
                core.losses(dropped.getAndSet(0))
                runCatching { core.append(entry) }.onFailure { core.markUnavailable() }
                collecting = core.enabled
            }
        } catch (_: RejectedExecutionException) {
            dropped.updateAndGet { if (it == Long.MAX_VALUE) it else it + 1 }
        }
    }

    fun close() { executor.shutdown() }

    fun snapshot(): CompletableFuture<JournalView> = control { }
    fun toggle(enabled: Boolean): CompletableFuture<JournalView> {
        // Настройка и уже принятые события обрабатываются в порядке единственного писателя.
        return control { core.toggle(enabled) }
    }
    fun clear(): CompletableFuture<JournalView> = control { core.clear() }

    private fun control(action: () -> Unit): CompletableFuture<JournalView> {
        val future = CompletableFuture<JournalView>()
        try {
            executor.execute {
                core.losses(dropped.getAndSet(0))
                runCatching { action() }.onFailure { core.markUnavailable() }
                val entries = runCatching { core.snapshot() }.getOrElse { core.markUnavailable(); emptyList() }
                collecting = core.enabled
                future.complete(JournalView(true, core.enabled, core.unavailable, entries = entries, lost = core.lost))
            }
        } catch (_: RejectedExecutionException) {
            future.complete(JournalView(ready = true, enabled = collecting, busy = true))
        }
        return future
    }
}

