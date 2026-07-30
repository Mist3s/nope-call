package com.mist3s.nopecall.core.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mist3s.nopecall.core.NopeCallApp

/**
 * `LOCKED_BOOT_COMPLETED` — приходит до разблокировки экрана (архитектура §3).
 *
 * Смысл приёмника не в том, чтобы что-то сделать, а в том, чтобы процесс поднялся и
 * выполнил фазу 1: тогда снимок правил в Device Protected Storage уже прочитан и прогрет
 * к моменту первого звонка. Здесь нельзя трогать Room — хранилище ещё недоступно.
 */
internal class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "получено ${intent.action}: фаза 1 выполнена, ждём разблокировки")
    }

    private companion object {
        const val TAG = "NopeCallBoot"
    }
}

/**
 * `BOOT_COMPLETED` и `MY_PACKAGE_REPLACED` — приходят после разблокировки.
 *
 * Оба инициируют фазу 2. `MY_PACKAGE_REPLACED` дополнительно требует приоритетной пересборки
 * снимка: сразу после обновления приложения формат снимка может оказаться прежним, и без
 * пересборки все звонки пойдут fail-open до её завершения (архитектура §5.2).
 */
internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "получено ${intent.action}")
        (context.applicationContext as? NopeCallApp)?.initUnlocked()
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // TODO(этап 1): приоритетная пересборка снимка + перепроверка роли (архитектура §5.2, §10).
        }
    }

    private companion object {
        const val TAG = "NopeCallBoot"
    }
}
