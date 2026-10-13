package dev.sm.messenger.chat

import org.junit.Assert.*
import org.junit.Test

class MessagePathLabelTest {
    @Test fun queuedOnPhoneDoesNotPretendBluetoothWasSelected() {
        assertNull(messagePathLabel(false, false, null))
        assertNull(messagePathLabel(false, false, MessagePath.Ble))
    }
    @Test fun nodeAcceptanceAndDeliveryHaveConfirmedLabels() {
        assertEquals("Интернет", messagePathLabel(false, true, null))
        assertEquals("Интернет", messagePathLabel(true, false, MessagePath.Node))
        assertEquals("Bluetooth", messagePathLabel(true, true, MessagePath.Ble))
        assertNull(messagePathLabel(true, false, null))
    }
}
