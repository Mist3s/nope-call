import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // Flutter Gradle Plugin применяется после Android и Kotlin.
    id("dev.flutter.flutter-gradle-plugin")
}

// Подпись релиза (ТЗ §15.4). Ключ и пароли живут вне репозитория: локально — в
// android/key.properties (он в .gitignore), в CI — раскладываются из секретов.
// Ключ один на всё время жизни проекта: его потеря означает невозможность обновить
// уже установленные копии.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// storeFile разрешается относительно android/app/ — это конвенция Flutter, и её же
// использует релизный workflow, раскладывая ключ из секрета в android/app/release.jks.
val releaseKeystore = keystoreProperties.getProperty("storeFile")?.let { file(it) }
val hasReleaseKey = releaseKeystore?.exists() == true

android {
    namespace = "com.mist3s.nopecall"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // Идентичность приложения. Менять после первого релиза нельзя: для системы это
        // будет другое приложение, а не обновление.
        applicationId = "com.mist3s.nopecall"
        minSdk = 29 // ТЗ §2: Android 10+, ROLE_CALL_SCREENING
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // PKCS12 — современный формат по умолчанию; JKS считается устаревшим.
                storeType = keystoreProperties.getProperty("storeType") ?: "PKCS12"

                // v1 (JAR signing) не нужен: он для Android 6 и ниже, у нас minSdk 29.
                // v3 включаем осознанно — только он поддерживает ротацию ключа
                // (SigningCertificateLineage). Без v3 ключ становится вечным, и его потеря
                // означает невозможность обновить уже установленные копии (ТЗ §15.4).
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Без key.properties подписываем отладочным ключом, чтобы `flutter run --release`
            // работал у любого разработчика. Релизная сборка в CI всегда идёт с настоящим
            // ключом: там key.properties создаётся из секретов, и hasReleaseKey = true.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Резервное копирование выключено: сырой журнал звонков и логи наблюдения не должны
    // уезжать в облако мимо остальных гарантий приватности (ТЗ §11.4, §7.7.4).
    // Сами правила переносятся явным экспортом в JSON.
}

dependencies {
    implementation(project(":core"))
    implementation(project(":updater"))
}

flutter {
    source = "../.."
}

// Тег -> pubspec -> CHANGELOG должны совпадать (ТЗ §15.4).
//
// Между релизами в pubspec уже стоит следующая версия, а в CHANGELOG — секция «Не выпущено»:
// это нормальное состояние, и локальную сборку оно ломать не должно. Строгая проверка
// включается флагом -PstrictVersion и применяется в релизном workflow, где пустые release
// notes — реальный дефект поставки.
val verifyReleaseVersion by tasks.registering {
    group = "verification"
    description = "Сверяет version из pubspec.yaml с секцией в CHANGELOG.md."
    doLast {
        val strict = providers.gradleProperty("strictVersion").orNull == "true"
        val repoRoot = rootProject.projectDir.parentFile
        val pubspec = File(repoRoot, "pubspec.yaml").readText()
        val version = Regex("""^version:\s*([^+\s]+)\+(\d+)""", RegexOption.MULTILINE)
            .find(pubspec)
            ?: throw GradleException("в pubspec.yaml не найдена строка version:")
        val (name, code) = version.destructured

        val changelog = File(repoRoot, "CHANGELOG.md")
        if (!changelog.exists()) throw GradleException("нет CHANGELOG.md")
        val text = changelog.readText()

        when {
            text.contains("## [$name]") ->
                logger.lifecycle("Версия $name (versionCode $code) согласована с CHANGELOG.md")

            strict -> throw GradleException(
                "в CHANGELOG.md нет секции ## [$name] — release notes для тега будут пустыми"
            )

            text.contains("## [Не выпущено]") ->
                logger.lifecycle(
                    "Версия $name (versionCode $code): в CHANGELOG секция «Не выпущено». " +
                        "Для релиза переименуй её в ## [$name] — иначе -PstrictVersion упадёт."
                )

            else -> logger.warn(
                "В CHANGELOG.md нет ни ## [$name], ни ## [Не выпущено] — release notes будут пустыми."
            )
        }
    }
}
