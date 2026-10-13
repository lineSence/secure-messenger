package dev.sm.messenger.relay

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import dev.sm.messenger.store.SecureDb

/** Этап своей ссылки (пригласивший). */
enum class OutStage {
    /** Ждёт карточку того, кто откроет ссылку. */
    Active,

    /** Карточка принята, начало сессии отправлено — ждём ответ. */
    Started,

    /** Контакт добавлен; очередь ссылки ещё читаем — в неё пишут, пока не придёт «адрес почты». */
    Done,
}

/**
 * Своя ссылка-приглашение (ADR-83): секрет ссылки, когда создана и до какого момента действует
 * (секунды Unix), очередь ссылки на своём узле. После карточки — адрес того, кто открыл
 * ссылку: его ключ личности, ID отправителя его очереди и запись его узла.
 */
class OutLink(
    val secret: ByteArray,
    val createdAt: Long,
    val expires: Long,
    val recipientId: ByteArray,
    val senderId: ByteArray,
    val stage: OutStage = OutStage.Active,
    val contact: String = "",
    val identityKey: ByteArray = ByteArray(0),
    val peerSender: ByteArray = ByteArray(0),
    val peerNode: String = "",
    /** Ссылка с кодом (ADR-142): в списке — пометка «с кодом». Сам код не хранится. */
    val coded: Boolean = false,
) {
    init {
        require(secret.size == InviteLinks.SECRET_LEN && recipientId.size == InviteLinks.ID_LEN && senderId.size == InviteLinks.ID_LEN) { "ссылка" }
        require(stage == OutStage.Active || (contact.isNotEmpty() && peerSender.size == InviteLinks.ID_LEN)) { "этап" }
    }
}

/** Этап чужой ссылки (получивший). */
enum class InStage {
    /** Карточка отправлена — ждём начало сессии от пригласившего. */
    Sent,

    /** Начало сессии принято — ждём, что скажет пользователь («Добавить …?»). */
    Ask,
}

/**
 * Открытая чужая ссылка: адрес пригласившего (его ключ личности, ID отправителя очереди ссылки,
 * запись его узла), срок ссылки, своя очередь для него ([queue]). После начала сессии — имя и
 * ключ маяка, которые он указал (внутри Signal).
 */
class InLink(
    val inviter: String,
    val identityKey: ByteArray,
    val inviterSender: ByteArray,
    val inviterNode: String,
    val expires: Long,
    val queue: OwnQueue,
    val stage: InStage = InStage.Sent,
    val name: String = "",
    val beaconKey: ByteArray = ByteArray(0),
) {
    init {
        require(inviter.isNotEmpty() && inviterSender.size == InviteLinks.ID_LEN) { "ссылка" }
    }
}

/**
 * Ссылки-приглашения на этом телефоне: таблица `relay_invite` в [RelayKv], две строки — свои
 * ([outs]) и открытые чужие ([ins]). Строк мало (не больше [MAX_OUT] и [MAX_IN]), поэтому список
 * целиком в одной строке. Повреждённая входящая строка — отказ: её нельзя считать пустой
 * и освобождать ключи живого владельца. Исходящие legacy строки сохраняют прежний разбор.
 */
class InviteLinks(private val kv: RelayKv) {
    fun outs(): List<OutLink> = read(OUT) { d, v -> readOut(d, v) }

    fun ins(): List<InLink> {
        val links = read(IN, strict = true) { d, _ -> readIn(d) }
        if (links.map { it.inviter }.distinct().size != links.size) throw SecureDb.CorruptRow("invite owners")
        return links
    }

    /** Записать свою ссылку (та же по секрету заменяется). `false` — уже [MAX_OUT] других. */
    fun putOut(l: OutLink): Boolean {
        val rest = outs().filterNot { it.secret.contentEquals(l.secret) }
        if (rest.size >= MAX_OUT) return false
        write(OUT, rest + l) { o, x -> writeOut(o, x) }
        return true
    }

    fun removeOut(secret: ByteArray) {
        write(OUT, outs().filterNot { it.secret.contentEquals(secret) }) { o, x -> writeOut(o, x) }
        kv.delete(PREPARED, preparedKey("start", secret))
    }

    /** Записать открытую ссылку (та же по пригласившему заменяется). `false` — уже [MAX_IN] других. */
    fun putIn(l: InLink): Boolean {
        val rest = ins().filterNot { it.inviter == l.inviter }
        if (rest.size >= MAX_IN) return false
        write(IN, rest + l) { o, x -> writeIn(o, x) }
        return true
    }

    fun removeIn(inviter: String) {
        write(IN, ins().filterNot { it.inviter == inviter }) { o, x -> writeIn(o, x) }
        kv.delete(PREPARED, preparedKey("card", inviter.toByteArray(Charsets.UTF_8)))
        kv.delete(PREPARED, preparedKey("reply", inviter.toByteArray(Charsets.UTF_8)))
    }

    // Separate encrypted KV rows preserve the v1/v2 link formats. Callers MUST hold the
    // outer SignalGate.atomic / DB transaction for read-modify-write and journal updates.
    // No monitor here: Contact deletion also accesses these rows while holding SQL.
    fun start(secret: ByteArray): ByteArray? = kv.get(PREPARED, preparedKey("start", secret))

    fun prepareStart(secret: ByteArray, frame: ByteArray) = kv.put(PREPARED, preparedKey("start", secret), frame)

    fun card(inviter: String): ByteArray? = kv.get(PREPARED, preparedKey("card", inviter.toByteArray(Charsets.UTF_8)))

    fun prepareCard(inviter: String, frame: ByteArray) = kv.put(PREPARED, preparedKey("card", inviter.toByteArray(Charsets.UTF_8)), frame)

    // A distinct own-card journal survives removeIn/prune until terminal release succeeds.
    // Peer cards MUST NOT be put here. All operations require SignalGate.atomic.
    fun ownCard(inviter: String): ByteArray? = kv.get(PREPARED, preparedKey("own-card", inviter.toByteArray(Charsets.UTF_8)))

    fun prepareOwnCard(inviter: String, card: ByteArray) {
        val owners = (ownedInviters() + inviter).distinct().sorted()
        if (owners.size > MAX_OWNERS) throw SecureDb.CorruptRow("invite card owners")
        write(OWNERS, owners) { o, owner -> o.writeUTF(owner) }
        kv.put(PREPARED, preparedKey("own-card", inviter.toByteArray(Charsets.UTF_8)), card)
    }

    fun releaseOwnCard(inviter: String) {
        write(OWNERS, ownedInviters().filterNot { it == inviter }) { o, owner -> o.writeUTF(owner) }
        kv.delete(PREPARED, preparedKey("own-card", inviter.toByteArray(Charsets.UTF_8)))
    }

    /** Strict bounded index permits reclaim after ContactBook removed an invite row. */
    fun ownedInviters(): List<String> {
        val bytes = kv.get(TABLE, OWNERS) ?: return emptyList()
        if (bytes.size > MAX_OWNER_BYTES) throw SecureDb.CorruptRow("invite card owners")
        return try {
            DataInputStream(bytes.inputStream()).use { d ->
                if (d.readUnsignedByte() != VERSION_CODED) throw IOException("owner version")
                val n = d.readUnsignedByte()
                if (n > MAX_OWNERS) throw IOException("owner count")
                val owners = List(n) { d.readUTF() }
                if (d.available() != 0 || owners.any { it.isEmpty() || it.length > MAX_OWNER_CHARS } ||
                    owners != owners.distinct().sorted()
                ) throw IOException("owner index")
                owners
            }
        } catch (_: IOException) {
            throw SecureDb.CorruptRow("invite card owners")
        }
    }

    fun reply(inviter: String): ByteArray? = kv.get(PREPARED, preparedKey("reply", inviter.toByteArray(Charsets.UTF_8)))

    fun prepareReply(inviter: String, frame: ByteArray) = kv.put(PREPARED, preparedKey("reply", inviter.toByteArray(Charsets.UTF_8)), frame)

    private fun preparedKey(kind: String, id: ByteArray) = (kind + "/").toByteArray(Charsets.US_ASCII) + id

    /**
     * Убрать отжившие на момент [now] и вернуть их (вызывающий забывает сессии и удаляет
     * очереди): своя неиспользованная — по сроку; начатая — через [GRACE_SECS] после срока;
     * готовая — через [DONE_KEEP_SECS]; чужая — через [GRACE_SECS] после срока.
     */
    fun prune(now: Long): Pair<List<OutLink>, List<InLink>> {
        val (deadOut, _) = outs().partition { now >= it.expires + outKeep(it.stage) }
        val (deadIn, _) = ins().partition { now >= it.expires + GRACE_SECS }
        deadOut.forEach { removeOut(it.secret) }
        deadIn.forEach { removeIn(it.inviter) }
        return deadOut to deadIn
    }

    private fun outKeep(s: OutStage) = when (s) {
        OutStage.Active -> 0L
        OutStage.Started -> GRACE_SECS
        OutStage.Done -> DONE_KEEP_SECS
    }

    private fun <T> read(key: ByteArray, strict: Boolean = false, item: (DataInputStream, Int) -> T): List<T> {
        val b = kv.get(TABLE, key) ?: return emptyList()
        return try {
            DataInputStream(b.inputStream()).use { d ->
                val version = d.readUnsignedByte()
                if (version != VERSION && version != VERSION_CODED) throw IOException("version")
                val n = d.readUnsignedByte()
                if (strict && n > MAX_IN) throw IOException("count")
                val out = List(n) { item(d, version) }
                if (d.available() != 0) throw IOException("trailing bytes")
                out
            }
        } catch (_: IOException) {
            if (strict) throw SecureDb.CorruptRow("invite owners")
            emptyList()
        } catch (_: IllegalArgumentException) {
            if (strict) throw SecureDb.CorruptRow("invite owners")
            emptyList()
        }
    }

    private fun <T> write(key: ByteArray, items: List<T>, item: (DataOutputStream, T) -> Unit) {
        if (items.isEmpty()) {
            kv.delete(TABLE, key)
            return
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { o ->
            o.writeByte(VERSION_CODED)
            o.writeByte(items.size)
            items.forEach { item(o, it) }
        }
        kv.put(TABLE, key, bytes.toByteArray())
    }

    private fun writeOut(o: DataOutputStream, l: OutLink) {
        o.write(l.secret)
        o.writeLong(l.createdAt)
        o.writeLong(l.expires)
        o.write(l.recipientId)
        o.write(l.senderId)
        o.writeByte(l.stage.ordinal)
        o.writeUTF(l.contact)
        bytes(o, l.identityKey)
        bytes(o, l.peerSender)
        o.writeUTF(l.peerNode)
        o.writeBoolean(l.coded)
    }

    private fun readOut(d: DataInputStream, version: Int): OutLink {
        val secret = fixed(d, SECRET_LEN)
        val createdAt = d.readLong()
        val expires = d.readLong()
        val r = fixed(d, ID_LEN)
        val s = fixed(d, ID_LEN)
        val stage = OutStage.entries.getOrNull(d.readUnsignedByte()) ?: throw IOException("этап")
        val contact = d.readUTF()
        val key = bytes(d)
        val peerSender = bytes(d)
        val peerNode = d.readUTF()
        // Версия 1 (до кода к ссылке) не хранила пометку: такие ссылки — без кода.
        val coded = version >= VERSION_CODED && d.readBoolean()
        return OutLink(secret, createdAt, expires, r, s, stage, contact, key, peerSender, peerNode, coded)
    }

    private fun writeIn(o: DataOutputStream, l: InLink) {
        o.writeUTF(l.inviter)
        bytes(o, l.identityKey)
        o.write(l.inviterSender)
        o.writeUTF(l.inviterNode)
        o.writeLong(l.expires)
        o.writeLong(l.queue.period)
        o.write(l.queue.recipientId)
        o.write(l.queue.senderId)
        o.writeByte(l.stage.ordinal)
        o.writeUTF(l.name)
        bytes(o, l.beaconKey)
    }

    private fun readIn(d: DataInputStream): InLink {
        val inviter = d.readUTF()
        val key = bytes(d)
        val sender = fixed(d, ID_LEN)
        val node = d.readUTF()
        val expires = d.readLong()
        val q = OwnQueue(d.readLong(), fixed(d, ID_LEN), fixed(d, ID_LEN))
        val stage = InStage.entries.getOrNull(d.readUnsignedByte()) ?: throw IOException("этап")
        return InLink(inviter, key, sender, node, expires, q, stage, d.readUTF(), bytes(d))
    }

    private fun fixed(d: DataInputStream, n: Int) = ByteArray(n).also { d.readFully(it) }

    private fun bytes(o: DataOutputStream, b: ByteArray) {
        o.writeShort(b.size)
        o.write(b)
    }

    private fun bytes(d: DataInputStream) = fixed(d, d.readUnsignedShort())

    companion object {
        /** Своих ссылок одновременно (неиспользованных, начатых и недавно готовых). */
        const val MAX_OUT = 8

        /** Открытых чужих ссылок одновременно. */
        const val MAX_IN = 4

        /** Срок ссылки (ADR-83): 24 ч. */
        const val LIFETIME_SECS = 24L * 60 * 60

        /** Сколько ждём завершения обмена после срока ссылки. */
        const val GRACE_SECS = 24L * 60 * 60

        /** Сколько ещё читаем очередь ссылки после добавления контакта. */
        const val DONE_KEEP_SECS = 7L * 24 * 60 * 60

        const val SECRET_LEN = 16
        const val ID_LEN = 32
        private const val TABLE = "relay_invite"
        private const val PREPARED = "relay_invite_prepared"
        private const val VERSION = 1

        /** Версия строки с пометкой «с кодом» (ADR-142); версия 1 читается, запись — всегда эта. */
        private const val VERSION_CODED = 2
        private const val MAX_OWNERS = 32
        private const val MAX_OWNER_CHARS = 128
        private const val MAX_OWNER_BYTES = 2 + MAX_OWNERS * (2 + MAX_OWNER_CHARS * 3)
        private val OWNERS = "own-card-owners".toByteArray(Charsets.US_ASCII)
        private val OUT = "out".toByteArray(Charsets.US_ASCII)
        private val IN = "in".toByteArray(Charsets.US_ASCII)
    }
}

/** Retry admission only: no DB or network, bounded by the caller's live invite snapshot. */
internal class InviteRetryBackoff {
    private class Slot(var next: Long = 0, var delay: Long = MIN_DELAY, var active: Boolean = false)
    private val slots = HashMap<String, Slot>()

    @Synchronized
    fun retain(live: Set<String>) {
        slots.entries.removeAll { it.key !in live && !it.value.active }
    }

    @Synchronized
    fun acquire(key: String, now: Long): Boolean {
        val slot = slots[key] ?: run {
            if (slots.size >= InviteLinks.MAX_IN + InviteLinks.MAX_OUT) return false
            Slot().also { slots[key] = it }
        }
        if (slot.active || now < slot.next) return false
        slot.active = true
        return true
    }

    @Synchronized
    fun finish(key: String, now: Long) {
        val slot = slots[key] ?: return
        slot.active = false
        slot.next = if (now > Long.MAX_VALUE - slot.delay) Long.MAX_VALUE else now + slot.delay
        slot.delay = (slot.delay * 2).coerceAtMost(MAX_DELAY)
    }

    companion object {
        const val MIN_DELAY = 30_000L
        const val MAX_DELAY = 300_000L
    }
}
