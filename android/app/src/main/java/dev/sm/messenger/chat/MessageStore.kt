package dev.sm.messenger.chat

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import dev.sm.messenger.store.SecureDb
import org.json.JSONException
import org.json.JSONObject

/** Статус своего сообщения (UX-26, ADR-40): на телефоне → доставлено; не доставлено — только если отправка невозможна (UX-29). */
enum class MessageState { Queued, Delivered, Failed, Received }

/** Каким путём пришло подтверждение «доставлено» (сведения о сообщении, макет ui-v11). */
enum class MessagePath { Ble, Node }

/** Сообщение истории. [at] — время на этом телефоне, [sentAt] — по часам отправителя (недоверенное). */
class ChatLine(
    val rowId: Long,
    val mine: Boolean,
    val text: String,
    val at: Long,
    val sentAt: Long,
    val state: MessageState,
    /** Кадр сообщения принят узлом (ждёт получателя там). */
    val onNode: Boolean = false,
    /** Путь подтверждения «доставлено»; `null` — ещё не доставлено или строка до этого поля. */
    val via: MessagePath? = null,
)

/**
 * История переписки: таблица `messages` в [SecureDb] (ADR-63). Строка — JSON, зашифрованный
 * ключом `sm.store` со связанными данными `db/messages/<id контакта>/<id>`: строку нельзя
 * перенести в чужой чат, поменяв столбец `contact` (база версии 2 — без id контакта,
 * переводится при открытии, [SecureDb]). Строка, которая не расшифровалась, не показывается
 * и не перезаписывается.
 *
 * Замков объекта нет: запись — только поток `sm-chat`, часто внутри его транзакции приёма
 * ([SecureDb.atomic]); замок объекта поверх соединения SQLite дал бы взаимную блокировку с
 * чтением истории из другого потока.
 */
internal class MessageStore(private val db: SecureDb) {
    /** Своё сообщение в очереди. `false` — такое id уже есть (не должно случаться). */
    fun addOutgoing(contactRow: Long, contactAddress: String, id: ByteArray, text: String, sentAt: Long): Boolean =
        insert(contactRow, mid(OUT, contactAddress, id), Row(true, text, sentAt, MessageState.Queued))

    /** Входящее. `false` — дубль (то же id от того же контакта уже записано). */
    fun addIncoming(contactRow: Long, contactAddress: String, id: ByteArray, text: String, sentAt: Long): Boolean =
        insert(contactRow, mid(IN, contactAddress, id), Row(false, text, sentAt, MessageState.Received))

    /**
     * Новый статус своего сообщения; [via] — путь подтверждения для `Delivered`. `false` — строки
     * нет, она не открылась или статус уже такой.
     */
    fun setState(contactAddress: String, id: ByteArray, state: MessageState, via: MessagePath? = null): Boolean =
        update(contactAddress, id) { row ->
            if (row.state == state || row.state == MessageState.Delivered) {
                null
            } else {
                row.copy(state = state, via = if (state == MessageState.Delivered) via else null)
            }
        }

    /** Кадр своего сообщения принят узлом. `false` — уже отмечено, доставлено или строки нет. */
    fun markOnNode(contactAddress: String, id: ByteArray): Boolean =
        update(contactAddress, id) { row -> if (row.onNode || row.state != MessageState.Queued) null else row.copy(onNode = true) }

    /** Прочитать и переписать свою строку одной транзакцией (замков объекта нет: см. описание класса). */
    private fun update(contactAddress: String, id: ByteArray, change: (Row) -> Row?): Boolean {
        val mid = mid(OUT, contactAddress, id)
        return db.atomic {
            val (key, row) = db.readableDatabase.rawQuery("SELECT id, contact, row FROM messages WHERE mid = ?", arrayOf(mid)).use { c ->
                if (!c.moveToFirst()) return@atomic false
                val key = c.getLong(1) to c.getLong(0)
                key to (open(key.first, key.second, c.getBlob(2)) ?: return@atomic false)
            }
            val (contactRow, rowId) = key
            if (!row.mine) return@atomic false
            val next = change(row) ?: return@atomic false
            val values = ContentValues().apply { put("row", db.cipher.seal(aad(contactRow, rowId), next.encode())) }
            db.writableDatabase.update("messages", values, "id = ?", arrayOf(rowId.toString())) == 1
        } == true
    }

    /** Последние [limit] сообщений контакта, от старых к новым; [broken] — сколько не открылось. */
    fun history(contactRow: Long, limit: Int = PAGE): Pair<List<ChatLine>, Int> {
        var bad = 0
        val out = ArrayList<ChatLine>()
        db.readableDatabase.rawQuery(
            "SELECT id, at, row FROM messages WHERE contact = ? ORDER BY id DESC LIMIT ?",
            arrayOf(contactRow.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val row = open(contactRow, id, c.getBlob(2))
                if (row == null) bad++ else out += ChatLine(id, row.mine, row.text, c.getLong(1), row.sentAt, row.state, row.onNode, row.via)
            }
        }
        out.reverse()
        return out to bad
    }

    /** Последнее сообщение контакта — для списка чатов. */
    fun last(contactRow: Long): ChatLine? = history(contactRow, 1).first.lastOrNull()

    /** Сколько входящих новее строки [afterRow] (не больше [PAGE]). */
    fun unread(contactRow: Long, afterRow: Long): Int {
        var n = 0
        db.readableDatabase.rawQuery(
            "SELECT id, row FROM messages WHERE contact = ? AND id > ? ORDER BY id DESC LIMIT ?",
            arrayOf(contactRow.toString(), afterRow.toString(), PAGE.toString()),
        ).use { c ->
            while (c.moveToNext()) if (open(contactRow, c.getLong(0), c.getBlob(1))?.mine == false) n++
        }
        return n
    }

    /** Самая новая строка контакта (для отметки «прочитано»). */
    fun lastRowId(contactRow: Long): Long =
        db.readableDatabase.rawQuery("SELECT MAX(id) FROM messages WHERE contact = ?", arrayOf(contactRow.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        }

    private fun insert(contactRow: Long, mid: String, row: Row): Boolean =
        db.atomic {
            val w = db.writableDatabase
            val values = ContentValues().apply {
                put("contact", contactRow)
                put("at", System.currentTimeMillis())
                put("mid", mid)
                put("row", ByteArray(0))
            }
            // Дубль — `false` без отката: внешняя транзакция приёма (ChatHub) должна завершиться.
            val id = w.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (id == -1L) return@atomic false
            val sealed = ContentValues().apply { put("row", db.cipher.seal(aad(contactRow, id), row.encode())) }
            if (w.update("messages", sealed, "id = ?", arrayOf(id.toString())) != 1) throw SQLiteException("строка messages не записана")
            true
        } ?: false

    /** id сообщения в базе — HMAC: направление ‖ адрес контакта ‖ id (чужое id не совпадёт со своим). */
    private fun mid(dir: Byte, contactAddress: String, id: ByteArray): String =
        SecureDb.hex(db.index.of(TABLE, byteArrayOf(dir) + contactAddress.toByteArray(Charsets.UTF_8) + SEP + id))

    /**
     * Очередь отправки пропала (испорченный снимок): свои сообщения «в очереди» становятся
     * «не доставлено» — иначе они висели бы «в очереди» без отправки. Сколько изменено.
     */
    fun failQueued(): Int =
        db.atomic {
            var n = 0
            val w = db.writableDatabase
            w.rawQuery("SELECT id, contact, row FROM messages ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    val (rowId, contactRow) = c.getLong(0) to c.getLong(1)
                    val row = open(contactRow, rowId, c.getBlob(2)) ?: continue
                    if (!row.mine || row.state != MessageState.Queued) continue
                    val values = ContentValues().apply {
                        put("row", db.cipher.seal(aad(contactRow, rowId), row.copy(state = MessageState.Failed).encode()))
                    }
                    if (w.update("messages", values, "id = ?", arrayOf(rowId.toString())) != 1) throw SQLiteException("строка messages не записана")
                    n++
                }
            }
            n
        } ?: 0

    private fun open(contactRow: Long, id: Long, sealed: ByteArray): Row? = db.cipher.open(aad(contactRow, id), sealed)?.let(Row::decode)

    private fun aad(contactRow: Long, id: Long) = SecureDb.messageAad(contactRow, id)

    /** Строка JSON; `node` и `via` — необязательные (строки до сведений о сообщении их не имеют). */
    private data class Row(
        val mine: Boolean,
        val text: String,
        val sentAt: Long,
        val state: MessageState,
        val onNode: Boolean = false,
        val via: MessagePath? = null,
    ) {
        fun encode(): ByteArray = JSONObject()
            .put("v", VERSION)
            .put("mine", mine)
            .put("text", text)
            .put("sent", sentAt)
            .put("state", state.name)
            .apply {
                if (onNode) put("node", true)
                via?.let { put("via", it.name) }
            }
            .toString()
            .toByteArray(Charsets.UTF_8)

        companion object {
            fun decode(b: ByteArray): Row? =
                try {
                    val j = JSONObject(String(b, Charsets.UTF_8))
                    if (j.getInt("v") != VERSION) {
                        null
                    } else {
                        val via = j.optString("via").let { v -> MessagePath.entries.firstOrNull { it.name == v } }
                        Row(
                            j.getBoolean("mine"),
                            j.getString("text"),
                            j.getLong("sent"),
                            MessageState.valueOf(j.getString("state")),
                            j.optBoolean("node", false),
                            via,
                        )
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
