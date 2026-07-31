package com.mist3s.nopecall.updater

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Сеть на `HttpURLConnection` (ТЗ §15.5).
 *
 * Без OkHttp и без Retrofit: два GET-запроса не стоят внешней зависимости в модуле, который
 * попадает в тот же APK, что и горячий путь. `HttpURLConnection` на Android — это тот же
 * OkHttp внутри платформы.
 *
 * Таймауты заданы явно и коротко (ТЗ §15.5: автопроверка при запуске идёт с коротким таймаутом).
 * По умолчанию `HttpURLConnection` ждёт **бесконечно**, и тогда тихая фоновая проверка держала бы
 * поток и сокет всё время, пока GitHub недоступен.
 */
public class UrlHttpTransport(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val downloadReadTimeoutMs: Int = DOWNLOAD_READ_TIMEOUT_MS,
) : HttpTransport {

    override fun getText(url: String, maxBytes: Long): String {
        val bytes = open(url, readTimeoutMs).use { stream -> readLimited(stream, maxBytes) }
        return bytes.toString(Charsets.UTF_8)
    }

    override fun download(
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (read: Long, total: Long) -> Unit,
    ): Long {
        var written = 0L
        try {
            open(url, downloadReadTimeoutMs).use { stream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > maxBytes) {
                            throw HttpFailure("файл больше ожидаемого ($written байт), скачивание прервано")
                        }
                        out.write(buffer, 0, read)
                        onProgress(written, maxBytes)
                    }
                    // fsync не нужен: файл читается тем же процессом и сразу, а не после
                    // перезагрузки. Зато нужен flush — иначе sha256 считался бы по неполному файлу.
                    out.flush()
                }
            }
        } catch (e: IOException) {
            throw HttpFailure("скачивание прервано: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return written
    }

    /**
     * Открытие соединения с проверками, общими для всех запросов.
     *
     * Перенаправления оставлены включёнными (ссылка на файл релиза всегда отдаёт 302), но
     * `HttpURLConnection` не переходит с https на http сам — то есть понижения протокола
     * на перенаправлении не произойдёт. Начальный адрес проверяется [AllowedHosts].
     */
    private fun open(url: String, readTimeout: Int): InputStream {
        if (!AllowedHosts.isAllowed(url)) {
            throw HttpFailure("адрес не разрешён апдейтеру: $url")
        }
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeout
                instanceFollowRedirects = true
                useCaches = false
                // Заголовок нужен GitHub API: без User-Agent он отвечает 403.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
            }
        } catch (e: IOException) {
            throw HttpFailure("не удалось открыть соединение: ${e.message ?: e.javaClass.simpleName}", e)
        }

        val code = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw HttpFailure("сеть недоступна: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            // 404 отдаётся и когда релизов ещё нет вовсе, поэтому код видно в тексте:
            // «нет релизов» и «GitHub закрыт» чинятся по-разному.
            throw HttpFailure("GitHub ответил HTTP $code")
        }
        return try {
            ClosingStream(connection.inputStream, connection)
        } catch (e: IOException) {
            connection.disconnect()
            throw HttpFailure("нет ответа от GitHub: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun readLimited(stream: InputStream, maxBytes: Long): ByteArray = try {
        val out = java.io.ByteArrayOutputStream(INITIAL_TEXT_BUFFER)
        val buffer = ByteArray(16 * 1024)
        var read = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count <= 0) break
            read += count
            if (read > maxBytes) throw HttpFailure("ответ больше $maxBytes байт, чтение прервано")
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    } catch (e: IOException) {
        throw HttpFailure("чтение ответа прервано: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /**
     * Поток, который закрывает и соединение.
     *
     * Нужен потому, что `use {}` закрывает только поток, а `HttpURLConnection` держит сокет
     * в пуле до `disconnect()`. На нашем объёме запросов это не утечка, но брошенное
     * соединение к недоступному хосту переживает саму проверку, а обещание «короткий
     * таймаут» должно распространяться и на сокет.
     */
    private class ClosingStream(
        private val delegate: InputStream,
        private val connection: HttpURLConnection,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            runCatching { delegate.close() }
            connection.disconnect()
        }
    }

    public companion object {
        /** Соединение: 5 секунд. Больше не имеет смысла — проверка обновлений не срочная. */
        public const val CONNECT_TIMEOUT_MS: Int = 5_000

        /** Чтение манифеста: 8 секунд на несколько килобайт с запасом на медленную сеть. */
        public const val READ_TIMEOUT_MS: Int = 8_000

        /**
         * Чтение APK: 30 секунд **на порцию**, а не на весь файл. Это не противоречит
         * «коротким таймаутам»: тайм-аут чтения ограничивает паузу между байтами, а
         * скачивание 25 МБ на слабой сети законно длится минуты.
         */
        public const val DOWNLOAD_READ_TIMEOUT_MS: Int = 30_000

        private const val USER_AGENT = "nope-call-updater"
        private const val INITIAL_TEXT_BUFFER = 16 * 1024
    }
}
