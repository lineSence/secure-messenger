package dev.sm.messenger.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * База приложения (ADR-63): SQLite из Android, файл `sm.db` в каталоге приложения (резервное
 * копирование выключено, ADR-56).
 *
 * Содержимое каждой строки — AES-256-GCM ключом `sm.store` ([SnapshotCipher]), связанные
 * данные — `sm/v1/db/` + таблица + id строки: строку нельзя подложить под другую таблицу или id.
 * Открытыми остаются только внутренние id, порядок и время (ADR-63 п.2): ключи записей Signal
 * (в них адрес контакта — его ключ личности) хранятся как HMAC-SHA256 ключом Keystore
 * `sm.index` ([RowIndex]), по файлу их не связать с ключами людей.
 *
 * Ошибка расшифровки строки — отказ (ADR-63 п.5): вызывающий получает исключение, строка не
 * используется и не перезаписывается молча.
 */
internal class SecureDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, FILE, null, VERSION) {

    val cipher = SnapshotCipher(SnapshotCipher.STORE_KEY)
    val index = RowIndex()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kv (tbl TEXT NOT NULL, k TEXT NOT NULL, v BLOB NOT NULL, PRIMARY KEY (tbl, k)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, added_at INTEGER NOT NULL, row BLOB NOT NULL)")
        createMessages(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == VERSION) {
            createMessages(db)
            return
        }
        throw SQLiteException("неизвестная версия базы $oldVersion → $newVersion")
    }

    /**
     * История (ADR-63 п.2): открыты id строки, внутренний id контакта и время на этом телефоне;
     * id сообщения — HMAC `sm.index` ([RowIndex]), чтобы найти строку по подтверждению и не
     * записать дубль; текст, направление и статус — в зашифрованной строке.
     */
    private fun createMessages(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, contact INTEGER NOT NULL, " +
                "at INTEGER NOT NULL, mid TEXT NOT NULL UNIQUE, row BLOB NOT NULL)",
        )
        db.execSQL("CREATE INDEX messages_by_contact ON messages (contact, id)")
    }

    /** Строка `kv`: расшифрованное значение или `null`, если строки нет. Подмена — [CorruptRow]. */
    fun kvGet(table: String, id: ByteArray): ByteArray? {
        val k = hex(index.of(table, id))
        return readableDatabase.rawQuery("SELECT v FROM kv WHERE tbl = ? AND k = ?", arrayOf(table, k)).use { c ->
            if (!c.moveToFirst()) return null
            cipher.open(kvAad(table, k), c.getBlob(0)) ?: throw CorruptRow("kv/$table")
        }
    }

    fun kvPut(table: String, id: ByteArray, value: ByteArray) {
        val k = hex(index.of(table, id))
        val row = ContentValues().apply {
            put("tbl", table)
            put("k", k)
            put("v", cipher.seal(kvAad(table, k), value))
        }
        if (writableDatabase.insertWithOnConflict("kv", null, row, SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw SQLiteException("строка kv/$table не записана")
        }
    }

    fun kvDelete(table: String, id: ByteArray) {
        val k = hex(index.of(table, id))
        writableDatabase.delete("kv", "tbl = ? AND k = ?", arrayOf(table, k))
    }

    private fun kvAad(table: String, k: String) = "db/kv/$table/$k"

    /**
     * [block] — одна транзакция SQLite: исключение или `null` — откат всего, что записано внутри,
     * в том числе записей Signal через `SignalKv` (ядро своего состояния не держит). Внутри уже
     * открытой транзакции этого потока [block] — её часть: откат тогда решает внешний вызов.
     */
    fun <T : Any> atomic(block: () -> T?): T? {
        val w = writableDatabase
        if (w.inTransaction()) return block()
        w.beginTransaction()
        try {
            val r = block() ?: return null
            w.setTransactionSuccessful()
            return r
        } finally {
            w.endTransaction()
        }
    }

    /** Строка не расшифровалась: подмена, другой ключ или повреждение. */
    class CorruptRow(where: String) : Exception("строка $where не расшифровалась")

    companion object {
        private const val FILE = "sm.db"
        private const val VERSION = 2

        @Volatile private var instance: SecureDb? = null

        fun get(context: Context): SecureDb =
            instance ?: synchronized(this) { instance ?: SecureDb(context).also { instance = it } }

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/**
 * id строк Signal в базе: HMAC-SHA256(ключ Keystore `sm.index`, таблица ‖ 0 ‖ id). Ключ из
 * процесса не извлекается; без него по файлу не понять, чья это сессия.
 */
internal class RowIndex {
    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    /** Ошибку Keystore не прячем: операция с базой не выполнится. */
    @Throws(GeneralSecurityException::class)
    fun of(table: String, id: ByteArray): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key())
        mac.update(table.toByteArray(Charsets.UTF_8))
        mac.update(0)
        return mac.doFinal(id)
    }

    private companion object {
        const val ALIAS = "sm.index"
        const val PROVIDER = "AndroidKeyStore"
    }
}
