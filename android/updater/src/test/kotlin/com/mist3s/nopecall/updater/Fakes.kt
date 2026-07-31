package com.mist3s.nopecall.updater

import java.io.File

/**
 * Поддельные сеть, источник манифеста и установщик.
 *
 * Существуют ради главного свойства модуля: порядок проверок перед установкой проверяется без
 * устройства и без сети. На устройстве «не та контрольная сумма» и «релиз подписан другим ключом»
 * воспроизводятся только специально испорченным релизом, то есть на практике не проверялись бы
 * никогда — а это именно те ветки, которые обязаны работать.
 */
internal class FakeTransport(
    /** Ответы на `getText` по адресу. Отсутствие адреса — «сети нет». */
    val texts: MutableMap<String, String> = LinkedHashMap(),
    /** Содержимое файлов по адресу. */
    val files: MutableMap<String, String> = LinkedHashMap(),
) : HttpTransport {

    var textRequests: MutableList<String> = ArrayList()
        private set

    var downloads: MutableList<String> = ArrayList()
        private set

    /** Сколько байт «успеет» записаться до обрыва; `null` — обрыва нет. */
    var breakAfterBytes: Int? = null

    override fun getText(url: String, maxBytes: Long): String {
        textRequests += url
        return texts[url] ?: throw HttpFailure("сеть недоступна: $url")
    }

    override fun download(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (read: Long, total: Long) -> Unit,
    ): Long {
        downloads += url
        val content = files[url] ?: throw HttpFailure("сеть недоступна: $url")
        val bytes = content.toByteArray()
        val limit = breakAfterBytes
        if (limit != null) {
            // Обрыв после части файла: наружу летит HttpFailure, но частично записанный файл
            // остаётся на диске — ровно так ведёт себя настоящая реализация.
            target.writeBytes(bytes.copyOfRange(0, minOf(limit, bytes.size)))
            throw HttpFailure("соединение разорвано")
        }
        target.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return bytes.size.toLong()
    }
}

internal class FakeSource(
    var manifest: UpdateManifest? = null,
    var failure: Exception? = null,
) : UpdateSource {

    var calls: Int = 0
        private set

    var lastAllowPrerelease: Boolean? = null
        private set

    override fun fetchManifest(allowPrerelease: Boolean): UpdateManifest {
        calls++
        lastAllowPrerelease = allowPrerelease
        failure?.let { throw it }
        return manifest ?: throw HttpFailure("манифест не задан в тесте")
    }
}

internal class FakeInstaller(
    var result: InstallResult = InstallResult.Started,
) : ApkInstaller {

    var installed: MutableList<File> = ArrayList()
        private set

    override fun install(apk: File): InstallResult {
        installed += apk
        return result
    }
}
