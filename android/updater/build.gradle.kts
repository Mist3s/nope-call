// Обновление приложения из релизов GitHub (ТЗ §15.5).
//
// Единственный модуль с сетью. У него нет зависимости на :core, то есть нет доступа
// к правилам, журналу и логам наблюдения — так гарантия «журнал не уходит в сеть»
// становится свойством сборки, а не обещанием в документации (архитектура §2, §8.5).
// Настройки («автопроверка», «предварительные версии») передаются извне интерфейсом,
// а не читаются из Room.

plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.mist3s.nopecall.updater"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Обратная граница: у апдейтера не должно появиться доступа к данным приложения.
val verifyUpdaterBoundaries by tasks.registering {
    group = "verification"
    description = "Проверяет, что :updater не зависит от :core и от Room (архитектура §2)."
    doLast {
        val forbidden = listOf(":core", "androidx.room")
        val violations = configurations
            .filter { it.isCanBeResolved && it.name.contains("CompileClasspath") }
            .flatMap { config ->
                runCatching {
                    config.incoming.resolutionResult.allComponents.map { it.id.displayName }
                }.getOrDefault(emptyList())
            }
            .filter { id -> forbidden.any { id.contains(it, ignoreCase = true) } }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "У :updater появился доступ к данным приложения:\n" +
                    violations.distinct().joinToString("\n") { "  $it" }
            )
        }
        logger.lifecycle("Граница :updater соблюдена: доступа к :core и Room нет.")
    }
}

tasks.named("check") { dependsOn(verifyUpdaterBoundaries) }
