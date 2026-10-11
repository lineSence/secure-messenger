package dev.sm.messenger.keys

import android.content.Context
import android.util.AtomicFile
import dev.sm.messenger.store.SnapshotCipher
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import uniffi.sm_ffi.DeviceIdentity
import uniffi.sm_ffi.KeyFfiException
import uniffi.sm_ffi.PlatformKeyStorage
import uniffi.sm_ffi.PlatformKeyWrapper

/**
 * Секреты ядра шифруются ключом Keystore `sm.keys` (ADR-56): AES-256-GCM, по возможности в
 * StrongBox. Связанные данные — `sm/v1/key/` + метка. Открытый секрет — только на время вызова;
 * после шифрования массив обнуляется.
 */
internal class KeystoreKeyWrapper : PlatformKeyWrapper {
    private val cipher = SnapshotCipher(SnapshotCipher.KEYS_KEY, strongBox = true)

    override fun wrap(label: String, secret: ByteArray): ByteArray =
        try {
            cipher.seal("key/$label", secret)
        } catch (_: GeneralSecurityException) {
            throw KeyFfiException.Wrap()
        } catch (_: IllegalStateException) {
            throw KeyFfiException.Wrap()
        } finally {
            secret.fill(0)
        }

    override fun unwrap(label: String, wrapped: ByteArray): ByteArray =
        cipher.open("key/$label", wrapped) ?: throw KeyFfiException.Wrap()
}

/**
 * Записи ключей — файлы `noBackupFilesDir/sm/keys/<метка>` (хранилище CE, без резервных копий),
 * запись через [AtomicFile]. Метку проверяет ядро (`[a-z0-9._-]`, без `..`), поэтому она
 * безопасна как имя файла.
 */
internal class KeyFiles(context: Context) : PlatformKeyStorage {
    private val dir = File(context.noBackupFilesDir, "sm/keys").apply { mkdirs() }

    /** Запись длиннее [MAX_RECORD] — отказ до чтения целиком (подменённый файл не съест память). */
    override fun load(label: String): ByteArray? =
        try {
            AtomicFile(File(dir, label)).openRead().use { input ->
                // readNBytes — API 33, minSdk 31: читаем сами, не больше MAX_RECORD + 1 байт.
                val b = ByteArray(MAX_RECORD + 1)
                var n = 0
                while (n < b.size) {
                    val r = input.read(b, n, b.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n > MAX_RECORD) throw KeyFfiException.Storage()
                b.copyOf(n)
            }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IOException) {
            throw KeyFfiException.Storage()
        }

    override fun store(label: String, record: ByteArray) {
        val f = AtomicFile(File(dir, label))
        val out = try {
            f.startWrite()
        } catch (_: IOException) {
            throw KeyFfiException.Storage()
        }
        try {
            out.write(record)
            f.finishWrite(out)
        } catch (_: IOException) {
            f.failWrite(out)
            throw KeyFfiException.Storage()
        }
    }

    private companion object {
        /** Запись ядра — не больше 8 КиБ обёрнутого секрета плюс метка и заголовок (`keystore.rs`). */
        const val MAX_RECORD = 16 * 1024
    }
}

/** Итог загрузки ключа личности при запуске. */
sealed interface KeysState {
    /** Первый запуск: ключа нет — экран создания (UX-17). */
    data object Missing : KeysState

    data class Ready(val identity: DeviceIdentity) : KeysState

    /**
     * Ключ есть, но не открывается (ключ Keystore удалён, файл подменён или повреждён).
     * Новый ключ молча не создаём: контакты увидели бы смену ключа.
     */
    data object Broken : KeysState
}

/** Ключ личности устройства: загрузка и создание через ядро. */
class DeviceKeys(context: Context) {
    private val wrapper = KeystoreKeyWrapper()
    private val files = KeyFiles(context.applicationContext)

    fun load(): KeysState =
        try {
            KeysState.Ready(DeviceIdentity.load(wrapper, files))
        } catch (_: KeyFfiException.NotFound) {
            KeysState.Missing
        } catch (_: KeyFfiException) {
            KeysState.Broken
        }

    /**
     * Создаёт ключ (долго: Keystore и libsignal) — вызывать не из главного потока.
     * Ключ уже есть — загружаем его, второй не создаётся.
     */
    fun create(): KeysState =
        try {
            KeysState.Ready(DeviceIdentity.create(wrapper, files))
        } catch (_: KeyFfiException.Exists) {
            load()
        } catch (_: KeyFfiException) {
            KeysState.Broken
        }
}
