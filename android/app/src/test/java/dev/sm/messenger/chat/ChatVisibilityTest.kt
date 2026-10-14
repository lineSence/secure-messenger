package dev.sm.messenger.chat

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVisibilityTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test
    fun homeAndLockDoNotRequireCompositionDisposal() {
        val owner = Owner()
        val visibility = ChatVisibility()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val lease = visibility.enter("alice", owner.lifecycle)
        assertTrue(visibility.isVisible("alice"))
        assertFalse(visibility.isVisible("bob"))
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertFalse(visibility.isVisible("alice"))
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse(visibility.isVisible("alice"))
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertFalse(visibility.isVisible("alice"))
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertTrue(visibility.isVisible("alice"))
        lease.close()
        assertFalse(visibility.isVisible("alice"))
    }

    @Test
    fun backgroundEntryDoesNotSuppressIncomingMessages() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        val visibility = ChatVisibility()
        val lease = visibility.enter("alice", owner.lifecycle)
        assertFalse(visibility.isVisible("alice"))
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertTrue(visibility.isVisible("alice"))
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertFalse(visibility.isVisible("alice"))
        lease.close()
    }

    @Test
    fun oldActivityDisposalAndEventsCannotHideNewChat() {
        val old = Owner()
        val next = Owner()
        old.lifecycle.currentState = Lifecycle.State.RESUMED
        next.lifecycle.currentState = Lifecycle.State.RESUMED
        val visibility = ChatVisibility()
        val first = visibility.enter("alice", old.lifecycle)
        val second = visibility.enter("bob", next.lifecycle)
        assertFalse(visibility.isVisible("alice"))
        assertTrue(visibility.isVisible("bob"))
        first.close()
        old.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertTrue(visibility.isVisible("bob"))
        second.close()
        next.lifecycle.currentState = Lifecycle.State.CREATED
        next.lifecycle.currentState = Lifecycle.State.RESUMED
        assertFalse(visibility.isVisible("bob"))
    }

    @Test
    fun staleDisposeForSameAddressDoesNotClearReplacement() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val visibility = ChatVisibility()
        val first = visibility.enter("alice", owner.lifecycle)
        val second = visibility.enter("alice", owner.lifecycle)
        first.close()
        assertTrue(visibility.isVisible("alice"))
        second.close()
        assertFalse(visibility.isVisible("alice"))
    }
}
