package com.mist3s.nopecall.updater

import java.io.File

/**
 * Сеть недоступна или ответила не тем (ТЗ §15.5).
 *
 * Отличается от [MalformedManifestException] намеренно: «нет сети» проходит само и в тихой
 * автопроверке не показывается вовсе, а «битый манифест» — дефект релиза.
 */
public class HttpFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Единственная точка выхода в сеть (ТЗ §15.5, §15.6).
 *
 * Интерфейс, а не `HttpURLConnection` внутри логики обновления, ровно для того, чтобы проверка
 * версий, выбор файла по ABI и проверки суммы и отпечатка тестировались на голой JVM без сети.
 * Реализация — [UrlHttpTransport].
 *
 * Методы **блокирующие**. Своих потоков и корутин у модуля нет сознательно: в `:updater` нельзя
 * добавлять зависимости, а планировать работу должен хост, который и без того знает, когда
 * проверка тихая фоновая, а когда её запросил пользователь.
 */
public interface HttpTransport {

    /**
     * @param maxBytes предел размера ответа; больше — отказ. Предел обязателен: доверять
     *   `Content-Length` от чужого сервера нельзя, а разбор JSON неограниченного размера
     *   в памяти телефона — готовый способ уронить процесс.
     * @throws HttpFailure
     */
    public fun getText(url: String, maxBytes: Long): String

    /**
     * Скачивание файла.
     *
     * @param onProgress зовётся по мере чтения; `total` равен 0, если размер неизвестен.
     *   Нужен интерфейсу для полосы прогресса: APK около 25 МБ, и молчащая кнопка
     *   выглядит как зависшая.
     * @return число фактически записанных байт
     * @throws HttpFailure
     */
    public fun download(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
    ): Long
}

/**
 * Куда апдейтеру разрешено обращаться (ТЗ §15.6: «обращения только к GitHub»).
 *
 * Проверка адреса — не формальность и не дубль манифеста. `latest.json` — обычный текстовый
 * файл, и если он подменён (или в релизе опечатка), то `url` файла может указывать куда угодно.
 * Ограничение хостов означает, что даже подменённый манифест не заставит приложение скачать
 * APK со стороннего сервера. Проверка чистая, поэтому проверяется тестами.
 */
public object AllowedHosts {

    public fun isAllowed(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        // Только https: usesCleartextTraffic="false" запрещает http на уровне платформы,
        // но полагаться на настройку манифеста приложения-хоста модуль не может.
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        // user@host в адресе — классический способ показать человеку один хост, а обратиться
        // к другому. Нашим адресам userInfo не нужен никогда.
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * `githubusercontent.com` в списке потому, что ссылка на файл релиза отдаёт 302 на
     * `objects.githubusercontent.com`, и без него скачивание падало бы на каждом релизе.
     */
    private val HOSTS = listOf("github.com", "githubusercontent.com")
}
