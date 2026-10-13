package dev.sm.messenger.contacts

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Production port uses the SAME SignalGate -> SQL transaction as accept/forget/save. */
internal interface PairSessionPort {
    fun get(contact: String): ByteArray?
    fun put(contact: String, row: ByteArray)
    fun delete(contact: String)
    fun rows(): List<ByteArray>
    fun known(contact: String): Boolean
    fun forget(contact: String)
    fun corrupt(): Nothing
    fun <T : Any> atomic(block: () -> T?): T?
}

/** Pending SESSION ownership, distinct from ownership of our issued cards/prekeys. */
internal class PairSessionOwners(
    private val port: PairSessionPort,
    private val live: MutableSet<String> = ConcurrentHashMap.newKeySet<String>(),
) {
    private data class Entry(val owner: String, val contact: String)

    /** Called inside the accept transaction, before posting its result to the UI. */
    fun claim(owner: String, contact: String) = transaction {
        val entry = Entry(owner, contact)
        val row = encode(entry)
        port.put(contact, row)
        live.add(owner)
        Unit
    }

    /** Every successful accept (including non-pairing lanes), and forget, invalidates it. */
    fun invalidate(contact: String) = transaction { port.delete(contact); Unit }

    fun owns(owner: String, contact: String): Boolean = transaction {
        current(contact)?.owner == owner
    }

    /** Transport/generation must be fenced first. Inactivity survives a SQL rollback. */
    fun release(owner: String) {
        live.remove(owner)
        transaction {
            port.rows().map(::decode).filter { it.owner == owner }.forEach(::cleanup)
            Unit
        }
    }

    /** A declined peer is cleaned without touching another generation's same address. */
    fun releaseContact(owner: String, contact: String) = transaction {
        current(contact)?.takeIf { it.owner == owner }?.let(::cleanup)
        Unit
    }

    /** New process => empty live set. Other live screens sharing this Gate are protected. */
    fun recover() = transaction {
        port.rows().map(::decode).filter { it.owner !in live }.forEach(::cleanup)
        Unit
    }

    private fun cleanup(entry: Entry) {
        val current = current(entry.contact) ?: return
        if (current != entry) return
        // A durable contact is a completed session, not an unfinished pairing to erase.
        if (!port.known(entry.contact)) port.forget(entry.contact)
        port.delete(entry.contact)
    }

    private fun current(contact: String): Entry? = port.get(contact)?.let {
        val entry = decode(it)
        if (entry.contact != contact) port.corrupt()
        entry
    }

    private fun encode(entry: Entry): ByteArray {
        if (!validOwner(entry.owner) || !validContact(entry.contact)) port.corrupt()
        return (entry.owner + entry.contact).toByteArray(Charsets.US_ASCII)
    }

    private fun decode(row: ByteArray): Entry {
        if (row.size != OWNER_LEN + CONTACT_LEN) port.corrupt()
        val entry = Entry(String(row, 0, OWNER_LEN, Charsets.US_ASCII), String(row, OWNER_LEN, CONTACT_LEN, Charsets.US_ASCII))
        if (!validOwner(entry.owner) || !validContact(entry.contact)) port.corrupt()
        return entry
    }

    private fun validOwner(owner: String): Boolean = try {
        UUID.fromString(owner).toString() == owner
    } catch (_: IllegalArgumentException) { false }

    private fun validContact(contact: String): Boolean =
        contact.length == CONTACT_LEN && contact.all { it in '0'..'9' || it in 'a'..'f' }

    private fun <T : Any> transaction(block: () -> T): T = port.atomic(block) ?: port.corrupt()

    companion object {
        const val TABLE = "pair_session_owners_v1"
        private const val OWNER_LEN = 36
        private const val CONTACT_LEN = 66
    }
}
