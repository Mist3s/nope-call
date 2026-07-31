package com.mist3s.nopecall.updater

/**
 * Решение «предлагать обновление или нет» (ТЗ §15.5).
 *
 * Чистая функция от манифеста, сведений об установленной копии и одной настройки. Ни сети, ни
 * файлов, ни Android SDK — поэтому все правила проверены тестами на голой JVM.
 *
 * Здесь то же отношение к неуверенности, что и в главном принципе ТЗ §1.1: сомнительный случай
 * не превращается в предложение обновиться. Установка APK — необратимая операция (по `versionCode`
 * откат невозможен), так что «предложить лишнее» дороже, чем «не предложить нужное»: во втором
 * случае у пользователя остаётся ссылка на страницу релиза.
 */
public object UpdatePolicy {

    /**
     * @param allowPrerelease галочка «предварительные версии» (ТЗ §9.6, §15.5). Настройка
     *   приходит извне, а не читается из БД: у `:updater` нет доступа к Room (архитектура §8.5).
     */
    public fun decide(
        manifest: UpdateManifest,
        installed: InstalledApp,
        allowPrerelease: Boolean,
    ): UpdateCheckResult {
        val offered = manifest.semVer
            ?: return UpdateCheckResult.Failure(
                UpdateFailureKind.FORMAT,
                "версия релиза \"${manifest.version}\" записана не по правилам semver",
                manifest.notesUrl,
            )
        val current = SemVer.parseOrNull(installed.versionName)
            ?: return UpdateCheckResult.Failure(
                UpdateFailureKind.FORMAT,
                "версию установленной копии \"${installed.versionName}\" не удалось разобрать",
                manifest.notesUrl,
            )

        // Предвыпуск без галочки — не отказ и не ошибка: для пользователя обновления просто нет.
        //
        // Признак берётся из двух мест: из поля `prerelease` в манифесте И из самой версии
        // (ТЗ §15.5: «-rc игнорируются, если не включена галочка»). Одного поля мало — его
        // достаточно забыть выставить при выпуске, и тогда предвыпуск разошёлся бы по всем
        // пользователям, включая тех, кто предвыпуски не включал. Обратный случай — тег без
        // `-rc`, помеченный предвыпуском на GitHub, — тоже учтён.
        if ((manifest.prerelease || offered.isPrerelease) && !allowPrerelease) {
            return UpdateCheckResult.UpToDate
        }

        // Предвыпуск не предлагается поверх соответствующего релиза даже с включённой галочкой:
        // 1.2.3-rc1 младше 1.2.3 по semver, и сравнение это уже учитывает.
        if (offered <= current) return UpdateCheckResult.UpToDate

        // versionCode строго возрастает (ТЗ §15.2), и Android не даёт установить сборку с
        // меньшим значением. Сравнивается базовое значение: у установленной копии в versionCode
        // может лежать смещение по ABI от --split-per-abi (см. InstalledApp.baseVersionCode).
        // Ситуация «версия выше, а build не выше» означает ошибку выпуска: установка такой
        // сборки закончилась бы отказом системы, поэтому её нельзя предлагать.
        if (manifest.build <= installed.baseVersionCode) {
            return UpdateCheckResult.Failure(
                UpdateFailureKind.FORMAT,
                "в релизе $offered номер сборки ${manifest.build} не больше установленного " +
                    "${installed.baseVersionCode} — такую сборку система не установит",
                manifest.notesUrl,
            )
        }

        // Отказ с текстом, а не «актуальная версия»: обновление существует, просто его нельзя
        // установить на этот телефон. Молчание выглядело бы как «разработка остановилась».
        if (manifest.minAndroidSdk > installed.deviceSdkInt) {
            return UpdateCheckResult.Failure(
                UpdateFailureKind.INCOMPATIBLE,
                "версия $offered требует Android API ${manifest.minAndroidSdk}, " +
                    "на устройстве ${installed.deviceSdkInt}",
                manifest.notesUrl,
            )
        }

        val asset = manifest.assetFor(installed.supportedAbis)
            ?: return UpdateCheckResult.Failure(
                UpdateFailureKind.INCOMPATIBLE,
                "в релизе $offered нет сборки под ${installed.supportedAbis.firstOrNull() ?: "ABI устройства"} " +
                    "и нет ${UpdateManifest.UNIVERSAL_ABI}",
                manifest.notesUrl,
            )

        return UpdateCheckResult.Available(manifest, asset)
    }
}
