package com.mist3s.nopecall.updater

import java.io.File

/**
 * Род отказа (ТЗ §15.5).
 *
 * Род, а не только текст: интерфейс обязан по-разному вести себя с «сети нет» (в тихой
 * автопроверке не показывать вовсе) и с «не совпал отпечаток» (показать и предложить страницу
 * релиза). Разбирать для этого текст сообщения — верный способ получить логику, которая
 * ломается от правки формулировки.
 */
public enum class UpdateFailureKind {
    /** Сети нет, GitHub не ответил, обрыв скачивания. Проходит само. */
    NETWORK,

    /** Манифест есть, но он битый или в нём нет обязательных полей. Дефект релиза. */
    FORMAT,

    /** Обновление существует, но неприменимо: нет сборки под ABI, нужен более новый Android. */
    INCOMPATIBLE,

    /** Не совпала контрольная сумма или отпечаток сертификата. Файл удалён, установки не будет. */
    VERIFICATION,

    /** Нет места, каталог недоступен для записи. */
    STORAGE,

    /** Системный установщик отказал: нет разрешения на установку из этого источника и прочее. */
    INSTALL,
}

/**
 * Итог проверки обновления (ТЗ §15.5).
 *
 * Типизированный результат, а не исключения и не пара «boolean + текст»: у моста должно быть
 * ровно три исхода, и «ошибка» обязана нести причину текстом. Ошибки **никогда** не показываются
 * всплывающими окнами — модуль их только возвращает, решение о показе принимает интерфейс
 * (ТЗ §15.5: автопроверка при запуске тихая, статус виден на экране «О приложении»).
 */
public sealed interface UpdateCheckResult {

    /** Обновление есть, и оно применимо на этом устройстве. */
    public data class Available(
        val manifest: UpdateManifest,
        val asset: UpdateAsset,
    ) : UpdateCheckResult

    /** Установлена актуальная версия. Сюда же попадает отфильтрованный предвыпуск. */
    public data object UpToDate : UpdateCheckResult

    public data class Failure(
        val kind: UpdateFailureKind,
        val reason: String,
        /** Страница релиза, если она известна: ТЗ §15.5 требует дать путь установки вручную. */
        val notesUrl: String? = null,
    ) : UpdateCheckResult
}

/** Итог скачивания и проверок файла (ТЗ §15.5). */
public sealed interface DownloadResult {

    /** Файл скачан, сумма и отпечаток совпали — можно отдавать системному установщику. */
    public data class Ready(val apk: File, val manifest: UpdateManifest) : DownloadResult

    public data class Failure(
        val kind: UpdateFailureKind,
        val reason: String,
        val notesUrl: String? = null,
    ) : DownloadResult
}

/** Итог передачи файла системному установщику (ТЗ §15.5). */
public sealed interface InstallResult {

    /**
     * Сессия установки создана, дальше решает пользователь в системном диалоге.
     *
     * «Начата», а не «установлено»: результат установки приходит асинхронно в тот
     * `IntentSender`, который передал хост, и знать его в момент возврата нельзя.
     */
    public data object Started : InstallResult

    public data class Failure(
        val kind: UpdateFailureKind,
        val reason: String,
        val notesUrl: String? = null,
    ) : InstallResult
}
