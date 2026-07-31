package com.mist3s.nopecall

import com.mist3s.nopecall.bridge.BridgeScope
import com.mist3s.nopecall.bridge.JournalApi
import com.mist3s.nopecall.bridge.JournalApiImpl
import com.mist3s.nopecall.bridge.RulesApi
import com.mist3s.nopecall.bridge.RulesApiImpl
import com.mist3s.nopecall.bridge.SettingsApi
import com.mist3s.nopecall.bridge.SettingsApiImpl
import com.mist3s.nopecall.bridge.StatusApi
import com.mist3s.nopecall.bridge.StatusApiImpl
import com.mist3s.nopecall.core.NopeCallApp
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * Единственная точка, где создаётся FlutterEngine, и единственное место, где регистрируется
 * мост (архитектура §2, §3.1).
 *
 * Из-за этого модуль `:app` и играет роль bridge: Pigeon-реализации кто-то должен создать
 * и зарегистрировать в движке Flutter, а горячий путь в `:core` про Flutter знать не должен.
 *
 * Предварительный прогрев движка в `Application` запрещён: горячему пути он ничего не даёт,
 * а каждый холодный старт сервиса проверки делает дороже.
 */
class MainActivity : FlutterActivity() {

    private val bridge = BridgeScope()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Запуск интерфейса — надёжный сигнал, что экран разблокирован. На ACTION_USER_UNLOCKED
        // полагаться нельзя: неявные рассылки из манифеста ограничены с Android 8 (архитектура §3).
        (application as? NopeCallApp)?.initUnlocked()

        val messenger = flutterEngine.dartExecutor.binaryMessenger
        StatusApi.setUp(messenger, StatusApiImpl { this })
        RulesApi.setUp(messenger, RulesApiImpl(bridge))
        JournalApi.setUp(messenger, JournalApiImpl(bridge))
        SettingsApi.setUp(messenger, SettingsApiImpl(bridge))
    }
}
