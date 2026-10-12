package dev.sm.messenger.relay

import dev.sm.messenger.ble.direct
import uniffi.sm_ffi.NodeFfiException
import uniffi.sm_ffi.NodeInfo
import uniffi.sm_ffi.guestPassOpen
import uniffi.sm_ffi.nodeRecordOpen
import uniffi.sm_ffi.queuePassOpen
import uniffi.sm_ffi.queueRecipientId
import uniffi.sm_ffi.queueSecretNew

/**
 * Своя почта на узле (п.11 плана 0.0.2-alpha): запись своего узла `SMN1:` (ADR-81), пропуск на
 * `NEW` `SMP1:` (ADR-136), секрет очередей устройства (ADR-134) и свои очереди на контакт
 * ([OwnQueues], период — ADR-137). Таблица `relay_account` в [RelayKv]; запись узла и пропуск
 * хранятся текстом и проверяются (подпись владельца, срок) при каждом чтении.
 */
class RelayAccount(private val kv: RelayKv) {
    val own = OwnQueues(kv)

    /** Какую запись узла получил каждый контакт. */
    val announced = AnnouncedNodes(kv)

    /** Секрет очередей устройства: создаётся при первом обращении, контактам не передаётся. */
    @Synchronized
    fun secret(): ByteArray = kv.get(TABLE, SECRET) ?: queueSecretNew().also { kv.put(TABLE, SECRET, it) }

    /** ID получателя своей очереди для контакта [address] в период [period] (ADR-134). */
    fun recipientId(address: String, period: Long): ByteArray =
        queueRecipientId(secret().direct(), address, period.toULong())

    /** Текст своей записи узла — его же получает контакт в сообщении «адрес почты». */
    fun nodeText(): String? = text(NODE)

    /** Свой узел на момент [nowSecs] или `null`: записи нет, подпись не владельца или срок вышел. */
    fun node(nowSecs: Long): NodeEndpoint? = nodeText()?.let { openNode(it, nowSecs) }?.let(::endpoint)

    /** Своя запись узла разобранной, на момент [nowSecs], или `null` (нет, подпись, срок). */
    fun nodeInfo(nowSecs: Long): NodeInfo? = nodeText()?.let { openNode(it, nowSecs) }

    /**
     * Принять запись узла (QR или текст от владельца). `false` — подпись или срок неверны, поля
     * не подходят приложению или уже есть запись новее по «выпущена».
     */
    @Synchronized
    fun setNode(text: String, nowSecs: Long): Boolean {
        val info = openNode(text, nowSecs) ?: return false
        if (endpoint(info) == null) return false
        val old = nodeText()?.let { openNode(it, nowSecs) }
        if (old != null && old.issued > info.issued) return false
        kv.put(TABLE, NODE, text.toByteArray(Charsets.US_ASCII))
        return true
    }

    /**
     * Пропуск на `NEW` (данные запроса) на момент [nowSecs] или `null`: нет, подпись не
     * владельца, срок вышел или пропуск отозван в записи узла. Пропуск владельца `SMP1:` — первым,
     * иначе гостевой, полученный по ссылке ([setGuest], ADR-146).
     */
    fun pass(nowSecs: Long): ByteArray? {
        val revoked = nodeText()?.let { openNode(it, nowSecs) }?.revoked.orEmpty()
        val owner = text(PASS)?.let { openPass(it, nowSecs) }
            ?.takeIf { bytes -> !GuestPolicy.isRevoked(revoked, bytes.copyOfRange(1, 1 + PASS_ID_LEN)) }
        return owner ?: guest(nowSecs, revoked)
    }

    /**
     * Гостевой пропуск (ADR-146) на момент [nowSecs] или `null`: нет, срок вышел, сам пропуск или
     * его родитель (пропуск владельца, от которого пошла цепочка) отозван в записи узла.
     */
    private fun guest(nowSecs: Long, revoked: List<ByteArray>): ByteArray? {
        val bytes = kv.get(TABLE, GUEST) ?: return null
        val info = openGuest(bytes, nowSecs) ?: return null
        return bytes.takeIf { !GuestPolicy.isRevoked(revoked, info.id, info.parent) }
    }

    /**
     * Сохранить гостевой пропуск, выданный узлом по билету из ссылки (ADR-146). `false` — не
     * гостевой пропуск или срок вышел. Подпись ключом узла приложение не проверяет (ключа у него
     * нет): пропуск пришёл от узла по TLS с пином, а при `NEW` его проверяет узел.
     */
    @Synchronized
    fun setGuest(bytes: ByteArray, nowSecs: Long): Boolean {
        if (openGuest(bytes, nowSecs) == null) return false
        kv.put(TABLE, GUEST, bytes)
        return true
    }

    /** Принять пропуск `SMP1:` от владельца. `false` — подпись или срок неверны. */
    @Synchronized
    fun setPass(text: String, nowSecs: Long): Boolean {
        if (openPass(text, nowSecs) == null) return false
        kv.put(TABLE, PASS, text.toByteArray(Charsets.US_ASCII))
        return true
    }

    private fun text(key: ByteArray): String? = kv.get(TABLE, key)?.toString(Charsets.US_ASCII)

    private fun openNode(text: String, nowSecs: Long): NodeInfo? =
        try {
            nodeRecordOpen(text, nowSecs.toULong())
        } catch (_: NodeFfiException) {
            null
        }

    private fun openGuest(bytes: ByteArray, nowSecs: Long) =
        try {
            guestPassOpen(bytes.direct(), nowSecs.toULong())
        } catch (_: NodeFfiException) {
            null
        }

    private fun openPass(text: String, nowSecs: Long): ByteArray? =
        try {
            queuePassOpen(text, nowSecs.toULong()).bytes
        } catch (_: NodeFfiException) {
            null
        }

    companion object {
        private const val TABLE = "relay_account"
        private const val PASS_ID_LEN = 16
        private val SECRET = "secret".toByteArray(Charsets.US_ASCII)
        private val NODE = "node".toByteArray(Charsets.US_ASCII)
        private val PASS = "pass".toByteArray(Charsets.US_ASCII)
        private val GUEST = "guest".toByteArray(Charsets.US_ASCII)

        /** Запись узла → адрес для [HttpsRelay]; `null` — поля не проходят проверки [NodeEndpoint]. */
        fun endpoint(info: NodeInfo): NodeEndpoint? =
            try {
                NodeEndpoint(info.domains.map { NodeEndpoint.Domain(it.name, it.pins) }, info.port.toInt(), info.path)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
