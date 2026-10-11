package dev.sm.messenger.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * После перезагрузки телефона или обновления приложения поднимает связь по Bluetooth, если
 * пользователь оставил её включённой (ADR-69 п.2): недоставленное уходит без открытия приложения.
 *
 * `BOOT_COMPLETED` приходит после первой разблокировки — хранилище CE и ключи Keystore уже
 * доступны. Запуск службы `connectedDevice` из этого события Android разрешает; всё остальное
 * (ядро, ключи, разрешения, включённый Bluetooth) проверяет сама [BleService] и при отказе
 * останавливается.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as android.app.Application
        if (!BleHub.get(app).wanted) return
        try {
            app.startForegroundService(Intent(app, BleService::class.java))
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException — связь поднимется при открытии приложения.
        }
    }
}
