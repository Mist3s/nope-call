package com.mist3s.nopecall

import io.flutter.embedding.android.FlutterActivity

/**
 * Единственная точка, где создаётся FlutterEngine (архитектура §3.1).
 *
 * Предварительный прогрев движка в Application запрещён: горячему пути он ничего не даёт,
 * а каждый холодный старт сервиса проверки делает дороже.
 *
 * Здесь же будут зарегистрированы реализации Pigeon — поэтому модуль :app и играет роль
 * bridge, а горячий путь живёт в :core, который про Flutter не знает.
 */
class MainActivity : FlutterActivity()
