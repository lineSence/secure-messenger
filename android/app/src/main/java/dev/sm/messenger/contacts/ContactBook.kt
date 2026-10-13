package dev.sm.messenger.contacts

import android.content.ContentValues
import android.database.sqlite.SQLiteException
import dev.sm.messenger.relay.MailboxTables
import dev.sm.messenger.store.SecureDb
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject
import uniffi.sm_ffi.PlatformKv
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.beaconKeyNew

/** Контакт (ADR-23): «сверен» — добавлен по QR или код безопасности сверен (UX-4). */
class Contact(
    val rowId: Long,
    /** Адрес в хранилищах Signal — ключ личности в hex. */
    val address: String,
    val name: String,
    val identityKey: ByteArray,
    val beaconKey: ByteArray,
    val verified: Boolean,
    val addedAt: Long,
    val incarnation: String = UUID.randomUUID().toString(),
)

/**
 * Хранилище «ключ → значение» для ядра (ADR-63 п.3): строки таблицы `kv` в [SecureDb].
 * Любая ошибка (Keystore, SQLite, расшифровка) — [SignalFfiException.Store]: ядро отменяет
 * операцию Signal, обхода нет (ADR-63 п.5).
 */
internal class SignalKv(private val db: SecureDb) : PlatformKv {
    override fun get(table: String, id: ByteArray): ByteArray? = guard { db.kvGet(table, id) }

    override fun put(table: String, id: ByteArray, value: ByteArray) = guard { db.kvPut(table, id, value) }

    override fun delete(table: String, id: ByteArray) = guard { db.kvDelete(table, id) }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (_: SecureDb.CorruptRow) {
            throw SignalFfiException.Store()
        } catch (_: GeneralSecurityException) {
            throw SignalFfiException.Store()
        } catch (_: SQLiteException) {
            throw SignalFfiException.Store()
        } catch (_: IllegalStateException) {
            throw SignalFfiException.Store()
        }
}

/**
 * Контакты: таблица `contacts`, содержимое строки (имя, ключи) зашифровано, открыты только id и
 * время добавления (ADR-63 п.2). Строка, которая не расшифровалась, в список не попадает
 * ([broken] — сколько таких), и молча не перезаписывается.
 */
class ContactBook internal constructor(private val db: SecureDb) {
    /** Сколько строк не расшифровалось при последнем [list]. */
    @Volatile var broken: Int = 0
        private set

    fun list(): List<Contact> = db.atomic {
        var bad = 0
        val out = ArrayList<Contact>()
        db.readableDatabase.rawQuery("SELECT id, added_at, row FROM contacts ORDER BY added_at", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val contact = db.cipher.open(aad(id), c.getBlob(2))?.let { decode(id, c.getLong(1), it) }
                if (contact == null) bad++ else out += contact
            }
        }
        broken = bad
        out
    } ?: throw SQLiteException("контакты не прочитаны")

    fun find(address: String): Contact? = list().firstOrNull { it.address == address }

    /**
     * Добавляет контакт или обновляет имя и ключ маяка уже известного. «Сверен» не снимается:
     * адрес — ключ личности, тот же адрес — тот же ключ.
     */
    fun save(address: String, name: String, identityKey: ByteArray, beaconKey: ByteArray, verified: Boolean): Contact =
        // Пустая строка и её заполнение — одна транзакция: сбой между ними не оставит строку без контакта.
        db.atomic {
            val old = find(address)
            val now = System.currentTimeMillis()
            val w = db.writableDatabase
            val id = old?.rowId ?: w.insertOrThrow(
                "contacts",
                null,
                ContentValues().apply {
                    put("added_at", now)
                    put("row", ByteArray(0))
                },
            )
            val c = Contact(id, address, name, identityKey, beaconKey, verified || old?.verified == true, old?.addedAt ?: now, old?.incarnation ?: UUID.randomUUID().toString())
            val row = ContentValues().apply { put("row", db.cipher.seal(aad(id), encode(c))) }
            if (w.update("contacts", row, "id = ?", arrayOf(id.toString())) != 1) throw SQLiteException("контакт не записан")
            c
        } ?: throw SQLiteException("контакт не записан")

    /** Отметить сверенным (QR при встрече или совпал код безопасности). */
    fun markVerified(address: String): Contact? = db.atomic {
        val c = find(address) ?: return@atomic null
        save(c.address, c.name, c.identityKey, c.beaconKey, verified = true)
    }

    /** Удалить контакт и его почту на узле (`relay_own`, `relay_peer`, `relay_told`) — одна транзакция. */
    fun delete(rowId: Long) {
        db.atomic {
            val address = list().firstOrNull { it.rowId == rowId }?.address
            db.writableDatabase.delete("contacts", "id = ?", arrayOf(rowId.toString()))
            if (address != null) MailboxTables.forget(address, db::kvDelete)
            true
        }
    }

    /** Свой ключ маяка (ADR-61 п.2): создаётся при первом обращении, раздаётся контактам. */
    fun ownBeaconKey(): ByteArray =
        db.atomic { db.kvGet(SELF, BEACON) ?: beaconKeyNew().also { db.kvPut(SELF, BEACON, it) } }
            ?: throw SQLiteException("ключ маяка не записан")

    private fun aad(id: Long) = "db/contacts/$id"

    private fun encode(c: Contact): ByteArray = JSONObject()
        .put("v", ROW_VERSION)
        .put("incarnation", c.incarnation)
        .put("address", c.address)
        .put("name", c.name)
        .put("identity", b64.encodeToString(c.identityKey))
        .put("beacon", b64.encodeToString(c.beaconKey))
        .put("verified", c.verified)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decode(id: Long, addedAt: Long, b: ByteArray): Contact? =
        try {
            val j = JSONObject(String(b, Charsets.UTF_8))
            if (j.getInt("v") != ROW_VERSION) {
                null
            } else {
                Contact(
                    id,
                    j.getString("address"),
                    j.getString("name"),
                    b64d.decode(j.getString("identity")),
                    b64d.decode(j.getString("beacon")),
                    j.getBoolean("verified"),
                    addedAt,
                    j.optString("incarnation", "legacy/$id/$addedAt"),
                )
            }
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val ROW_VERSION = 1
        const val SELF = "self"
        val BEACON = "beacon".toByteArray(Charsets.UTF_8)
        val b64: Base64.Encoder = Base64.getEncoder()
        val b64d: Base64.Decoder = Base64.getDecoder()
    }
}
