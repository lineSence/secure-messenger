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
 * ждёт базу) заблокировали бы друг друга. Под [atomic] нельзя вызывать то, что берёт замки
 * объектов поверх базы (`ContactBook`).
 */
class SignalGate internal constructor(private val sessions: SignalSessions, private val db: SecureDb) {
    private val lock = ReentrantLock()

    /** [block] под замком сессий и в одной транзакции: исключение или `null` — откат. */
    internal fun <T : Any> atomic(block: () -> T?): T? = lock.withLock { db.atomic(block) }

    private fun <T : Any> call(block: () -> T): T = atomic(block) ?: throw SignalFfiException.Store()

    fun cardFrame(qrSecret: ByteArray?): ByteArray = call { sessions.cardFrame(qrSecret) }

    fun acceptCard(card: ByteBuffer, proof: ByteArray?, qrSecret: ByteArray?, hello: ContactHello): StartedSession =
        call { sessions.acceptCard(card, proof, qrSecret, hello) }

    fun acceptStart(ciphertext: ByteBuffer, expectedIdentity: ByteArray?): AcceptedSession =
        call { sessions.acceptStart(ciphertext, expectedIdentity) }

    fun replyFrame(contact: String, hello: ContactHello): ByteArray = call { sessions.replyFrame(contact, hello) }

    fun openReply(contact: String, ciphertext: ByteBuffer): ContactHello = call { sessions.openReply(contact, ciphertext) }

    fun forget(contact: String) = call { sessions.forget(contact) }

    fun encrypt(contact: String, plaintext: ByteBuffer): ByteArray = call { sessions.encrypt(contact, plaintext) }

    /** Сообщение от одного из [candidates] или `null` (ничего не записано). */
    fun open(candidates: List<String>, ciphertext: ByteBuffer): OpenedMessage? = atomic { sessions.open(candidates, ciphertext) }
}
