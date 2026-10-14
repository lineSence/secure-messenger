package dev.sm.messenger.chat

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.sm.messenger.onboarding.Profile
import dev.sm.messenger.ui.*

internal fun notificationAccess(runtime: Boolean, app: Boolean, channel: Boolean): Boolean = runtime && app && channel

@Composable
internal fun rememberNotificationAccess(revision: Int = 0): State<Boolean> {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val enabled = remember { mutableStateOf(false) }
    DisposableEffect(owner, context, revision) {
        fun refresh() {
            val manager = context.getSystemService(NotificationManager::class.java)
            enabled.value = notificationAccess(
                Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                manager?.areNotificationsEnabled() == true,
                manager?.getNotificationChannel("messages")?.importance != NotificationManager.IMPORTANCE_NONE,
            )
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner?.lifecycle?.addObserver(observer)
        refresh()
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return enabled
}

@Composable
internal fun NotificationOptIn() {
    val context = LocalContext.current
    val profile = remember(context) { Profile(context) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    var asked by remember { mutableStateOf(profile.notificationAsked) }
    var requestRevision by remember { mutableStateOf(0) }
    val enabled by rememberNotificationAccess(requestRevision)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Только результат; не открываем Settings автоматически при отказе.
        requestRevision++
    }
    LaunchedEffect(enabled, requestRevision) {
        if (first) { first = false; if (!enabled && !asked) sheet = true }
    }
    fun later() { sheet = false; profile.notificationAsked = true; asked = true }
    if (!enabled) NotificationNotice { sheet = true }
    if (sheet) SmSheet(onDismiss = ::later) {
        SheetTitle("Сообщать о новых сообщениях?")
        Muted("После этого объяснения приложение запросит системное разрешение. Отказ не мешает переписке. Работа в фоне и ограничения батареи — отдельная настройка.")
        SmButton("Разрешить уведомления", {
            val wasAsked = asked
            later()
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !wasAsked) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        })
        SmButton("Позже", ::later, kind = BtnKind.Tonal)
    }
}

@Composable
internal fun NotificationNotice(onEnable: () -> Unit) {
    Banner("Уведомления выключены: о новых сообщениях в фоне телефон не сообщит.", Sm.colors.warn, SmIcons.Bell) {
        SmButton("Настроить", onEnable, kind = BtnKind.Tonal, small = true)
    }
}
