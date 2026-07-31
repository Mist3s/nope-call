package com.mist3s.nopecall.updater

/**
 * Что апдейтер знает об установленной копии и об устройстве (ТЗ §15.5).
 *
 * Данные, а не обращения к `PackageManager` из логики проверки, — по той же причине, что
 * и интерфейсы вокруг сети: решение «предлагать обновление или нет» обязано проверяться
 * на голой JVM. Читает эти поля [AndroidInstalledApp].
 *
 * @property versionName версия установленной копии; основа сравнения (ТЗ §15.2)
 * @property versionCode `versionCode` установленной копии, уже **со** смещением по ABI,
 *   если сборка собиралась с `--split-per-abi`
 * @property signingCertsSha256 отпечатки сертификатов подписи установленной копии
 *   в нижнем регистре без разделителей. Список, а не одно значение: при подписи v3 у ключа
 *   есть история ротации, и релиз мог быть подписан любым звеном этой цепочки
 *   (`enableV3Signing` включён, см. `app/build.gradle.kts`). Пустой список означает
 *   «прочитать не удалось» — тогда установка не начинается.
 * @property deviceSdkInt `Build.VERSION.SDK_INT`
 * @property supportedAbis `Build.SUPPORTED_ABIS` в порядке убывания предпочтения
 */
public data class InstalledApp(
    val versionName: String,
    val versionCode: Long,
    val signingCertsSha256: List<String>,
    val deviceSdkInt: Int,
    val supportedAbis: List<String>,
) {
    /**
     * `versionCode` без смещения по ABI (ТЗ §15.2).
     *
     * При `--split-per-abi` Flutter добавляет к `versionCode` `1000 × индекс ABI`, поэтому
     * установленная копия имеет, например, `2045` там, где в `latest.json` записано `build: 45`.
     * Остаток от деления на 1000 возвращает базовое значение — и это не догадка, а та же схема,
     * которой пользуется сам Flutter: она молча предполагает, что номер сборки меньше 1000.
     *
     * Отклонённая альтернатива — вычитать смещение по ABI устройства: неизвестно, какой именно
     * файл был установлен (пользователь мог поставить `universal` вручную), а ошибка здесь
     * означала бы либо вечное «обновлений нет», либо предложение того же самого билда.
     */
    public val baseVersionCode: Long get() = versionCode % ABI_VERSION_CODE_STEP

    public companion object {
        /** Шаг смещения `versionCode` по ABI в схеме Flutter (ТЗ §15.2). */
        public const val ABI_VERSION_CODE_STEP: Long = 1000L
    }
}
