package dev.sm.messenger.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Шифрование на диске ключом из Android Keystore (ADR-56, п. 1–3): снимки очереди и окна
 * (ключ `sm.store`, TEE) и секреты ядра (ключ `sm.keys`, [strongBox] — StrongBox, если есть).
 *
 * Ключ — AES-256-GCM, из процесса не извлекается; IV создаёт Keystore
 * (рандомизация не отключена). Связанные данные — `sm/v1/` + имя файла: снимок одного файла
 * нельзя подложить под другое имя. Формат: `[версия = 1][IV 12 байт][шифротекст + тег 16 байт]`.
 * Любая ошибка расшифровки — `null`: снимок не используется.
 */
internal class SnapshotCipher(
    private val alias: String = STORE_KEY,
    private val strongBox: Boolean = false,
    /** Explicit test key provider; production always uses Android Keystore. */
    private val keySource: (() -> SecretKey)? = null,
) {

    /**
     * Ключ создаётся один раз: проверка и создание — под общим для всех объектов замком.
     * Иначе два объекта с одним [alias] (база, очередь) могли создать ключ дважды — второй
     * заменил бы первый, и всё зашифрованное первым перестало бы открываться.
     */
    private fun key(): SecretKey {
        keySource?.let { return it() }
        synchronized(KEY_LOCK) {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            if (strongBox) {
                try {
                    return generate(inStrongBox = true)
                } catch (_: StrongBoxUnavailableException) {
                    // Нет StrongBox — ключ в TEE (ADR-56, п. 1).
                }
            }
            return generate(inStrongBox = false)
        }
    }

    private fun generate(inStrongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(inStrongBox)
                .build(),
        )
        return gen.generateKey()
    }

    private fun aad(name: String): ByteArray = "sm/v1/$name".toByteArray(Charsets.UTF_8)

    /** Шифрует снимок файла [name]. Ошибку Keystore не прячем: снимок не будет записан. */
    fun seal(name: String, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad(name))
        val body = c.doFinal(plain)
        val iv = c.iv
        check(iv.size == IV_LEN) { "Keystore выдал IV другой длины" }
        return byteArrayOf(VERSION) + iv + body
    }

    /** Расшифровывает снимок файла [name]; `null` — подмена, другой ключ или повреждение. */
    fun open(name: String, data: ByteArray): ByteArray? {
        if (data.size < 1 + IV_LEN + TAG_LEN || data[0] != VERSION) return null
        return try {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_LEN * 8, data, 1, IV_LEN),
            )
            c.updateAAD(aad(name))
            c.doFinal(data, 1 + IV_LEN, data.size - 1 - IV_LEN)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    companion object {
        /** Замок создания ключей Keystore — общий для [SnapshotCipher] и [RowIndex]. */
        internal val KEY_LOCK = Any()

        const val STORE_KEY = "sm.store"
        const val KEYS_KEY = "sm.keys"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_LEN = 12
        private const val TAG_LEN = 16
    }
}
