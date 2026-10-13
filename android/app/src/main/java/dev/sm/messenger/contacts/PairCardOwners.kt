package dev.sm.messenger.contacts

import dev.sm.messenger.ble.direct
import dev.sm.messenger.store.SecureDb
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import uniffi.sm_ffi.PairMessage
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.pairFrameDecode

/** Issued cards only, never contact sessions. All journal/Signal changes use Gate -> SQL. */
internal class PairCardOwners(private val gate: SignalGate, private val db: SecureDb) {
    fun issue(owner: String, secret: ByteArray?): ByteArray = gate.atomic {
        recover() // Retry durable terminal/abandoned cleanup before charging another issuance.
        val frame = gate.cardFrame(secret)
        val card = (pairFrameDecode(frame.direct()) as? PairMessage.Card)?.card
            ?: throw SignalFfiException.Store()
        val id = UUID.randomUUID().toString()
        gate.pinCard(card.direct())
        db.kvPut(TABLE, id.toByteArray(), encode(id, owner, card))
        live.add(owner)
        frame
    } ?: throw SignalFfiException.Store()

    /** Called before new work; a second screen must not reclaim another live screen's cards. */
    fun recover() = gate.atomic {
        for (row in db.kvAll(TABLE)) {
            val entry = decode(row)
            if (entry.owner !in live) releaseEntry(entry)
        }
        gate.cleanupCards()
        Unit
    }

    /** Caller first stops transport + fences pending callbacks; running core work is serialized. */
    fun release(owner: String) = gate.atomic {
        // Caller has terminally fenced this generation. Keep it inactive even on SQL rollback,
        // so the durable row is retried by recover rather than consuming quota until restart.
        live.remove(owner)
        for (row in db.kvAll(TABLE)) {
            val entry = decode(row)
            if (entry.owner == owner) releaseEntry(entry)
        }
        Unit
    }

    private fun releaseEntry(entry: Entry) {
        gate.releaseCard(entry.card.direct())
        db.kvDelete(TABLE, entry.id.toByteArray())
    }

    internal data class Entry(val id: String, val owner: String, val card: ByteArray)

    companion object {
        private const val TABLE = "pair_card_owners_v1"
        private val live = ConcurrentHashMap.newKeySet<String>()
        // UUIDs have fixed width. No address/session lookup is encoded in an ownership handle.
        internal fun encode(id: String, owner: String, card: ByteArray): ByteArray {
            require(UUID.fromString(id).toString() == id && UUID.fromString(owner).toString() == owner)
            require(card.isNotEmpty() && card.size <= 4096)
            return id.toByteArray(Charsets.US_ASCII) + owner.toByteArray(Charsets.US_ASCII) + card
        }
        internal fun decode(row: ByteArray): Entry {
            if (row.size !in 73..4168) throw SignalFfiException.Store()
            val id = String(row, 0, 36, Charsets.US_ASCII)
            val owner = String(row, 36, 36, Charsets.US_ASCII)
            try {
                if (UUID.fromString(id).toString() != id || UUID.fromString(owner).toString() != owner) {
                    throw SignalFfiException.Store()
                }
            } catch (_: IllegalArgumentException) { throw SignalFfiException.Store() }
            return Entry(id, owner, row.copyOfRange(72, row.size))
        }
    }
}
