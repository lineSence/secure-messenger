package dev.sm.messenger.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Шаг разрешений первого запуска (UX-17, UX-18, ADR-69 п.1, макет `ui-v16.html`).
 *
 * Уведомления и «Работа в фоне» запрашиваются отдельно, отказ не блокирует остальные шаги.
 * Состояние пересчитывается при возврате в приложение: пользователь мог выдать разрешение
 * в системных настройках, а не в диалоге.
 */
@Composable
internal fun PermissionsStep(onBack: () -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    var notif by remember { mutableStateOf(notificationPerm(context)) }
    var batt by remember { mutableStateOf(batteryPerm(context)) }
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    var askedBatt by rememberSaveable { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedNotif = true
        notif = if (granted) Perm.Yes else Perm.No
    }
    val battLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        askedBatt = true
        batt = batteryPerm(context)
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notif = notificationPerm(context)
                batt = batteryPerm(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    PermissionsContent(
        notif = if (askedNotif && notif == Perm.Ask) Perm.No else notif,
        batt = if (askedBatt && batt == Perm.Ask) Perm.No else batt,
        onNotif = {
            when {
                notif == Perm.No -> openNotificationSettings(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> openNotificationSettings(context)
            }
        },
        onBatt = { requestIgnoreBattery(context, battLauncher) },
        onBack = onBack,
        done = done,
    )
}

/** Разрешение «Уведомления»: до Android 13 оно выдаётся при установке. */
private fun notificationPerm(context: Context): Perm =
    if (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    ) {
        Perm.Yes
    } else {
        Perm.Ask
    }

private fun batteryPerm(context: Context): Perm =
    if (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true) Perm.Yes else Perm.Ask

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}

/** Системное окно «без ограничений батареи»; на прошивках без него — общий список настроек. */
private fun requestIgnoreBattery(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:" + context.packageName),
    )
    runCatching { launcher.launch(request) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
