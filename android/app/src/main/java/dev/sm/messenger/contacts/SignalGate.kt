package dev.sm.messenger.contacts

import dev.sm.messenger.store.SecureDb
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import uniffi.sm_ffi.AcceptedSession
import uniffi.sm_ffi.ContactHello
import uniffi.sm_ffi.OpenedMessage
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.SignalSessions
import uniffi.sm_ffi.StartedSession

/**
 * Единственный вход к сессиям Signal устройства (ревью v0.0.1-alpha, п.13–14). Каждый вызов —
 * под общим замком и в одной транзакции SQLite ([SecureDb.atomic]): ядро своего состояния не
 * держит и пишет только через [SignalKv], поэтому сбой записи или отказ посреди операции
 * откатывает её целиком, а не оставляет сессию наполовину сдвинутой.
 *
 * Порядок замков везде один: этот замок → транзакция SQLite → замок ядра. Без общего замка
 * поток сообщений (`sm-chat`, держит транзакцию и ждёт ядро) и поток сопряжения (держит ядро и
 * ждёт базу) заблокировали бы друг друга. ContactBook использует только SQLite-транзакции, без монитора; его методы
 * допустимы под [atomic]. Никогда не входить в Gate из внешней SQL-транзакции.
 */
class SignalGate internal constructor(private val sessions: SignalSessions, private val db: SecureDb) {
    private val lock = ReentrantLock()

    internal val pairSessions = PairSessionOwners(object : PairSessionPort {
        override fun get(contact: String) = db.kvGet(PairSessionOwners.TABLE, contact.toByteArray(Charsets.US_ASCII))
        override fun put(contact: String, row: ByteArray) = db.kvPut(PairSessionOwners.TABLE, contact.toByteArray(Charsets.US_ASCII), row)
        override fun delete(contact: String) = db.kvDelete(PairSessionOwners.TABLE, contact.toByteArray(Charsets.US_ASCII))
        override fun rows() = db.kvAll(PairSessionOwners.TABLE)
        override fun known(contact: String) = ContactBook(db).find(contact) != null
        override fun forget(contact: String) = sessions.forget(contact)
        override fun corrupt(): Nothing = throw SignalFfiException.Store()
        override fun <T : Any> atomic(block: () -> T?): T? = this@SignalGate.atomic(block)
    })

    /** [block] под замком сессий и в одной транзакции: исключение или `null` — откат. */
    internal fun <T : Any> atomic(block: () -> T?): T? {
        // Fail closed rather than acquire Gate while an external SQL transaction is held.
        if (db.writableDatabase.inTransaction() && !lock.isHeldByCurrentThread) throw SignalFfiException.Store()
        return lock.withLock { db.atomic(block) }
    }

    private fun <T : Any> call(block: () -> T): T = atomic(block) ?: throw SignalFfiException.Store()

    fun cleanupCards() = call {
        // Startup card recovery also retries abandoned SESSION owners; journals stay separate.
        pairSessions.recover()
        sessions.cleanupCards()
    }

    fun pinCard(card: ByteBuffer) = call { sessions.pinCard(card) }

    /** Caller commits terminal ownership state in the SAME outer atomic transaction. */
    fun releaseCard(card: ByteBuffer) = call { sessions.releaseCard(card) }

    fun cardFrame(qrSecret: ByteArray?): ByteArray = call { sessions.cardFrame(qrSecret) }

    fun acceptCard(card: ByteBuffer, proof: ByteArray?, qrSecret: ByteArray?, hello: ContactHello): StartedSession =
        call { sessions.acceptCard(card, proof, qrSecret, hello).also { pairSessions.invalidate(it.contact) } }

    fun linkCardFrame(linkSecret: ByteBuffer, senderId: ByteBuffer, node: String): ByteArray =
        call { sessions.linkCardFrame(linkSecret, senderId, node) }

    fun acceptLinkCard(
        card: ByteBuffer,
        proof: ByteBuffer,
        senderId: ByteBuffer,
        node: String,
        linkSecret: ByteBuffer,
        hello: ContactHello,
    ): StartedSession = call { sessions.acceptLinkCard(card, proof, senderId, node, linkSecret, hello).also { pairSessions.invalidate(it.contact) } }

    fun acceptStart(ciphertext: ByteBuffer, expectedIdentity: ByteArray?): AcceptedSession =
        call { sessions.acceptStart(ciphertext, expectedIdentity).also { pairSessions.invalidate(it.contact) } }

    fun replyFrame(contact: String, hello: ContactHello): ByteArray = call { sessions.replyFrame(contact, hello) }

    fun openReply(contact: String, ciphertext: ByteBuffer): ContactHello = call { sessions.openReply(contact, ciphertext) }

    fun forget(contact: String) = call { sessions.forget(contact); pairSessions.invalidate(contact) }

    fun encrypt(contact: String, plaintext: ByteBuffer): ByteArray = call { sessions.encrypt(contact, plaintext) }

    /** Сообщение от одного из [candidates] или `null` (ничего не записано). */
    fun open(candidates: List<String>, ciphertext: ByteBuffer): OpenedMessage? = atomic { sessions.open(candidates, ciphertext) }
}
