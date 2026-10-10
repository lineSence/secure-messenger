package dev.sm.messenger.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.sm.messenger.MainActivity
import dev.sm.messenger.R
import dev.sm.messenger.contacts.Contact

/**
 * Уведомления о новых сообщениях. На экране блокировки — только «Новое сообщение» без имени и
 * текста (`VISIBILITY_PRIVATE` с публичной версией). Нет разрешения на уведомления — Android их
 * просто не показывает.
 */
internal class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Новые сообщения от контактов"
            },
        )
    }

    fun message(c: Contact, text: String) {
        val m = manager ?: return
        val launch = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val open = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Новое сообщение")
            .build()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(c.name)
            .setContentText(text.take(PREVIEW))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        try {
            m.notify(TAG, c.rowId.toInt(), n)
        } catch (_: SecurityException) {
            // Разрешение отозвано между проверкой и показом — без уведомления.
        }
    }

    fun clear(c: Contact) {
        manager?.cancel(TAG, c.rowId.toInt())
    }

    private companion object {
        const val CHANNEL = "messages"
        const val TAG = "chat"
        const val PREVIEW = 200
    }
}
