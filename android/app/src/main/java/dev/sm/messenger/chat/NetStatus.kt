package dev.sm.messenger.chat

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.sm.messenger.ble.BleMode
import dev.sm.messenger.relay.NodeLink
import dev.sm.messenger.relay.RelayHub
import kotlinx.coroutines.delay

/** Цвет полосы связи: зелёный, жёлтый, красный. */
internal enum class Tone { Ok, Warn, Bad }

/** Что показывает полоса связи: [sub] пусто, когда всё работает (макет `ui-v11.html`, `netbar`). */
internal data class NetStatus(val tone: Tone, val title: String, val sub: String)

private const val LINK_POLL_MS = 1_000L

/** Связь с узлом, обновляется раз в секунду (поток приёма пишет её в `RelayHub.link`). */
@Composable
internal fun rememberNodeLink(): State<NodeLink> {
    val app = LocalContext.current.applicationContext as Application
    return produceState(RelayHub.get(app).link) {
        while (true) {
            value = RelayHub.get(app).link
            delay(LINK_POLL_MS)
        }
    }
}

/** Канал через интернет работает: сообщения уходят и приходят через узел. */
internal fun nodeWorks(link: NodeLink): Boolean = link == NodeLink.Ok || link == NodeLink.Idle

/**
 * Полоса связи по макету `ui-v11.html`: узел и Bluetooth рядом вместе. Узел не подключён —
 * «Только Bluetooth рядом» или «Нет связи»; узел работает — «Связь есть»; иначе состояние узла,
 * жёлтым, если контакты рядом всё же доступны по Bluetooth.
 */
internal fun netStatus(link: NodeLink, ble: BleMode): NetStatus {
    val near = ble == BleMode.Conn
    return when {
        link == NodeLink.None && near ->
            NetStatus(Tone.Warn, "Только Bluetooth рядом", "узел не подключён — примите запись узла")
        link == NodeLink.None -> {
            val bt = if (ble == BleMode.Off) "Bluetooth рядом выключен" else "рядом никого"
            NetStatus(Tone.Bad, "Нет связи", "узел не подключён · $bt")
        }
        nodeWorks(link) -> NetStatus(Tone.Ok, "Связь есть", "")
        else -> {
            val title = when (link) {
                NodeLink.Down -> "Узел не отвечает"
                NodeLink.Offline -> "Нет сети"
                else -> "Подключение…"
            }
            val bad = link == NodeLink.Down || link == NodeLink.Offline
            if (near) {
                NetStatus(Tone.Warn, title, "контактам рядом — по Bluetooth сразу, остальным — когда узел ответит")
            } else {
                NetStatus(if (bad) Tone.Bad else Tone.Warn, title, "сообщения ждут на телефоне")
            }
        }
    }
}

/** Подсказка над полем ввода (макет `chatBanner`); `null` — всё работает или идёт подключение. */
internal fun chatHint(link: NodeLink, ble: BleMode, contactNear: Boolean): String? = when {
    nodeWorks(link) || (contactNear && ble == BleMode.Conn) -> null
    link == NodeLink.None -> "Узел не подключён — сообщение уйдёт по Bluetooth, когда человек будет рядом"
    link == NodeLink.Down -> "Узел не отвечает — сообщения уйдут сами, когда связь вернётся"
    link == NodeLink.Offline -> "Нет сети — сообщения уйдут сами, когда связь вернётся"
    else -> null
}
