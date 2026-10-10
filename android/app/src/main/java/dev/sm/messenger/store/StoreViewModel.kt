package dev.sm.messenger.store

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/**
 * Один [DeliveryStore] на процесс приложения: переживает поворот экрана, поэтому два объекта
 * не пишут одни и те же файлы. Создаётся только при исправном ядре.
 */
class StoreViewModel(app: Application) : AndroidViewModel(app) {
    private var store: DeliveryStore? = null

    @Synchronized
    fun open(): DeliveryStore = store ?: DeliveryStore(getApplication()).also { store = it }

    /** Записать снимки сейчас, если хранилище открыто. */
    @Synchronized
    fun flush() {
        store?.flush()
    }
}
