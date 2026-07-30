// Движок правил: чистый Kotlin/JVM (архитектура §6.1).
//
// Здесь СОЗНАТЕЛЬНО нет ни Android SDK, ни Room, ни сетевых библиотек, ни libphonenumber.
// Это не аккуратность, а техническая гарантия двух требований ТЗ:
//   * «проверка звонка не делает сетевых запросов» (ТЗ §10) — сети нет в classpath;
//   * движок переносим на iOS и тестируется на JVM (ТЗ §8.5, §17).
// Нормализация номера объявляется здесь интерфейсом, а реализуется в :core — стоимость
// загрузки метаданных региона не укладывается в бюджет звонка, а Android-порт требует Context
// (архитектура §6.3).
//
// Список зависимостей проверяется задачей verifyModuleBoundaries в :core.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
