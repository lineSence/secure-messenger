package dev.sm.messenger.relay

import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Совпадает ли SHA-256 ключа (SPKI, `PublicKey.encoded`) с одним из пинов узла (ADR-72). */
internal fun spkiPinned(key: PublicKey, pins: List<ByteArray>): Boolean {
    val hash = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    return pins.any { MessageDigest.isEqual(it, hash) }
}

/**
 * Системная проверка цепочки (Let's Encrypt, хранилище CA Android) и пин ключа листа (ADR-72).
 * Пин — в коде, а не в `network_security_config`: там домен узла оказался бы в APK (ADR-90).
 * Имя хоста проверяет `HttpsURLConnection` (системный `HostnameVerifier`).
 */
internal class PinningTrustManager(
    private val system: X509TrustManager,
    private val pins: List<ByteArray>,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("клиентские сертификаты не принимаем")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        system.checkServerTrusted(chain, authType)
        val leaf = chain?.firstOrNull() ?: throw CertificateException("пустая цепочка")
        if (!spkiPinned(leaf.publicKey, pins)) throw CertificateException("ключ узла не совпал с пином")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        /** Системный `X509TrustManager` (хранилище CA Android). */
        fun system(): X509TrustManager {
            val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            f.init(null as KeyStore?)
            return f.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        /**
         * Новая фабрика сокетов с пином. Каждая фабрика — свой пул соединений `HttpsURLConnection`:
         * так плановая замена канала (ADR-93) поднимает новое TLS-соединение.
         */
        fun socketFactory(pins: List<ByteArray>): SSLSocketFactory {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(PinningTrustManager(system(), pins)), null)
            return ctx.socketFactory
        }
    }
}
