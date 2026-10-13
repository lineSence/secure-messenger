package dev.sm.messenger.relay

import dev.sm.messenger.diagnostics.Diagnostics
import dev.sm.messenger.diagnostics.DiagnosticEvent
import dev.sm.messenger.diagnostics.DiagnosticResult
import dev.sm.messenger.ble.direct
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import uniffi.sm_ffi.RelayCommand
import uniffi.sm_ffi.RelayReply
import uniffi.sm_ffi.RelayStatus
import uniffi.sm_ffi.relayReplyMax
import uniffi.sm_ffi.relayReplyParse
import uniffi.sm_ffi.relayRequest

/**
 * Плагин HTTPS к своему узлу (ADR-84, ADR-90; интерфейс транспорта — ADR-12): `POST` на секретный
 * путь, тело запроса и ответа — один кадр `sm_core::relay` (собирает и разбирает ядро).
 *
 * - TLS — системный (`HttpsURLConnection`, Conscrypt), только HTTP/1.1; пин ключа узла
 *   ([PinningTrustManager]), без `VpnService` и без локальных портов.
 * - Соединения держит пул `HttpsURLConnection` (keep-alive): обычно одно, второе — только пока
 *   первое занято `WAIT`. Залпов рукопожатий нет (`transport-ru-2026.md`).
 * - Домены — по порядку со случайного места, смена — по [DomainRotation] (ADR-86).
 * - [renewChannel] — плановая замена канала (ADR-93): следующий запрос поднимает новое
 *   TLS-соединение, старое простаивает и закрывается по `keepalive_timeout` nginx.
 *
 * Вызовы блокирующие и потокобезопасные: ожидание почты и отправка идут из разных потоков.
 */
class HttpsRelay(
    private val node: NodeEndpoint,
    private val clock: () -> Long,
    random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private val rotation = DomainRotation(node.domains.size, random.nextInt(node.domains.size), clock())
    private var factories: List<SSLSocketFactory> = freshFactories()

    /** Поднять новый канал при следующем запросе (ADR-93). */
    fun renewChannel() {
        Diagnostics.record(DiagnosticEvent.ChannelRenew)
        val fresh = freshFactories()
        synchronized(lock) { factories = fresh }
    }

    /** Свои пины на каждый домен (ADR-135). */
    private fun freshFactories(): List<SSLSocketFactory> =
        node.domains.map { PinningTrustManager.socketFactory(it.pins) }

    /**
     * Создать очередь с ID получателя [recipientId] по пропуску [pass] (ADR-134, ADR-136) или
     * подтвердить живую; вернуть ID отправителя. Повтор безопасен — в том числе после `NO_QUEUE`:
     * ID отправителя тот же. Пропуск не принят — [RelayFailure.Rejected].
     */
    fun newQueue(recipientId: ByteArray, pass: ByteArray): ByteArray {
        val data = call(RelayCommand.NEW, recipientId, pass).data
        if (data.size != ID_LEN) throw RelayException(RelayFailure.Blocked, "ответ NEW не той длины").also { Diagnostics.record(DiagnosticEvent.RelayNew, DiagnosticResult.Protocol) }
        return data
    }

    /**
     * Билет для ссылки по пропуску [pass] — владельца или гостевому (ADR-146): одноразовый, 24 часа,
     * считается в лимит новых очередей пропуска. Узел без ключа гостевых пропусков или лимит
     * исчерпан — [RelayFailure.Rejected]: ссылка тогда идёт без билета.
     */
    fun ticket(pass: ByteArray): ByteArray {
        val data = call(RelayCommand.TICKET, ZERO_ID, pass).data
        if (data.size != ID_LEN) throw RelayException(RelayFailure.Blocked, "ответ TICKET не той длины").also { Diagnostics.record(DiagnosticEvent.RelayTicket, DiagnosticResult.Protocol) }
        return data
    }

    /**
     * Обменять билет из ссылки на гостевой пропуск этого узла (ADR-146). Повтор безопасен: тот же
     * пропуск. Билета нет, он истёк или узел перезапускался — [RelayFailure.Rejected].
     */
    fun guestPass(ticket: ByteArray): ByteArray {
        require(ticket.size == ID_LEN)
        val data = call(RelayCommand.GUEST, ticket, ByteArray(0)).data
        if (data.isEmpty()) throw RelayException(RelayFailure.Blocked, "ответ GUEST пустой").also { Diagnostics.record(DiagnosticEvent.RelayGuest, DiagnosticResult.Protocol) }
        return data
    }

    /** Положить блоб в очередь по ID отправителя. */
    fun send(senderId: ByteArray, blob: ByteArray) {
        call(RelayCommand.SEND, senderId, blob)
    }

    /**
     * Самый старый блоб первой непустой очереди из [recipientIds] (до [MAX_QUEUES], ADR-114) или
     * `null` — все пусты. [wait] — ждать на узле до 25 с (`WAIT`, по Wi-Fi), иначе ответ сразу
     * (`FETCH`, опрос на мобильной сети, ADR-85). Очереди нет — [RelayException] с
     * [RelayFailure.NoQueue] и её номером в списке ([RelayException.queue]).
     */
    fun receive(recipientIds: List<ByteArray>, wait: Boolean): Received? {
        require(recipientIds.size in 1..MAX_QUEUES && recipientIds.all { it.size == ID_LEN })
        val cmd = if (wait) RelayCommand.WAIT else RelayCommand.FETCH
        val rest = ByteArray((recipientIds.size - 1) * ID_LEN)
        recipientIds.drop(1).forEachIndexed { i, id -> id.copyInto(rest, i * ID_LEN) }
        val reply = call(cmd, recipientIds[0], rest, if (wait) WAIT_READ_MS else READ_MS)
        if (reply.status == RelayStatus.EMPTY) return null
        val d = reply.data
        if (d.size <= 1 + SEQ_LEN) throw RelayException(RelayFailure.Blocked, "блоб без номера")
        val queue = d[0].toInt() and 0xff
        if (queue >= recipientIds.size) throw RelayException(RelayFailure.Blocked, "номер очереди вне списка")
        var seq = 0L
        for (i in 1..SEQ_LEN) seq = (seq shl 8) or (d[i].toLong() and 0xff)
        return Received(queue, seq, d.copyOfRange(1 + SEQ_LEN, d.size))
    }

    /** Удалить полученный блоб с номером [seq]; повтор безопасен. */
    fun ack(recipientId: ByteArray, seq: Long) {
        val data = ByteArray(SEQ_LEN) { i -> (seq ushr (8 * (SEQ_LEN - 1 - i))).toByte() }
        call(RelayCommand.ACK, recipientId, data)
    }

    /** Удалить очередь. */
    fun deleteQueue(recipientId: ByteArray) {
        call(RelayCommand.DEL, recipientId, ByteArray(0))
    }

    /** Проба для диагностики (ADR-13): время ответа, мс. */
    fun ping(): Long {
        val t = clock()
        call(RelayCommand.PING, ZERO_ID, ByteArray(0))
        return clock() - t
    }

    /** Один запрос; ответ — `OK` или `EMPTY`, остальное — [RelayException]. */
    private fun call(cmd: RelayCommand, id: ByteArray, data: ByteArray, readMs: Int = READ_MS): RelayReply {
        val event = commandEvent(cmd)
        val attempt = Diagnostics.begin(event)
        val frame = relayRequest(cmd, id.direct(), data.direct())
            ?: throw RelayException(RelayFailure.Rejected, "кадр не собран").also { Diagnostics.record(event, DiagnosticResult.Protocol, attempt) }
        val (host, sockets) = synchronized(lock) { rotation.current.let { node.domains[it].name to factories[it] } }
        val reply = try {
            exchange(host, sockets, frame, readMs, event, attempt)
        } catch (e: RelayException) {
            Diagnostics.record(event, failureResult(e.failure), attempt)
            synchronized(lock) { rotation.onFailure(e.failure, clock()) }
            throw e
        }
        Diagnostics.record(event, statusResult(reply.status), attempt, reply.status.ordinal)
        synchronized(lock) { rotation.onSuccess(clock()) }
        return when (reply.status) {
            RelayStatus.OK, RelayStatus.EMPTY -> reply
            RelayStatus.NO_QUEUE -> throw RelayException(
                RelayFailure.NoQueue,
                "очереди нет",
                queue = reply.data.singleOrNull()?.let { it.toInt() and 0xff } ?: 0,
            )
            else -> throw RelayException(RelayFailure.Rejected, "узел отказал: ${reply.status}")
        }
    }

    private fun exchange(host: String, sockets: SSLSocketFactory, frame: ByteArray, readMs: Int, event: DiagnosticEvent, attempt: Long): RelayReply {
        val conn = URL("https", host, node.port, node.path).openConnection() as HttpsURLConnection
        var ok = false
        try {
            conn.sslSocketFactory = sockets
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(frame.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(frame) }
            // Не 200 — ответил не узел: сайт вместо секретного пути, подмена или заглушка.
            val code = conn.responseCode
            Diagnostics.record(event, DiagnosticResult.Http, attempt, code.coerceIn(0, 599))
            if (code != HttpsURLConnection.HTTP_OK) throw RelayException(RelayFailure.Blocked, "HTTP $code")
            val body = conn.inputStream.use { readBody(it) }
            val reply = relayReplyParse(body.direct()) ?: throw RelayException(RelayFailure.Blocked, "чужой ответ").also { Diagnostics.record(event, DiagnosticResult.Protocol, attempt) }
            ok = true
            return reply
        } catch (e: SSLException) {
            Diagnostics.record(event, DiagnosticResult.Tls, attempt)
            throw RelayException(RelayFailure.Blocked, "TLS: ${e.javaClass.simpleName}", e)
        } catch (e: SocketTimeoutException) {
            Diagnostics.record(event, DiagnosticResult.Timeout, attempt)
            throw RelayException(RelayFailure.Timeout, "тайм-аут", e)
        } catch (e: UnknownHostException) {
            Diagnostics.record(event, DiagnosticResult.Dns, attempt)
            throw RelayException(RelayFailure.Unreachable, "имя не разрешилось", e)
        } catch (e: ConnectException) {
            Diagnostics.record(event, DiagnosticResult.Connect, attempt)
            throw RelayException(RelayFailure.Unreachable, "нет соединения", e)
        } catch (e: NoRouteToHostException) {
            Diagnostics.record(event, DiagnosticResult.NoRoute, attempt)
            throw RelayException(RelayFailure.Unreachable, "нет маршрута", e)
        } catch (e: IOException) {
            Diagnostics.record(event, DiagnosticResult.Io, attempt)
            throw RelayException(RelayFailure.Blocked, "обрыв: ${e.javaClass.simpleName}", e)
        } finally {
            // Удачный ответ дочитан — соединение вернётся в пул; иначе — закрыть.
            if (!ok) conn.disconnect()
        }
    }

    /** Тело не длиннее самого длинного ответа узла: длиннее — не узел, дальше не читаем. */
    private fun readBody(input: InputStream): ByteArray {
        val max = relayReplyMax().toInt()
        val buf = ByteArray(max + 1)
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        if (n > max) throw RelayException(RelayFailure.Blocked, "ответ длиннее предела")
        return buf.copyOf(n)
    }

    private fun commandEvent(cmd: RelayCommand): DiagnosticEvent = when (cmd) {
        RelayCommand.NEW -> DiagnosticEvent.RelayNew
        RelayCommand.TICKET -> DiagnosticEvent.RelayTicket
        RelayCommand.GUEST -> DiagnosticEvent.RelayGuest
        RelayCommand.SEND -> DiagnosticEvent.RelaySend
        RelayCommand.FETCH -> DiagnosticEvent.RelayFetch
        RelayCommand.WAIT -> DiagnosticEvent.RelayWait
        RelayCommand.ACK -> DiagnosticEvent.RelayAck
        RelayCommand.DEL -> DiagnosticEvent.RelayDelete
        RelayCommand.PING -> DiagnosticEvent.RelayPing
    }

    private fun statusResult(status: RelayStatus): DiagnosticResult = when (status) {
        RelayStatus.OK -> DiagnosticResult.Ok
        RelayStatus.EMPTY -> DiagnosticResult.Empty
        RelayStatus.NO_QUEUE -> DiagnosticResult.NoQueue
        RelayStatus.QUOTA -> DiagnosticResult.RateLimited
        RelayStatus.TOO_BIG -> DiagnosticResult.Full
        RelayStatus.BAD_REQUEST -> DiagnosticResult.Bad
        RelayStatus.UNAVAILABLE -> DiagnosticResult.NoNetwork
        RelayStatus.DENIED -> DiagnosticResult.Rejected
    }

    private fun failureResult(failure: RelayFailure): DiagnosticResult = when (failure) {
        RelayFailure.Blocked -> DiagnosticResult.Failed
        RelayFailure.Unreachable -> DiagnosticResult.NoNetwork
        RelayFailure.Timeout -> DiagnosticResult.Timeout
        RelayFailure.Rejected -> DiagnosticResult.Rejected
        RelayFailure.NoQueue -> DiagnosticResult.NoQueue
    }

    private companion object {
        const val ID_LEN = 32
        const val SEQ_LEN = 8

        /** Очередей в одном `FETCH`/`WAIT` (`sm_core::relay::MAX_QUEUES`, ADR-114). */
        const val MAX_QUEUES = 64

        val ZERO_ID = ByteArray(ID_LEN)
        const val CONNECT_MS = 15_000
        const val READ_MS = 30_000

        /** `WAIT` держит ответ до 25 с (ADR-90); сверх — запас на сеть. */
        const val WAIT_READ_MS = 45_000
    }
}
