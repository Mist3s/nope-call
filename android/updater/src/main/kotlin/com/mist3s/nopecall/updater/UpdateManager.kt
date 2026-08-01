package com.mist3s.nopecall.updater

import java.io.File

/**
 * Публичный контракт апдейтера: проверить, скачать, отдать установщику (ТЗ §15.5).
 *
 * Это тот класс, который зовёт мост (`UpdaterApi`, архитектура §8.1). Android SDK здесь не
 * используется намеренно: всё, что зависит от системы, спрятано за [HttpTransport], [ApkInstaller]
 * и поставщиком [InstalledApp], поэтому весь порядок проверок покрыт тестами на голой JVM —
 * включая отказы, которые на устройстве воспроизводятся тяжело (не та сумма, не тот отпечаток).
 *
 * Методы **блокирующие** и обязаны вызываться вне главного потока: своих потоков у модуля нет,
 * потому что в `:updater` нельзя добавлять зависимости, а корутины уже есть у хоста.
 *
 * Ни один метод не бросает исключений наружу и ничего не показывает пользователю: отказ — это
 * значение с родом и текстом (ТЗ §15.5, «никогда не показывает всплывающих ошибок»).
 *
 * @param installedApp читается при каждом вызове: после самообновления версия меняется, а
 *   закэшированная привела бы к предложению установить уже установленное
 * @param downloadDir приватный каталог приложения; создаётся при первом скачивании
 */
public class UpdateManager(
    private val source: UpdateSource,
    private val transport: HttpTransport,
    private val installedApp: () -> InstalledApp,
    private val downloadDir: File,
    private val installer: ApkInstaller,
) {

    /**
     * Проверка наличия обновления (ТЗ §15.5).
     *
     * Годится и для тихой автопроверки при запуске, и для ручной: разница только в том, показывает
     * ли интерфейс [UpdateCheckResult.Failure]. Отдельного «тихого» режима внутри нет — модуль
     * и так ничего не показывает и не пишет в логи.
     */
    public fun check(allowPrerelease: Boolean): UpdateCheckResult {
        val installed = runCatching(installedApp).getOrNull()
            ?: return UpdateCheckResult.Failure(
                UpdateFailureKind.FORMAT,
                "не удалось прочитать версию установленной копии",
            )

        val manifest = try {
            source.fetchManifest(allowPrerelease)
        } catch (e: HttpFailure) {
            return UpdateCheckResult.Failure(UpdateFailureKind.NETWORK, e.message ?: "сеть недоступна")
        } catch (e: MalformedManifestException) {
            return UpdateCheckResult.Failure(
                UpdateFailureKind.FORMAT,
                "манифест релиза не разобран: ${e.message}",
            )
        }

        return UpdatePolicy.decide(manifest, installed, allowPrerelease)
    }

    /**
     * Скачивание и все проверки перед установкой (ТЗ §15.5).
     *
     * Порядок проверок:
     * 1. отпечаток сертификата релиза против отпечатка **установленной** копии;
     * 2. адрес файла — только GitHub ([AllowedHosts]);
     * 3. скачивание в приватный каталог;
     * 4. размер, если он объявлен;
     * 5. sha256 файла.
     *
     * Отпечаток проверяется **до** скачивания, хотя ТЗ §15.5 перечисляет его после. Наблюдаемое
     * поведение то же (установка не начинается, причина возвращается текстом), но при несовпадении
     * не тратятся десятки мегабайт трафика: сравнение локальное и от файла не зависит. Проверять
     * его после скачивания имело бы смысл только если бы отпечаток брался из самого APK — а он
     * берётся из манифеста и из системы.
     *
     * Любой отказ означает: файла нет (удалён), установка не начата, причина возвращена.
     */
    public fun download(
        manifest: UpdateManifest,
        asset: UpdateAsset,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult {
        val installed = runCatching(installedApp).getOrNull()
            ?: return DownloadResult.Failure(
                UpdateFailureKind.VERIFICATION,
                "не удалось прочитать подпись установленной копии",
                manifest.notesUrl,
            )

        verifySigningCert(manifest, installed)?.let { return it }

        if (!AllowedHosts.isAllowed(asset.url)) {
            // Манифест указывает на файл вне GitHub — либо релиз собран неправильно, либо
            // ответ подменён. В обоих случаях скачивать нельзя.
            return DownloadResult.Failure(
                UpdateFailureKind.VERIFICATION,
                "файл релиза лежит не на GitHub: ${asset.url}",
                manifest.notesUrl,
            )
        }

        val target = try {
            prepareTarget(manifest, asset)
        } catch (e: Exception) {
            return DownloadResult.Failure(
                UpdateFailureKind.STORAGE,
                "нет доступа к каталогу загрузки: ${e.message ?: e.javaClass.simpleName}",
                manifest.notesUrl,
            )
        }

        val written = try {
            transport.download(asset.url, target, maxBytes(asset), onProgress)
        } catch (e: HttpFailure) {
            target.delete()
            return DownloadResult.Failure(
                UpdateFailureKind.NETWORK,
                e.message ?: "скачивание не удалось",
                manifest.notesUrl,
            )
        }

        // Размер сверяется до суммы: он даёт понятную причину «файл скачался не целиком»,
        // тогда как несовпадение sha256 в этом случае выглядело бы как подмена файла.
        if (asset.size > 0 && written != asset.size) {
            target.delete()
            return DownloadResult.Failure(
                UpdateFailureKind.VERIFICATION,
                "размер файла $written байт вместо ${asset.size} — файл скачан не полностью",
                manifest.notesUrl,
            )
        }

        val expected = Digests.normalizeFingerprint(asset.sha256)
            ?: run {
                target.delete()
                return DownloadResult.Failure(
                    UpdateFailureKind.FORMAT,
                    "в манифесте неверная контрольная сумма: \"${asset.sha256}\"",
                    manifest.notesUrl,
                )
            }
        val actual = runCatching { Digests.sha256(target) }.getOrNull()
        if (actual != expected) {
            target.delete()
            return DownloadResult.Failure(
                UpdateFailureKind.VERIFICATION,
                "контрольная сумма файла не совпала: ожидалась $expected, получена ${actual ?: "не вычислена"}",
                manifest.notesUrl,
            )
        }

        return DownloadResult.Ready(target, manifest)
    }

    /**
     * Передача проверенного файла системному установщику (ТЗ §15.5).
     *
     * При отказе файл удаляется: повторная попытка обязана начинаться со скачивания и проверок,
     * иначе на диске остаётся APK, про который через день нельзя сказать, проверяли его или нет.
     */
    public fun install(ready: DownloadResult.Ready): InstallResult {
        val result = installer.install(ready.apk)
        if (result is InstallResult.Failure) {
            ready.apk.delete()
            return result.copy(notesUrl = result.notesUrl ?: ready.manifest.notesUrl)
        }
        return result
    }

    /**
     * Кнопка «Обновить» целиком: скачать, проверить, отдать установщику (ТЗ §15.5).
     *
     * Один метод, потому что у моста это одно действие пользователя, а разбивать его на два
     * вызова значит позволить вызвать установку с непроверенным файлом.
     */
    public fun downloadAndInstall(
        available: UpdateCheckResult.Available,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ): InstallResult = when (val downloaded = download(available.manifest, available.asset, onProgress)) {
        is DownloadResult.Failure ->
            InstallResult.Failure(downloaded.kind, downloaded.reason, downloaded.notesUrl)

        is DownloadResult.Ready -> install(downloaded)
    }

    /**
     * Отпечаток из `latest.json` против отпечатка установленной копии (ТЗ §15.5).
     *
     * Смысл проверки: убедиться, что предлагаемое обновление подписано **тем же** ключом, что
     * и уже установленное приложение. Иначе система всё равно откажет («signatures do not match»),
     * но пользователь увидит непонятный системный отказ вместо объяснения, а до этого зря скачает
     * файл. Пустой список отпечатков установленной копии — тоже отказ: проверить нечем, значит
     * уверенности нет (ТЗ §1.1).
     */
    private fun verifySigningCert(manifest: UpdateManifest, installed: InstalledApp): DownloadResult.Failure? {
        val expected = Digests.normalizeFingerprint(manifest.signingCertSha256)
            ?: return DownloadResult.Failure(
                UpdateFailureKind.FORMAT,
                // Это дефект самого релиза, а не устройства, и пользователю надо сказать, что
                // делать: APK со страницы выпуска ставится руками и подписан тем же ключом.
                // Так и случилось в 0.1.2: в поле отпечатка оказалась подпись строки вывода
                // apksigner, потому что сборочный скрипт брал «поле после двоеточия».
                "в манифесте выпуска неверный отпечаток сертификата: " +
                    "\"${manifest.signingCertSha256}\". Это ошибка сборки релиза, " +
                    "а не вашего телефона — обновление можно поставить вручную " +
                    "со страницы выпуска",
                manifest.notesUrl,
            )
        val installedCerts = installed.signingCertsSha256.mapNotNull { Digests.normalizeFingerprint(it) }
        if (installedCerts.isEmpty()) {
            return DownloadResult.Failure(
                UpdateFailureKind.VERIFICATION,
                "не удалось прочитать отпечаток сертификата установленной копии",
                manifest.notesUrl,
            )
        }
        if (expected !in installedCerts) {
            return DownloadResult.Failure(
                UpdateFailureKind.VERIFICATION,
                "релиз подписан другим ключом: в манифесте $expected, у установленной копии " +
                    installedCerts.joinToString(", "),
                manifest.notesUrl,
            )
        }
        return null
    }

    /**
     * Файл под скачивание в приватном каталоге.
     *
     * Каталог перед скачиванием очищается: недоскачанные и уже установленные APK по 25 МБ иначе
     * копятся в приватном хранилище, которое пользователь может очистить только целиком вместе
     * с правилами и журналом.
     */
    private fun prepareTarget(manifest: UpdateManifest, asset: UpdateAsset): File {
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw java.io.IOException("каталог ${downloadDir.name} не создан")
        }
        downloadDir.listFiles()?.forEach { it.delete() }
        // Имя с версией и ABI: если пользователь пришлёт файл на разбор, из имени видно,
        // что именно скачалось.
        return File(downloadDir, "nope-call-${manifest.version}-${asset.abi}.apk")
    }

    /**
     * Предел на скачивание.
     *
     * При объявленном размере — ровно он: лишний байт означает, что по ссылке лежит не тот файл,
     * и продолжать чтение незачем. Без объявленного размера — общий предел, который заметно
     * больше любого нашего APK, но не позволяет скачать «бесконечный» поток в память телефона.
     */
    private fun maxBytes(asset: UpdateAsset): Long =
        if (asset.size > 0) asset.size else MAX_APK_BYTES

    public companion object {
        /** Предел при неизвестном размере: наши APK около 25 МБ (ТЗ §15.5). */
        public const val MAX_APK_BYTES: Long = 200L * 1024 * 1024
    }
}
