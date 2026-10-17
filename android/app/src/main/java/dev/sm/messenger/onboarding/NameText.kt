package dev.sm.messenger.onboarding

/**
 * Обрезка по code points, а не по UTF-16: `.take(n)` может разорвать суррогатную пару
 * (эмодзи), и в поле имени остаётся битый символ.
 */
internal fun takeCodePoints(value: String, max: Int): String {
    if (value.isEmpty() || max <= 0) return ""
    var end = 0
    var count = 0
    while (end < value.length && count < max) {
        end += Character.charCount(value.codePointAt(end))
        count++
    }
    return value.substring(0, end)
}

/** Первый code point строки (для аватара): эмодзи остаётся целиком, а не половиной пары. */
internal fun firstCodePoint(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return null
    return String(Character.toChars(text.codePointAt(0)))
}
