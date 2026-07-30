package com.mist3s.nopecall.core

import android.app.Application
import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * Двухфазная инициализация (архитектура §3.1).
 *
 * `onCreate` выполняется перед ЛЮБОЙ точкой входа, включая сервис проверки, и в том числе
 * до первой разблокировки экрана. Обычная инициализация Flutter-приложения в этот момент
 * либо упадёт на недоступном хранилище, либо съест бюджет звонка — поэтому она разделена.
 *
 * Фаза 1 обязана быть дешёвой и безопасной в Direct Boot: никакого Room, никаких
 * `SharedPreferences` из CE-хранилища, никакого FlutterEngine, никаких обращений
 * к `ContentProvider`. Целевая стоимость — единицы миллисекунд.
 */
public class NopeCallApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val startedAt = System.nanoTime()

        CoreGraph.initDeviceEncrypted(createDeviceProtectedStorageContext())

        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        Log.d(TAG, "фаза 1 за ${(System.nanoTime() - startedAt) / 1_000} мкс, разблокировано=$unlocked")

        if (unlocked) initUnlocked()
    }

    /**
     * Фаза 2: Room, прогрев нормализатора номеров, синхронизация зеркала журнала,
     * планировщик, индекс контактов, слив очереди событий из Direct Boot.
     *
     * Идемпотентна: вызывается отсюда, из `BOOT_COMPLETED`, при запуске UI и из любого
     * воркера. На `ACTION_USER_UNLOCKED` полагаться нельзя — неявные рассылки из манифеста
     * ограничены с Android 8, поэтому он регистрируется в рантайме только как ускоритель.
     */
    public fun initUnlocked() {
        if (!CoreGraph.markUnlockedInitStarted()) return
        Log.d(TAG, "фаза 2 запущена")
        // TODO(этап 1): Room, снимок, зеркало журнала, контакты, слив DE-спула.
    }

    private companion object {
        const val TAG = "NopeCallApp"
    }
}

/**
 * Граф зависимостей горячего пути. Ссылки ленивые: в фазе 1 ничего тяжёлого создавать нельзя.
 */
public object CoreGraph {

    @Volatile
    private var deContext: Context? = null

    private val unlockedInitStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Контекст Device Protected Storage: единственное, что доступно до разблокировки. */
    public val deviceEncrypted: Context
        get() = deContext ?: error("CoreGraph не инициализирован: фаза 1 не выполнилась")

    public fun initDeviceEncrypted(context: Context) {
        deContext = context
    }

    internal fun markUnlockedInitStarted(): Boolean = unlockedInitStarted.compareAndSet(false, true)
}
