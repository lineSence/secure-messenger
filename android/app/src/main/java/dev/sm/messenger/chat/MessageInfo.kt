package dev.sm.messenger.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sm.messenger.ui.SmIcons

/** Сколько шагов пройдено: 0 — на телефоне, 1 — на узле, 2 — доставлено (макет ui-v11, `minfo`). */
internal fun infoStep(m: ChatLine): Int = when {
    m.state == MessageState.Delivered -> 2
    m.onNode -> 1
    else -> 0
}

/**
 * «Сведения о сообщении» по макету `ui-v11.html` (лист `minfo`): путь по шагам — на телефоне →
 * на узле → доставлено; по Bluetooth шага «на узле» нет. Отчётов о прочтении нет.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoSheet(m: ChatLine, onDismiss: () -> Unit) {
    val step = infoStep(m)
    val bt = m.via == MessagePath.Ble
    val failed = m.state == MessageState.Failed
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Сведения о сообщении", style = MaterialTheme.typography.titleLarge)
            val phone = when {
                failed -> "${clock(m.at)} · отправить не удалось"
                step > 0 -> "${clock(m.at)} · ушло"
                else -> "${clock(m.at)} · ждёт связи"
            }
            Step(SmIcons.Clock, "На телефоне", phone, done = true)
            if (!bt) Step(SmIcons.Check, "На узле", if (step >= 1) "ждёт получателя · через интернет" else "ещё нет", done = step >= 1)
            val delivered = when {
                step < 2 -> "ещё нет"
                bt -> "получатель подтвердил · по Bluetooth рядом, без узла"
                m.via == MessagePath.Node -> "получатель подтвердил · через узел"
                else -> "получатель подтвердил"
            }
            Step(SmIcons.Delivered, "Доставлено", delivered, done = step >= 2)
            val path = when {
                bt -> "Bluetooth рядом — напрямую с телефона на телефон"
                m.via == MessagePath.Node || m.onNode -> "Интернет — через ваш узел"
                else -> "Bluetooth рядом или интернет через узел — что раньше"
            }
            Text("Путь: $path. Отчётов о прочтении нет.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, sub: String, done: Boolean) {
    Row(Modifier.alpha(if (done) 1f else DIM), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val DIM = 0.45f
