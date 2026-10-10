package dev.sm.messenger.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.sm.messenger.CoreState
import dev.sm.messenger.MainActivity
import dev.sm.messenger.R
import dev.sm.messenger.checkCore

/**
 * Служба переднего плана для связи по Bluetooth рядом (ADR-59, UX-18): пока связь включена,
 * в шторке висит уведомление «Соединение активно», и Android не останавливает процесс при
 * закрытом приложении и выключенном экране. Тип — `connectedDevice` (связь с устройствами рядом).
 *
 * Сама связь — в [BleHub]; служба только держит процесс и показывает состояние. Если система
 * убила процесс, она перезапускает службу (`START_STICKY`) — тогда связь включается снова,
 * но только при исправном ядре и выданных разрешениях; иначе служба останавливается.
 */
class BleService : Service() {
    private lateinit var hub: BleHub
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        hub = BleHub.get(application)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Соединение", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление, пока работает связь по Bluetooth рядом"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Перезапуск системой после гибели процесса: связь была включена — включаем снова.
        if (hub.mode == BleMode.Off && !(checkCore() is CoreState.Ready && hub.turnOn(fromService = true))) {
            stopSelf()
            return START_NOT_STICKY
        }
        hub.onChanged = { notifications.notify(NOTIFICATION_ID, build()) }
        return START_STICKY
    }

    /**
     * Android запрещает запуск служб переднего плана из фона и требует для типа
     * `connectedDevice` разрешение Bluetooth — в обоих случаях исключение, а не падение процесса:
     * связь работает, пока открыто приложение.
     */
    private fun enterForeground(): Boolean =
        try {
            startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException — подкласс IllegalStateException.
            false
        } catch (e: SecurityException) {
            false
        }

    private fun build(): Notification {
        // Как значок на рабочем столе: открывает уже запущенное приложение, а не второй экран.
        val launch = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Соединение активно")
            .setContentText(hub.statusLine())
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        hub.onChanged = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "link"
        const val NOTIFICATION_ID = 1
    }
}
