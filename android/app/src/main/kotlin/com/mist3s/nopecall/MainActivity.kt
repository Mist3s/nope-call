package com.mist3s.nopecall

import com.mist3s.nopecall.bridge.BridgeScope
import com.mist3s.nopecall.bridge.DiagnosticsApi
import com.mist3s.nopecall.bridge.DiagnosticsApiImpl
import com.mist3s.nopecall.bridge.JournalApi
import com.mist3s.nopecall.bridge.JournalApiImpl
import com.mist3s.nopecall.bridge.ObservationApi
import com.mist3s.nopecall.bridge.ObservationApiImpl
import com.mist3s.nopecall.bridge.RulesApi
import com.mist3s.nopecall.bridge.RulesApiImpl
import com.mist3s.nopecall.bridge.SettingsApi
import com.mist3s.nopecall.bridge.SettingsApiImpl
import com.mist3s.nopecall.bridge.StatusApi
import com.mist3s.nopecall.bridge.StatusApiImpl
import com.mist3s.nopecall.bridge.UpdaterApi
import com.mist3s.nopecall.bridge.UpdaterApiImpl
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

    /**
     * Реализация правил держится полем: импорт открывает системный выбор файла, и ответ
     * Pigeon завершается уже в `onActivityResult` — обратный вызов живёт внутри неё.
     */
    private val rules by lazy { RulesApiImpl(bridge) { this } }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Запуск интерфейса — надёжный сигнал, что экран разблокирован. На ACTION_USER_UNLOCKED
        // полагаться нельзя: неявные рассылки из манифеста ограничены с Android 8 (архитектура §3).
        (application as? NopeCallApp)?.initUnlocked()

        val messenger = flutterEngine.dartExecutor.binaryMessenger
        StatusApi.setUp(messenger, StatusApiImpl { this })
        RulesApi.setUp(messenger, rules)
        JournalApi.setUp(messenger, JournalApiImpl(bridge) { this })
        SettingsApi.setUp(messenger, SettingsApiImpl(bridge))
        ObservationApi.setUp(messenger, ObservationApiImpl(bridge) { this })
        DiagnosticsApi.setUp(messenger, DiagnosticsApiImpl(bridge) { this })
        UpdaterApi.setUp(messenger, UpdaterApiImpl(bridge) { this })
    }

    /**
     * Разрешения выданы — надо воспользоваться ими сразу.
     *
     * Без этого индекс контактов и зеркало журнала наполнились бы только при следующем запуске
     * процесса: и то и другое строится в фазе 2, а она к этому моменту уже прошла. Пользователь
     * выдал бы доступ и не увидел никакой разницы — то есть решил бы, что не работает.
     */
    /**
     * Результат выбора файла при импорте правил.
     *
     * Ответ Pigeon завершается именно здесь: обратный вызов ждёт с момента открытия диалога,
     * и отмена — тоже ответ. Иначе интерфейс остался бы в ожидании навсегда.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RulesApiImpl.IMPORT_REQUEST_CODE) {
            rules.onImportPicked(if (resultCode == RESULT_OK) data?.data else null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != StatusApiImpl.PERMISSIONS_REQUEST_CODE) return
        if (grantResults.none { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) return
        (application as? NopeCallApp)?.refreshAfterPermissions()
    }
}
