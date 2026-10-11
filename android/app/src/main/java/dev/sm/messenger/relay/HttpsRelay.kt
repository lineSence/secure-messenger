package dev.sm.messenger.relay

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
    private var factory: SSLSocketFactory = PinningTrustManager.socketFactory(node.pins)

    /** Поднять новый канал при следующем запросе (ADR-93). */
    fun renewChannel() {
        val fresh = PinningTrustManager.socketFactory(node.pins)
        synchronized(lock) { factory = fresh }
    }

    /** Создать очередь: (ID получателя, ID отправителя). */
    fun newQueue(): Pair<ByteArray, ByteArray> {
        val data = call(RelayCommand.NEW, ZERO_ID, ByteArray(0)).data
        if (data.size != 2 * ID_LEN) throw RelayException(RelayFailure.Blocked, "ответ NEW не той длины")
        return data.copyOfRange(0, ID_LEN) to data.copyOfRange(ID_LEN, 2 * ID_LEN)
    }

    /** Положить блоб в очередь по ID отправителя. */
    fun send(senderId: ByteArray, blob: ByteArray) {
        call(RelayCommand.SEND, senderId, blob)
    }

    /**
     * Самый старый блоб очереди: (номер, блоб) или `null` — пусто. [wait] — ждать на узле до 25 с
     * (`WAIT`, по Wi-Fi), иначе ответ сразу (`FETCH`, опрос на мобильной сети, ADR-85).
     */
    fun receive(recipientId: ByteArray, wait: Boolean): Pair<Long, ByteArray>? {
        val cmd = if (wait) RelayCommand.WAIT else RelayCommand.FETCH
        val reply = call(cmd, recipientId, ByteArray(0), if (wait) WAIT_READ_MS else READ_MS)
        if (reply.status == RelayStatus.EMPTY) return null
        val d = reply.data
        if (d.size <= SEQ_LEN) throw RelayException(RelayFailure.Blocked, "блоб без номера")
        var seq = 0L
        for (i in 0 until SEQ_LEN) seq = (seq shl 8) or (d[i].toLong() and 0xff)
        return seq to d.copyOfRange(SEQ_LEN, d.size)
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
        val frame = relayRequest(cmd, id, data)
            ?: throw RelayException(RelayFailure.Rejected, "кадр не собран")
        val (host, sockets) = synchronized(lock) { node.domains[rotation.current] to factory }
        val reply = try {
            exchange(host, sockets, frame, readMs)
        } catch (e: RelayException) {
            synchronized(lock) { rotation.onFailure(e.failure, clock()) }
            throw e
        }
        synchronized(lock) { rotation.onSuccess(clock()) }
        return when (reply.status) {
            RelayStatus.OK, RelayStatus.EMPTY -> reply
            RelayStatus.NO_QUEUE -> throw RelayException(RelayFailure.NoQueue, "очереди нет")
            else -> throw RelayException(RelayFailure.Rejected, "узел отказал: ${reply.status}")
        }
    }

    private fun exchange(host: String, sockets: SSLSocketFactory, frame: ByteArray, readMs: Int): RelayReply {
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
            if (code != HttpsURLConnection.HTTP_OK) throw RelayException(RelayFailure.Blocked, "HTTP $code")
            val body = conn.inputStream.use { readBody(it) }
            val reply = relayReplyParse(body) ?: throw RelayException(RelayFailure.Blocked, "чужой ответ")
            ok = true
            return reply
        } catch (e: SSLException) {
            throw RelayException(RelayFailure.Blocked, "TLS: ${e.javaClass.simpleName}", e)
        } catch (e: SocketTimeoutException) {
            throw RelayException(RelayFailure.Timeout, "тайм-аут", e)
        } catch (e: UnknownHostException) {
            throw RelayException(RelayFailure.Unreachable, "имя не разрешилось", e)
        } catch (e: ConnectException) {
            throw RelayException(RelayFailure.Unreachable, "нет соединения", e)
        } catch (e: NoRouteToHostException) {
            throw RelayException(RelayFailure.Unreachable, "нет маршрута", e)
        } catch (e: IOException) {
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

    private companion object {
        const val ID_LEN = 32
        const val SEQ_LEN = 8
        val ZERO_ID = ByteArray(ID_LEN)
        const val CONNECT_MS = 15_000
        const val READ_MS = 30_000

        /** `WAIT` держит ответ до 25 с (ADR-90); сверх — запас на сеть. */
        const val WAIT_READ_MS = 45_000
    }
}
