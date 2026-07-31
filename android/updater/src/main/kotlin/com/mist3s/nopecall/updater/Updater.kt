package com.mist3s.nopecall.updater

import android.content.Context
import android.content.IntentSender
import java.io.File

/**
 * Сборка апдейтера для Android-хоста (ТЗ §15.5).
 *
 * Отдельная фабрика нужна, чтобы весь Android оставался снаружи [UpdateManager]: мост получает
 * готовый объект одной строкой, а тесты собирают тот же [UpdateManager] из поддельных частей.
 * Собственного хранилища у модуля нет: настройки («автопроверка», «предварительные версии»)
 * приходят параметрами вызова от хоста (архитектура §8.5).
 */
public object Updater {

    /**
     * Имя каталога загрузки внутри `noBackupFilesDir`.
     *
     * `noBackupFilesDir`, а не `cacheDir` и не `filesDir`: кэш система вправе очистить прямо
     * посреди установки, а `filesDir` попал бы в резервную копию, если её когда-нибудь включат —
     * а класть APK на 25 МБ в облако не нужно ни нам, ни пользователю (ТЗ §11 про приватность).
     */
    private const val DOWNLOAD_DIR = "updates"

    /**
     * @param statusAction действие широковещательного интента, которым система сообщит исход
     *   установки. Приёмник объявляет хост в `:app`: после замены APK нужно перепроверить роль
     *   и пересобрать снимок (ТЗ §15.5), а это уже не дело апдейтера.
     * @param repo репозиторий релизов; параметр существует ради тестов и отладочных сборок
     */
    public fun create(
        context: Context,
        statusAction: String,
        repo: String = GitHubUpdateSource.DEFAULT_REPO,
    ): UpdateManager = create(
        context = context,
        statusIntentSender = SystemApkInstaller.broadcastStatusSender(context, statusAction),
        repo = repo,
    )

    /** Вариант для хоста, который сам решает, куда система пришлёт исход установки. */
    public fun create(
        context: Context,
        statusIntentSender: (sessionId: Int) -> IntentSender,
        repo: String = GitHubUpdateSource.DEFAULT_REPO,
    ): UpdateManager {
        val app = context.applicationContext
        val transport = UrlHttpTransport()
        return UpdateManager(
            source = GitHubUpdateSource(transport, repo),
            transport = transport,
            installedApp = { AndroidInstalledApp.read(app) },
            downloadDir = File(app.noBackupFilesDir, DOWNLOAD_DIR),
            installer = SystemApkInstaller(app, statusIntentSender),
        )
    }
}
