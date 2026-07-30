pluginManagement {
    val flutterSdkPath =
        run {
            // local.properties — машинный файл, в репозиторий не попадает. Его создаёт
            // `flutter pub get`, поэтому любой вызов ./gradlew обязан идти ПОСЛЕ него.
            // Резервного пути тут быть не может: плагин dev.flutter.flutter-plugin-loader
            // ниже читает этот файл сам, и обойти это из нашего кода нельзя.
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
}

// Структура модулей — архитектура §2.
//
//   :app      роль bridge: MainActivity, реализации Pigeon, регистрация плагинов.
//             Единственный модуль, знающий про Flutter.
//   :core     горячий путь: CallScreeningService, снимок, Room, адаптеры.
//             НЕ знает про Flutter и Pigeon — это проверяется задачей verifyModuleBoundaries.
//   :updater  обновление приложения; сеть только здесь, доступа к данным нет.
//   :engine   чистый Kotlin/JVM: модель правил, канонизация, сопоставление.
//             Лежит в корне репозитория, а не внутри android/, потому что при варианте A
//             (KMP, ТЗ §8.5) из него будет собираться и iOS-фреймворк.
include(":app")
include(":core")
include(":updater")
include(":engine")
project(":engine").projectDir = file("../engine")
