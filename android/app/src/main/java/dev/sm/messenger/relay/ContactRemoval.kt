package dev.sm.messenger.relay

import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.contacts.Contact
import java.util.concurrent.Executors

/**
 * Удаление контакта (макет `ui-v14.html`; собеседнику ничего не сообщается). Телефон чистит
 * [ChatHub.removeContact] — сразу, без сети. Очереди на своём узле закрываются после, одной
 * попыткой: узла нет в сети — очередь останется там до перезапуска узла (блобы живут не больше
 * 7 суток), прочесть её уже некому: строки, по которым телефон её читал, стёрты.
 */
class ContactRemoval internal constructor(
    private val chat: ChatHub,
    private val client: (String, Long) -> HttpsRelay?,
) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sm-remove").apply { isDaemon = true } }

    /** Удалить [contact]; [done] (главный поток) — `true`, если с телефона всё стёрто. */
    fun remove(contact: Contact, done: (Boolean) -> Unit) {
        chat.removeContact(contact) { queues ->
            done(queues != null)
            if (!queues.isNullOrEmpty()) worker.execute { closeQueues(queues) }
        }
    }

    private fun closeQueues(queues: List<ByteArray>) {
        val text = chat.account.nodeText() ?: return
        val relay = client(text, System.currentTimeMillis() / MS_PER_S) ?: return
        for (id in queues) {
            try {
                relay.deleteQueue(id)
            } catch (_: RelayException) {
                // Узла нет в сети: очередь останется до перезапуска узла.
            }
        }
    }

    private companion object {
        const val MS_PER_S = 1000L
    }
}
