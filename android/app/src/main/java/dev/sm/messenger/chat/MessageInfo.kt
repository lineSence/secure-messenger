package dev.sm.messenger.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sm.messenger.ui.Muted
import dev.sm.messenger.ui.SheetTitle
import dev.sm.messenger.ui.Sm
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.SmSheet
import dev.sm.messenger.ui.bottomLine

/** Сколько шагов пройдено: 0 — на телефоне, 1 — на узле, 2 — доставлено (макет ui-v11, `minfo`). */
internal fun infoStep(m: ChatLine): Int = when {
    m.state == MessageState.Delivered -> 2
    m.onNode -> 1
    else -> 0
}

/**
 * «Сведения о сообщении» по макету `ui-v14.html` (лист `minfo`): путь по шагам — на телефоне →
 * на узле → доставлено; по Bluetooth шага «на узле» нет. Отчётов о прочтении нет.
 */
@Composable
fun MessageInfoSheet(m: ChatLine, onDismiss: () -> Unit) = SmSheet(onDismiss) { MessageInfoContent(m) }

@Composable
internal fun MessageInfoContent(m: ChatLine) {
    val step = infoStep(m)
    val bt = m.via == MessagePath.Ble
    val failed = m.state == MessageState.Failed
    SheetTitle("Сведения о сообщении")
    Column(Modifier.padding(horizontal = 16.dp)) {
        val phone = when {
            failed -> "${clock(m.at)} · отправить не удалось"
            step > 0 -> "${clock(m.at)} · ушло"
            else -> "${clock(m.at)} · ждёт связи"
        }
        Step(SmIcons.Clock, "На телефоне", phone, done = true, last = false)
        if (!bt) Step(SmIcons.Check, "На узле", if (step >= 1) "ждёт получателя · через интернет" else "ещё нет", done = step >= 1, last = false)
        val delivered = when {
            step < 2 -> "ещё нет"
            bt -> "получатель подтвердил · по Bluetooth рядом, без узла"
            m.via == MessagePath.Node -> "получатель подтвердил · через узел"
            else -> "получатель подтвердил"
        }
        Step(SmIcons.Delivered, "Доставлено", delivered, done = step >= 2, last = true)
    }
    val path = when {
        bt -> "Bluetooth рядом — напрямую с телефона на телефон"
        m.via == MessagePath.Node || m.onNode -> "Интернет — через ваш узел"
        else -> "Bluetooth рядом или интернет через узел — что раньше"
    }
    Muted("Путь: $path. Отчётов о прочтении нет.", Modifier.padding(top = 8.dp))
}

/** Шаг пути (`.tr`): значок, название, подпись; не пройденный шаг бледнее, между шагами линия. */
@Composable
private fun Step(icon: ImageVector, title: String, sub: String, done: Boolean, last: Boolean) {
    val c = Sm.colors
    Row(
        Modifier.fillMaxWidth().alpha(if (done) 1f else DIM).let { if (last) it else it.bottomLine(c.line) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.tx, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.tx, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = c.tx2, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

private const val DIM = 0.45f
