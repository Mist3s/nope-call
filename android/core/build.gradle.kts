val roomVersion = "2.7.2"

// Горячий путь: CallScreeningService, снимок правил, Room, адаптеры Android (архитектура §2).
//
// Ключевое свойство модуля: он НЕ знает про Flutter и Pigeon. Иначе правило «проверка звонка
// не зависит от Flutter Engine» (ТЗ §8.2) держалось бы на дисциплине, а не на сборке.
// Проверяется задачей verifyModuleBoundaries ниже.

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.google.devtools.ksp")
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

    // Схемы Room подкладываются в ресурсы тестов: MigrationTestHelper ищет их именно в assets,
    // и без этой строки тест миграции падает с FileNotFoundException, а не с расхождением схемы.
    sourceSets {
        getByName("test") { assets.srcDirs("$projectDir/schemas") }
    }
}

// Схемы Room экспортируются в репозиторий: без них нельзя написать тест миграции,
// а терять созданные пользователем правила при обновлении нельзя (архитектура §5.4).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // api, а не implementation: типы движка возвращаются из публичного API :core
    // (SaveResult, PatternCheck, RuleTarget), значит они часть его контракта.
    // Room, напротив, остаётся implementation — :app не должен знать про Room.
    api(project(":engine"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // NotificationCompat: уведомления о заблокированных звонках (ТЗ §9.6)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // kotlin("test"), а не голый JUnit4: у JUnit4 сообщение в assertEquals идёт ПЕРВЫМ
    // аргументом, а в :engine используется kotlin.test с сообщением последним. Разный порядок
    // в разных модулях — готовая ловушка при правках.
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
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
