package dev.sm.messenger.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.ArrayDeque

internal data class JournalEntry(
    val at: Long,
    val elapsed: Long,
    val session: Long,
    val attempt: Long,
    val event: DiagnosticEvent,
    val result: DiagnosticResult,
    val value: Int,
)

internal interface JournalStorage {
    fun load(): ByteArray?
    fun save(bytes: ByteArray)
}

/** Один писатель; никаких идентификаторов людей и произвольного текста даже в формате на диске. */
internal class JournalCore(private val storage: JournalStorage, private val clock: () -> Long) {
    var enabled = false
        private set
    var unavailable = false
        private set
    var lost = 0L
        private set
    private val entries = ArrayDeque<JournalEntry>()

    fun load() {
        try {
            val bytes = storage.load() ?: return
            require(bytes.size <= MAX_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == VERSION)
                val collect = input.readBoolean()
                val losses = input.readLong().also { require(it >= 0) }
                val size = input.readInt().also { require(it in 0..MAX_ENTRIES) }
                val restored = ArrayList<JournalEntry>(size)
                repeat(size) {
                    val at = input.readLong()
                    val elapsed = input.readLong()
                    val session = input.readLong()
                    val attempt = input.readLong()
                    val event = DiagnosticEvent.entries.getOrNull(input.readInt()) ?: error("event")
                    val result = DiagnosticResult.entries.getOrNull(input.readInt()) ?: error("result")
                    val value = input.readInt()
                    require(at >= 0 && elapsed >= 0 && session >= 0 && attempt >= 0 && value in 0..MAX_VALUE)
                    restored.add(JournalEntry(at, elapsed, session, attempt, event, result, value))
                }
                require(input.read() == -1)
                entries.addAll(restored)
                lost = losses
                enabled = collect
            }
            trim()
            persist()
        } catch (_: IOException) {
            fail()
        } catch (_: IllegalArgumentException) {
            fail()
        } catch (_: IllegalStateException) {
            fail()
        }
    }

    fun toggle(collect: Boolean) {
        if (unavailable) return
        enabled = collect
        trim()
        persist()
    }

    fun append(entry: JournalEntry) {
        if (!enabled || unavailable) return
        require(entry.value in 0..MAX_VALUE && entry.at >= 0 && entry.elapsed >= 0 && entry.session >= 0 && entry.attempt >= 0)
        trim()
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        persist()
    }

    fun losses(n: Long) {
        if (n > 0) lost = if (Long.MAX_VALUE - lost < n) Long.MAX_VALUE else lost + n
    }

    fun clear() {
        if (unavailable) return
        entries.clear()
        lost = 0
        persist()
    }

    fun snapshot(): List<JournalEntry> {
        trim()
        // Удаление просроченного фиксируется и на чтении, даже когда сбор выключен.
        if (!unavailable) persist()
        return if (unavailable) emptyList() else entries.toList()
    }

    private fun trim() {
        val now = clock()
        val cutoff = (now - MAX_AGE_MS).coerceAtLeast(0)
        // Изменение часов телефона назад: будущие записи тоже не храним.
        entries.removeIf { it.at < cutoff || it.at > now }
    }

    private fun persist() {
        try {
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(VERSION)
                    out.writeBoolean(enabled)
                    out.writeLong(lost)
                    out.writeInt(entries.size)
                    for (e in entries) {
                        out.writeLong(e.at)
                        out.writeLong(e.elapsed)
                        out.writeLong(e.session)
                        out.writeLong(e.attempt)
                        out.writeInt(e.event.ordinal)
                        out.writeInt(e.result.ordinal)
                        out.writeInt(e.value)
                    }
                }
            }.toByteArray()
            check(bytes.size <= MAX_BYTES)
            storage.save(bytes)
        } catch (_: IOException) {
            fail()
        }
    }

    fun markUnavailable() = fail()

    private fun fail() {
        unavailable = true
        enabled = false
        entries.clear()
    }

    companion object {
        const val MAX_ENTRIES = 2000
        const val MAX_BYTES = 1024 * 1024
        const val MAX_AGE_MS = 48L * 60 * 60 * 1000
        const val MAX_VALUE = 10_000
        private const val VERSION = 1
    }
}

/** Экспорт принимает только числовые сведения о сборке и SHA с проверкой формата. */
internal object JournalReport {
    fun text(entries: List<JournalEntry>, lost: Long, versionCode: Long, androidSdk: Int, sha: String, madeAt: Long, version: String = "unknown"): String {
        val safeVersion = if (version.matches(Regex("[0-9A-Za-z.+-]{1,40}"))) version else "unknown"
        val safeSha = if (sha.matches(Regex("[0-9a-f]{40}"))) sha else "unknown"
        val first = entries.minOfOrNull { it.at } ?: 0
        val last = entries.maxOfOrNull { it.at } ?: 0
        return buildString {
            append("Uncensored Debug diagnostics v1\n")
            append("version=$safeVersion build_code=$versionCode android_sdk=$androidSdk source_sha=$safeSha\n")
            append("export_at_ms=$madeAt first_at_ms=$first last_at_ms=$last lost_events=$lost\n")
            val starts = entries.filter { it.attempt != 0L && it.result == DiagnosticResult.Start }.associate { (it.session to it.attempt) to it.elapsed }
            append("value: Http=HTTP status; Relay results=protocol 0 Ok,1 Empty,2 NoQueue,3 TooBig,4 Quota,5 BadRequest,6 Unavailable,7 Denied; ChatGather=frame count; BleState=0 Off,1 Scan,2 Conn,3 Lost\n")
            append("at_ms elapsed_ms duration_ms session attempt event result value\n")
            for (e in entries) {
                val duration = starts[e.session to e.attempt]?.let { (e.elapsed - it).coerceAtLeast(0) } ?: 0
                append("${e.at} ${e.elapsed} $duration ${e.session} ${e.attempt} ${e.event.name} ${e.result.name} ${e.value}\n")
            }
        }
    }
}
