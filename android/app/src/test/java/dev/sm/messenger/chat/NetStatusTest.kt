package dev.sm.messenger.chat

import dev.sm.messenger.ble.BleMode
import dev.sm.messenger.relay.NodeLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetStatusTest {
    @Test
    fun workingNodeIsCalmWhateverBluetoothDoes() {
        for (ble in BleMode.values()) {
            for (link in listOf(NodeLink.Ok)) {
                assertEquals(NetStatus(Tone.Ok, "Связь есть", ""), netStatus(link, ble))
            }
        }
    }

    @Test
    fun importedNodeWithoutQueuesDoesNotClaimWorkingRoute() {
        assertEquals(false, nodeWorks(NodeLink.Idle))
        assertEquals(Tone.Warn, netStatus(NodeLink.Idle, BleMode.Off).tone)
        assertEquals("Подключение…", netStatus(NodeLink.Idle, BleMode.Off).title)
    }

    @Test
    fun noNodeWithBluetoothOffIsRed() {
        val s = netStatus(NodeLink.None, BleMode.Off)
        assertEquals(Tone.Bad, s.tone)
        assertEquals("Нет связи", s.title)
    }

    @Test
    fun noNodeButContactNearIsOnlyBluetooth() {
        assertEquals("Только Bluetooth рядом", netStatus(NodeLink.None, BleMode.Conn).title)
    }

    @Test
    fun nodeProblemsAreNamedAndSoftenedByBluetooth() {
        assertEquals(NetStatus(Tone.Bad, "Узел не отвечает", "сообщения ждут на телефоне"), netStatus(NodeLink.Down, BleMode.Off))
        assertEquals(Tone.Warn, netStatus(NodeLink.Down, BleMode.Conn).tone)
        assertEquals("Нет сети", netStatus(NodeLink.Offline, BleMode.Scan).title)
        assertEquals(Tone.Warn, netStatus(NodeLink.Connecting, BleMode.Off).tone)
    }

    @Test
    fun hintOnlyWhenMessagesWillWait() {
        assertNull(chatHint(NodeLink.Ok, BleMode.Off, false))
        assertNull(chatHint(NodeLink.Down, BleMode.Conn, true))
        assertNull(chatHint(NodeLink.Connecting, BleMode.Off, false))
        assertEquals(true, chatHint(NodeLink.None, BleMode.Off, false)!!.startsWith("Узел не подключён"))
        assertEquals(true, chatHint(NodeLink.Down, BleMode.Off, false)!!.startsWith("Узел не отвечает"))
        assertEquals(true, chatHint(NodeLink.Offline, BleMode.Off, false)!!.startsWith("Нет сети"))
    }
}
