package dev.sm.messenger.chat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Уведомление подавляется только для открытого RESUMED-чата (CHAT-01).
 * В многооконном режиме STARTED без RESUMED не считается прочтением.
 * Адрес в composition сам по себе не означает, что пользователь видит сообщение.
 */
internal class ChatVisibility {
    @Volatile private var current: Entry? = null

    @Synchronized
    fun enter(address: String, lifecycle: Lifecycle): AutoCloseable {
        val entry = Entry(address, lifecycle)
        current = entry
        lifecycle.addObserver(entry)
        return entry
    }

    fun isVisible(address: String): Boolean = current?.visibleFor(address) == true

    private inner class Entry(private val address: String, private val lifecycle: Lifecycle) : LifecycleEventObserver, AutoCloseable {
        @Volatile private var resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        @Volatile private var closed = false

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            resumed = !closed && source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }

        fun visibleFor(candidate: String): Boolean = !closed && resumed && address == candidate

        override fun close() {
            synchronized(this@ChatVisibility) {
                closed = true
                resumed = false
                lifecycle.removeObserver(this)
                // Старый dispose после перехода/пересоздания не гасит новый экран.
                if (current === this) current = null
            }
        }
    }
}
