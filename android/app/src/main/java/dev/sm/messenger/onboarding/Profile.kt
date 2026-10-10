package dev.sm.messenger.onboarding

import android.content.Context

/**
 * Имя, которое видят контакты (UX-17). Не секрет, но и не для чужих глаз: `SharedPreferences`
 * в хранилище CE, резервное копирование приложения выключено.
 */
class Profile(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var name: String?
        get() = prefs.getString(NAME, null)
        set(value) {
            prefs.edit().putString(NAME, value).apply()
        }

    /** Первый запуск пройден (разрешения можно было пропустить). */
    var onboarded: Boolean
        get() = prefs.getBoolean(ONBOARDED, false)
        set(value) {
            prefs.edit().putBoolean(ONBOARDED, value).apply()
        }

    companion object {
        const val MAX_NAME = 32
        private const val PREFS = "sm.profile"
        private const val NAME = "name"
        private const val ONBOARDED = "onboarded"
    }
}

/** Открытый ключ личности для показа: 16 байт после байта типа, группы по 4 знака hex (UX-17). */
fun identityText(publicKey: ByteArray): String =
    publicKey.drop(1).take(SHOWN_BYTES)
        .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
        .chunked(GROUP).chunked(GROUPS_PER_LINE)
        .joinToString("\n") { it.joinToString(" ") }

private const val SHOWN_BYTES = 16
private const val BYTE_MASK = 0xFF
private const val GROUP = 4
private const val GROUPS_PER_LINE = 4
