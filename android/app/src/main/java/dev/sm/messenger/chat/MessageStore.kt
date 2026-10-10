package dev.sm.messenger.chat

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.sm.messenger.store.SecureDb
import org.json.JSONException
import org.json.JSONObject

/** Статус своего сообщения (UX-3, ADR-40): в очереди → доставлено; 7 дней без ответа — не доставлено. */
enum class MessageState { Queued, Delivered, Failed, Received }

/** Сообщение истории. [at] — время на этом телефоне, [sentAt] — по часам отправителя (недоверенное). */
class ChatLine(
    val rowId: Long,
    val mine: Boolean,
    val text: String,
    val at: Long,
    val sentAt: Long,
    val state: MessageState,
)

/**
 * История переписки: таблица `messages` в [SecureDb] (ADR-63). Строка — JSON, зашифрованный
 * ключом `sm.store` со связанными данными `db/messages/<id>`. Строка, которая не расшифровалась,
 * не показывается и не перезаписывается.
 */
internal class MessageStore(private val db: SecureDb) {
    /** Своё сообщение в очереди. `false` — такое id уже есть (не должно случаться). */
    @Synchronized
    fun addOutgoing(contactRow: Long, contactAddress: String, id: ByteArray, text: String, sentAt: Long): Boolean =
        insert(contactRow, mid(OUT, contactAddress, id), Row(true, text, sentAt, MessageState.Queued))

    /** Входящее. `false` — дубль (то же id от того же контакта уже записано). */
    @Synchronized
    fun addIncoming(contactRow: Long, contactAddress: String, id: ByteArray, text: String, sentAt: Long): Boolean =
        insert(contactRow, mid(IN, contactAddress, id), Row(false, text, sentAt, MessageState.Received))

    /** Новый статус своего сообщения; `false` — строки нет или она не открылась. */
    @Synchronized
    fun setState(contactAddress: String, id: ByteArray, state: MessageState): Boolean {
        val mid = mid(OUT, contactAddress, id)
        val (rowId, row) = db.readableDatabase.rawQuery("SELECT id, row FROM messages WHERE mid = ?", arrayOf(mid)).use { c ->
            if (!c.moveToFirst()) return false
            val rowId = c.getLong(0)
            rowId to (open(rowId, c.getBlob(1)) ?: return false)
        }
        if (!row.mine || row.state == state || row.state == MessageState.Delivered) return false
        val values = ContentValues().apply { put("row", db.cipher.seal(aad(rowId), row.copy(state = state).encode())) }
        return db.writableDatabase.update("messages", values, "id = ?", arrayOf(rowId.toString())) == 1
    }

    /** Последние [limit] сообщений контакта, от старых к новым; [broken] — сколько не открылось. */
    @Synchronized
    fun history(contactRow: Long, limit: Int = PAGE): Pair<List<ChatLine>, Int> {
        var bad = 0
        val out = ArrayList<ChatLine>()
        db.readableDatabase.rawQuery(
            "SELECT id, at, row FROM messages WHERE contact = ? ORDER BY id DESC LIMIT ?",
            arrayOf(contactRow.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val row = open(id, c.getBlob(2))
                if (row == null) bad++ else out += ChatLine(id, row.mine, row.text, c.getLong(1), row.sentAt, row.state)
            }
        }
        out.reverse()
        return out to bad
    }

    /** Последнее сообщение контакта — для списка чатов. */
    fun last(contactRow: Long): ChatLine? = history(contactRow, 1).first.lastOrNull()

    /** Сколько входящих новее строки [afterRow] (не больше [PAGE]). */
    @Synchronized
    fun unread(contactRow: Long, afterRow: Long): Int {
        var n = 0
        db.readableDatabase.rawQuery(
            "SELECT id, row FROM messages WHERE contact = ? AND id > ? ORDER BY id DESC LIMIT ?",
            arrayOf(contactRow.toString(), afterRow.toString(), PAGE.toString()),
        ).use { c ->
            while (c.moveToNext()) if (open(c.getLong(0), c.getBlob(1))?.mine == false) n++
        }
        return n
    }

    /** Самая новая строка контакта (для отметки «прочитано»). */
    @Synchronized
    fun lastRowId(contactRow: Long): Long =
        db.readableDatabase.rawQuery("SELECT MAX(id) FROM messages WHERE contact = ?", arrayOf(contactRow.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }

    private fun insert(contactRow: Long, mid: String, row: Row): Boolean {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("contact", contactRow)
                put("at", System.currentTimeMillis())
                put("mid", mid)
                put("row", ByteArray(0))
            }
            val id = w.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (id == -1L) return false
            val sealed = ContentValues().apply { put("row", db.cipher.seal(aad(id), row.encode())) }
            w.update("messages", sealed, "id = ?", arrayOf(id.toString()))
            w.setTransactionSuccessful()
            return true
        } finally {
            w.endTransaction()
        }
    }

    /** id сообщения в базе — HMAC: направление ‖ адрес контакта ‖ id (чужое id не совпадёт со своим). */
    private fun mid(dir: Byte, contactAddress: String, id: ByteArray): String =
        SecureDb.hex(db.index.of(TABLE, byteArrayOf(dir) + contactAddress.toByteArray(Charsets.UTF_8) + SEP + id))

    private fun open(id: Long, sealed: ByteArray): Row? = db.cipher.open(aad(id), sealed)?.let(Row::decode)

    private fun aad(id: Long) = "db/$TABLE/$id"

    private data class Row(val mine: Boolean, val text: String, val sentAt: Long, val state: MessageState) {
        fun encode(): ByteArray = JSONObject()
            .put("v", VERSION)
            .put("mine", mine)
            .put("text", text)
            .put("sent", sentAt)
            .put("state", state.name)
            .toString()
            .toByteArray(Charsets.UTF_8)

        companion object {
            fun decode(b: ByteArray): Row? =
                try {
                    val j = JSONObject(String(b, Charsets.UTF_8))
                    if (j.getInt("v") != VERSION) {
                        null
                    } else {
                        Row(j.getBoolean("mine"), j.getString("text"), j.getLong("sent"), MessageState.valueOf(j.getString("state")))
                    }
                } catch (_: JSONException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
        }
    }

    private companion object {
        const val TABLE = "messages"
        const val VERSION = 1
        const val PAGE = 500
        const val OUT: Byte = 1
        const val IN: Byte = 2
        const val SEP: Byte = 0
    }
}
