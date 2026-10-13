package dev.sm.messenger.diagnostics

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sm.messenger.ui.BottomSpace
import dev.sm.messenger.ui.BtnKind
import dev.sm.messenger.ui.Muted
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.SettingRow
import dev.sm.messenger.ui.SheetTitle
import dev.sm.messenger.ui.Sm
import dev.sm.messenger.ui.SmHeader
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.SmSheet
import dev.sm.messenger.ui.SmSwitch
import dev.sm.messenger.ui.Tag
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object DebugTools {
    @Composable fun Row(onClick: () -> Unit) {
        SettingRow(SmIcons.DebugLog, "Debug-логи", "Ссылки, связь и доставка", trailing = { Tag("debug", Sm.colors.acc2) }, onClick = onClick)
    }

    @Composable fun Screen(onBack: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var view by remember { mutableStateOf(JournalView()) }
        val exportState = remember(context) { ViewModelProvider(context as ComponentActivity)[JournalExportState::class.java] }
        var busy by exportState.busy
        var notice by remember { mutableStateOf<String?>(null) }
        var sheet by remember { mutableStateOf<JournalSheet?>(null) }
        // Снимок фиксируется до выбора папки; новые события не меняют выгружаемый файл.
        var pending by exportState.pending
        suspend fun refresh(future: CompletableFuture<JournalView>) {
            val next = withContext(Dispatchers.IO) { awaitView(future) }
            if (next.busy) notice = "Журнал занят. Попробуйте ещё раз." else view = next
            busy = pending != null
        }
        val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val bytes = pending
            pending = null
            if (uri == null || bytes == null) {
                bytes?.fill(0)
                busy = false
            } else {
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) { writeReport(context, uri, bytes) }
                        notice = if (saved) "Файл сохранён. Журнал остался на телефоне." else "Файл не сохранён. Журнал остался на телефоне. Попробуйте ещё раз."
                    } finally {
                        bytes.fill(0)
                        busy = false
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            Diagnostics.initialize(context)
            refresh(Diagnostics.snapshot())
        }
        JournalContent(view, busy, notice, onBack, onToggle = { enabled ->
            busy = true
            notice = null
            scope.launch { refresh(Diagnostics.toggle(enabled)) }
        }, onSave = { sheet = JournalSheet.Save }, onClear = { sheet = JournalSheet.Clear })
        sheet?.let { selected ->
            SmSheet(onDismiss = { sheet = null }) {
                JournalSheetContent(selected, onDismiss = { sheet = null }) {
                    sheet = null
                    busy = true
                    notice = null
                    scope.launch {
                        if (selected == JournalSheet.Clear) {
                            refresh(Diagnostics.clear())
                            if (!view.unavailable && !view.busy) notice = "Журнал очищен"
                        } else {
                            val snap = withContext(Dispatchers.IO) { awaitView(Diagnostics.snapshot()) }
                            view = snap
                            if (snap.unavailable || snap.busy || snap.entries.isEmpty()) {
                                notice = "Не удалось подготовить файл. Журнал не изменён."
                                busy = false
                            } else {
                                pending = withContext(Dispatchers.Default) { Diagnostics.report(snap) }
                                try {
                                    save.launch("uncensored-debug-${System.currentTimeMillis()}.txt")
                                } catch (_: android.content.ActivityNotFoundException) {
                                    pending?.fill(0)
                                    pending = null
                                    notice = "Выбор папки недоступен. Журнал остался на телефоне."
                                    busy = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun awaitView(future: CompletableFuture<JournalView>): JournalView =
    try {
        future.get(10, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        JournalView(ready = true, busy = true)
    } catch (_: ExecutionException) {
        JournalView(ready = true, unavailable = true)
    }

/** Успех только после close; никаких временных открытых файлов внутри приложения. */
private fun writeReport(context: Context, uri: Uri, bytes: ByteArray): Boolean =
    JournalExport.write(bytes) { context.contentResolver.openOutputStream(uri, "wt") }

/** Снимок переживает поворот при открытом выборе документа, но никогда не кладётся в Bundle. */
internal class JournalExportState : ViewModel() {
    val pending = mutableStateOf<ByteArray?>(null)
    val busy = mutableStateOf(false)
    override fun onCleared() {
        pending.value?.fill(0)
        pending.value = null
    }
}

internal enum class JournalSheet { Save, Clear }

@Composable
internal fun JournalContent(
    view: JournalView,
    busy: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val c = Sm.colors
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        SmHeader("Debug-логи", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            notice?.let { Muted(it) }
            Muted("Журнал для разбора ошибок добавления контактов и обмена сообщениями.")
            SettingRow(SmIcons.DebugLog, "Собирать события", if (view.enabled) "Включено · только на этом телефоне" else "Выключено · новые события не пишутся", chevron = false,
                trailing = { SmSwitch(view.enabled, if (!busy && view.ready && !view.unavailable) onToggle else null) }, onClick = null)
            LogCard {
                Text("Без содержимого переписки", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.tx)
                Muted("Не записываем имена, ключи, адреса узла, пропуски, ссылки и коды. Только этапы операций, время и коды ошибок.", inset = 0.dp)
            }
            SectionTitle("На этом телефоне")
            when {
                !view.ready -> Muted("Открываем журнал…")
                view.unavailable -> Muted("Журнал не открыт: ошибка хранения или ключа. Работа приложения не меняется; выгрузка недоступна.")
                view.entries.isEmpty() -> Muted("Журнал пуст. Включите сбор и повторите ошибку.")
                else -> {
                    Muted("Событий: ${view.entries.size} · потеряно: ${view.lost}\nНе больше 2 000 событий / 1 МиБ / 48 часов.")
                    LogCard {
                        Text(view.entries.takeLast(4).joinToString("\n") { "+${it.elapsed / 1000}s ${it.event.name} ${it.result.name}" },
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = c.tx)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LogButton(if (busy) "Подождите…" else "Сохранить файл", onSave, Modifier.fillMaxWidth(), enabled = !busy && view.ready && !view.unavailable && view.entries.isNotEmpty())
                LogButton("Очистить журнал", onClear, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, enabled = !busy && view.ready && !view.unavailable && view.entries.isNotEmpty())
            }
            Muted("Сбор переживает перезапуск. Журнал хранится зашифрованным внутри приложения; на сервер не отправляется.")
            BottomSpace()
        }
    }
}

@Composable
internal fun JournalSheetContent(sheet: JournalSheet, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    SheetTitle(if (sheet == JournalSheet.Save) "Сохранить debug-логи?" else "Очистить журнал?")
    if (sheet == JournalSheet.Save) {
        Muted("Файл содержит время и технические события. После сохранения он не зашифрован и хранится вне приложения. Отправляйте его только тому, кому доверяете.")
        Muted("Далее откроется выбор папки Android. Автоматической отправки нет.")
    } else {
        Muted("События исчезнут с этого телефона. Уже сохранённые файлы останутся. Настройка сбора не изменится.")
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LogButton(if (sheet == JournalSheet.Save) "Выбрать место" else "Очистить", onConfirm, Modifier.fillMaxWidth())
        LogButton("Отмена", onDismiss, Modifier.fillMaxWidth(), kind = BtnKind.Tonal)
    }
}

@Composable
private fun LogCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        .clip(RoundedCornerShape(12.dp)).background(Sm.colors.hov).padding(14.dp), content = content)
}

@Composable
private fun LogButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.Primary, enabled: Boolean = true) {
    val secondary = kind == BtnKind.Tonal
    Box(modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(14.dp))
        .background(if (secondary) Sm.colors.hov else Sm.colors.acc).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (secondary) Sm.colors.acc2 else Sm.colors.acct, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
