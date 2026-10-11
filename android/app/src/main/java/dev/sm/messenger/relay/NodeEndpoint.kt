package dev.sm.messenger.relay

/**
 * Как достучаться до узла по HTTPS (ADR-84, ADR-90). Источник — подписанная запись узла
 * (ADR-81, п.10 плана 0.0.2-alpha); в APK и в репозитории адресов нет (ADR-86).
 *
 * [domains] — 2–3 домена с тем же адресом (ADR-86), у каждого свой ключ TLS и свои пины
 * (ADR-135); [path] — секретный путь, который nginx проксирует на узел, остальное — сайт (ADR-66).
 */
class NodeEndpoint(
    val domains: List<Domain>,
    val port: Int,
    val path: String,
) {
    init {
        require(domains.isNotEmpty() && domains.size <= MAX_DOMAINS) { "домены узла" }
        require(port in 1..MAX_PORT) { "порт" }
        require(path.length in 2..MAX_PATH && path.startsWith('/') && path.all(::isPathChar)) { "путь" }
    }

    /**
     * Домен [name] и SHA-256 ключа (SPKI) его сертификата [pins]: основной и запасной
     * (ADR-72, `certbot --reuse-key`; свой ключ на домен — ADR-135).
     */
    class Domain(val name: String, val pins: List<ByteArray>) {
        init {
            require(isHostName(name)) { "имя домена" }
            require(pins.isNotEmpty() && pins.size <= MAX_PINS && pins.all { it.size == PIN_LEN }) { "пины" }
        }
    }

    private companion object {
        const val MAX_DOMAINS = 8
        const val MAX_PINS = 4
        const val MAX_PORT = 65535
        const val MAX_PATH = 128
        const val PIN_LEN = 32
        const val MAX_HOST = 253

        /** Имя DNS: метки из `a-z0-9-` через точку; без IP-адресов, портов и пользователя. */
        fun isHostName(s: String): Boolean =
            s.length in 1..MAX_HOST && s.contains('.') && s.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            } && !s.split('.').last().all { it in '0'..'9' }

        /** Путь — только безопасные символы URL, без `?`, `#`, `%` и `..`. */
        fun isPathChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '/' || c == '-' || c == '_'
    }
}
