package dev.sm.messenger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sm.messenger.ui.*

internal enum class RouteState { Loading, ReadError, Missing }
internal fun routeState(loading: Boolean, failed: Boolean): RouteState = when {
    loading -> RouteState.Loading
    failed -> RouteState.ReadError
    else -> RouteState.Missing
}
@Composable
internal fun RouteUnavailableContent(state: RouteState, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SmHeader("Чат", onBack = onBack)
        if (state == RouteState.Loading) Muted("Открываем контакт…") else {
            Banner(if (state == RouteState.ReadError) "Не удалось открыть контакт. Ошибка чтения не означает, что он удалён. Переписка и ключи не сбрасывались." else "Контакт недоступен: его нет в загруженном списке. Переписка и ключи не сбрасывались.", Sm.colors.bad, SmIcons.Alert) {}
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmButton("Повторить", onRetry, Modifier.fillMaxWidth())
                SmButton("К чатам", onBack, Modifier.fillMaxWidth(), kind = BtnKind.Tonal)
            }
        }
    }
}
