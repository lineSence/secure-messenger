import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.sm.messenger.chat.MessageStore
import dev.sm.messenger.store.SecureDb
import dev.sm.messenger.store.SnapshotCipher
import dev.sm.messenger.store.RowIndex
import javax.crypto.spec.SecretKeySpec
import java.security.GeneralSecurityException
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Actual Android SQLite + production AES-GCM codec, synthetic keys (not a Keystore/device test). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageStoreTest {
    private lateinit var db: SecureDb
    private lateinit var messages: MessageStore
    private var failKey = false
    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("sm.db")
        db = SecureDb(context, SnapshotCipher(keySource = {
            if (failKey) throw GeneralSecurityException("injected key failure")
            SecretKeySpec(ByteArray(32) { 7 }, "AES")
        }), RowIndex { SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256") })
        messages = MessageStore(db)
    }
    @After fun close() { db.close() }
    private fun add(id: Int, mine: Boolean = false) {
        val bytes = java.nio.ByteBuffer.allocate(16).putInt(id).array()
        assertTrue(if (mine) messages.addOutgoing(1, "peer", bytes, "text-$id", 0) else messages.addIncoming(1, "peer", bytes, "text-$id", 0))
    }
    @Test fun pagingAndUnreadBeyondFiveHundred() {
        db.atomic { for (id in 1..601) add(id); true }
        assertEquals(601, messages.unread(1, 0))
        assertFalse(messages.readableBoundary(1, 0, 102, 601))
        assertTrue(messages.readableBoundary(1, 0, 1, 10))
        val newest = messages.page(1)
        assertEquals(500, newest.lines.size)
        assertEquals(102L, newest.oldest)
        assertTrue(newest.hasOlder)
        val older = messages.page(1, newest.oldest!!)
        assertEquals(101, older.lines.size)
        assertFalse(older.hasOlder)
        assertEquals(1L, messages.firstUnread(1, 0))
        assertEquals(601, (older.lines + newest.lines).map { it.rowId }.toSet().size)
    }
    @Test fun oneIncomingBeforeFiveHundredOutgoingIsNotLost() {
        db.atomic { add(1); for (id in 2..501) add(id, true); true }
        assertEquals(1, messages.unread(1, 0))
        assertEquals(1, messages.unread(1, 0, 2))
        assertFalse(messages.readableBoundary(1, 0, 2, 501))
        assertEquals(1L, messages.firstUnread(1, 0))
    }
    @Test fun failedSendTransactionRollsBackHistoryAndJournal() {
        try { db.atomic { add(1, true); throw SQLiteException("journal write failed") } } catch (_: SQLiteException) { }
        assertTrue(messages.page(1).lines.isEmpty())
    }
    @Test fun deleteAndCleanupIntentCommitTogether() {
        add(1)
        try { db.atomic { messages.deleteContact(1); throw SQLiteException("intent failure") } } catch (_: SQLiteException) { }
        assertEquals(1, messages.page(1).lines.size)
        db.atomic { messages.deleteContact(1); db.kvPut("chat_delete", byteArrayOf(1), "peer".toByteArray()); true }
        db.close()
        // Simulate reopen after commit, before snapshot cleanup: intent remains durable.
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = SecureDb(context, SnapshotCipher(keySource = { SecretKeySpec(ByteArray(32) { 7 }, "AES") }), RowIndex { SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256") })
        messages = MessageStore(db)
        assertTrue(messages.page(1).lines.isEmpty())
        assertEquals("peer", String(db.kvAll("chat_delete").single()))
    }
    @Test fun corruptIncomingPreventsAdvancingReadBoundary() {
        add(1)
        db.writableDatabase.execSQL("UPDATE messages SET row = X'01' WHERE id = 1")
        assertEquals(1, messages.page(1).broken)
        assertTrue(messages.page(1).lines.isEmpty())
        try { messages.readableBoundary(1, 0, 2, 10); fail("corrupt gap was acknowledged") } catch (_: SecureDb.CorruptRow) { }
    }
    @Test fun contactDeletionRollsBackWithCleanupIntentFailure() {
        val book = dev.sm.messenger.contacts.ContactBook(db)
        val contact = book.save("demo", "Аня", ByteArray(32), ByteArray(32), true)
        try { db.atomic { book.deleteWithinTransaction(contact.rowId, contact.address); throw SQLiteException("intent fault") } } catch (_: SQLiteException) { }
        assertNotNull(book.find("demo"))
        db.atomic { book.deleteWithinTransaction(contact.rowId, contact.address); db.kvPut("chat_delete", byteArrayOf(2), "demo".toByteArray()); true }
        assertNull(book.find("demo"))
        assertEquals("demo", String(db.kvAll("chat_delete").single()))
    }
    @Test fun keyFailureDoesNotCreateOutgoingRow() {
        failKey = true
        try { add(1, true); fail("expected key failure") } catch (_: GeneralSecurityException) { }
        failKey = false
        assertTrue(messages.page(1).lines.isEmpty())
    }
}
