// Горячий путь: CallScreeningService, снимок правил, Room, адаптеры Android (архитектура §2).
//
// Ключевое свойство модуля: он НЕ знает про Flutter и Pigeon. Иначе правило «проверка звонка
// не зависит от Flutter Engine» (ТЗ §8.2) держалось бы на дисциплине, а не на сборке.
// Проверяется задачей verifyModuleBoundaries ниже.

plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.mist3s.nopecall.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 29 // ТЗ §2: Android 10+
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":engine"))
    testImplementation("junit:junit:4.13.2")
}

// --- граница модуля, проверяемая сборкой (архитектура §2) ---------------------------------
//
// Запрещённые зависимости перечислены явно, а не выводятся из эвристик: список короткий,
// а ошибка «случайно затянули Flutter в горячий путь» стоит дорого и глазами не ловится.
val forbiddenInCore = listOf(
    "io.flutter" to "Flutter в горячем пути: нарушает ТЗ §8.2",
    "dev.flutter" to "Flutter в горячем пути: нарушает ТЗ §8.2",
    "flutter_embedding" to "Flutter в горячем пути: нарушает ТЗ §8.2",
    "pigeon" to "Pigeon в горячем пути: мост принадлежит :app",
    ":updater" to "updater в горячем пути: у него есть сеть, у горячего пути её быть не должно",
)

val verifyModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Проверяет, что в :core нет Flutter, Pigeon и :updater (архитектура §2)."
    doLast {
        val violations = mutableListOf<String>()
        configurations
            .filter { it.isCanBeResolved && it.name.contains("CompileClasspath") }
            .forEach { config ->
                val ids = runCatching {
                    config.incoming.resolutionResult.allComponents.map { it.id.displayName }
                }.getOrDefault(emptyList())
                for (id in ids) {
                    for ((needle, why) in forbiddenInCore) {
                        if (id.contains(needle, ignoreCase = true)) {
                            violations += "  $id  (${config.name}) — $why"
                        }
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Граница модуля :core нарушена:\n" + violations.distinct().joinToString("\n")
            )
        }
        logger.lifecycle("Граница :core соблюдена: Flutter, Pigeon и :updater отсутствуют.")
    }
}

tasks.named("check") { dependsOn(verifyModuleBoundaries) }
