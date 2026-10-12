package dev.sm.messenger.relay

import java.security.SecureRandom
import java.util.Locale
import org.signal.argon2.Argon2
import org.signal.argon2.Argon2Exception
import org.signal.argon2.MemoryCost
import org.signal.argon2.Type
import org.signal.argon2.Version

/**
 * Код к ссылке-приглашению (ADR-133, ADR-142): [WORD_COUNT] слов из [InviteWords]. Приложение
 * создаёт код само, показывает один раз; без кода ссылку не открыть. Ввод прощает регистр, «ё»,
 * пробелы, точки, запятые, «·» и дефисы между словами.
 */
object InviteCode {
    /** Слов в коде: 4 из 2048 — 44 бита. */
    const val WORD_COUNT = 4

    /** Новый код: слова разные, выбор — `SecureRandom` (`nextInt` без смещения). */
    fun generate(random: SecureRandom): List<String> {
        val words = InviteWords.ALL
        val out = LinkedHashSet<String>()
        while (out.size < WORD_COUNT) out.add(words[random.nextInt(words.size)])
        return out.toList()
    }

    /** Слова кода для показа. */
    fun display(words: List<String>): String = words.joinToString(" · ")

    /**
     * Разобрать введённый код. `null` — слов не [WORD_COUNT] или есть слово не из списка (тот же
     * отказ, что и при неверном коде: какое слово не так, не подсказываем).
     */
    fun parse(input: String): List<String>? {
        val words = input.lowercase(Locale.ROOT).replace('ё', 'е').split(SEPARATORS).filter { it.isNotEmpty() }
        if (words.size != WORD_COUNT || words.any { it !in KNOWN }) return null
        return words
    }

    /** Пароль для Argon2id: слова через один пробел, UTF-8. */
    fun password(words: List<String>): ByteArray = words.joinToString(" ").toByteArray(Charsets.UTF_8)

    private val KNOWN = InviteWords.ALL.toHashSet()
    private val SEPARATORS = Regex("[\\s,.;·\\-–—]+")
}

/**
 * Ключ кода: Argon2id (версия 1.3) из `org.signal:argon2` (ADR-142) — 64 МиБ, 3 прохода, 1 поток,
 * 32 байта; соль — из ссылки. Дальше ключ уходит в ядро: HKDF-Expand и AES-256-GCM
 * (`sm_signal::invite`). Считается секунды на телефоне, поэтому — вне главного потока.
 */
object InviteKdf {
    /** Ключ для [words] и [salt] (16 байт из ссылки); `null` — библиотека отказала (память). */
    fun key(words: List<String>, salt: ByteArray): ByteArray? =
        try {
            Argon2.Builder(Version.V13)
                .type(Type.Argon2id)
                .memoryCost(MemoryCost.MiB(MEMORY_MIB))
                .parallelism(1)
                .iterations(PASSES)
                .hashLength(KEY_LEN)
                .build()
                .hash(InviteCode.password(words), salt)
                .hash
        } catch (_: Argon2Exception) {
            null
        }

    private const val MEMORY_MIB = 64
    private const val PASSES = 3
    private const val KEY_LEN = 32
}
