package com.mist3s.nopecall.core.observe

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Выгрузка логов за период (ТЗ §7.7.3).
 *
 * Собирается потоково, без загрузки в память: на несжатых 500 МБ не должно быть OOM. Поэтому
 * читается построчно и сразу пишется в архив, а не «сначала соберём в список».
 *
 * Приложение **никуда ничего не отправляет само** — оно только создаёт файл. Сеть доступна
 * лишь апдейтеру, и это принципиально (ТЗ §15.6): отправку выбирает пользователь.
 */
public class LogExporter(
    private val log: ObservationLog,
    private val outputDir: File,
) {

    public data class Request(
        val fromAt: Long,
        val toAt: Long,
        val mask: Boolean,
        val installId: String,
        val periodLabel: String,
        /** Значения настроек логирования: без них непонятно, почему в архиве чего-то нет. */
        val config: ObservationConfig,
        val summary: String,
        val manifestExtra: Map<String, String> = emptyMap(),
    )

    public data class Result(
        val file: File,
        val bytes: Long,
        val callLines: Int,
        val techLines: Int,
    )

    /**
     * Оценка до сборки: что именно уйдёт (ТЗ §7.7.3 п. 2).
     *
     * Считает строки, а не байты сегментов: период режется построчно, и «размер сегментов»
     * ввёл бы в заблуждение на границах.
     */
    public fun estimate(fromAt: Long, toAt: Long): Estimate {
        var lines = 0
        var bytes = 0L
        for (segment in log.callSegments(fromAt, toAt)) {
            forEachLine(segment) { line ->
                val at = Json.timestampOf(line)
                if (at != null && at in fromAt..toAt) {
                    lines++
                    bytes += line.length
                }
            }
        }
        return Estimate(callLines = lines, uncompressedBytes = bytes)
    }

    public data class Estimate(val callLines: Int, val uncompressedBytes: Long) {
        /** Грубая оценка архива: gzip на JSONL даёт примерно шестикратное сжатие (ТЗ §7.7.2). */
        public val archiveBytesEstimate: Long get() = uncompressedBytes / 6
    }

    /**
     * Собирает архив. Отменяется через [cancelled]: на больших объёмах это минуты, и висящую
     * без возможности отмены операцию пользователь воспримет как зависание.
     */
    public fun export(request: Request, cancelled: () -> Boolean = { false }): Result {
        if (!outputDir.isDirectory) outputDir.mkdirs()
        // Старые архивы удаляем: они уже отданы, а место занимают вдвойне.
        outputDir.listFiles()?.forEach { if (it.name.startsWith(PREFIX)) it.delete() }

        val name = "$PREFIX-${request.installId}-${request.periodLabel}.zip"
        val target = File(outputDir, name)
        val mask = if (request.mask) MaskMode.MASKED else MaskMode.FULL

        var callLines = 0
        var techLines = 0

        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifest(request).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_SUMMARY))
            zip.write(request.summary.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_CALLS))
            for (segment in log.callSegments(request.fromAt, request.toAt)) {
                if (cancelled()) break
                forEachLine(segment) { line ->
                    val at = Json.timestampOf(line)
                    if (at != null && at in request.fromAt..request.toAt) {
                        // Маскирование при выгрузке, а не при записи: на устройстве лог нужен
                        // полный, иначе разобрать жалобу по нему невозможно (ТЗ §7.7.4).
                        zip.write((maskLine(line, mask) + "\n").toByteArray())
                        callLines++
                    }
                }
            }
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_TECH))
            for (segment in log.techSegments(request.fromAt, request.toAt)) {
                if (cancelled()) break
                forEachLine(segment) { line ->
                    val at = line.substringBefore('\t').toLongOrNull()
                    if (at != null && at in request.fromAt..request.toAt) {
                        zip.write((mask.extra(line) + "\n").toByteArray())
                        techLines++
                    }
                }
            }
            zip.closeEntry()
        }

        return Result(target, target.length(), callLines, techLines)
    }

    /**
     * Маскирование уже записанной строки (ТЗ §7.7.4).
     *
     * На диске лог лежит полным: на устройстве он нужен целиком, иначе разобрать жалобу по нему
     * невозможно. Маскируется только выгрузка, то есть уже готовая строка JSONL.
     *
     * Маскируются **только строковые значения**. Это не мелочь: сплошная замена длинных
     * последовательностей цифр по всей строке портила метки времени (`"at":1785484813888`
     * превращалось в `"at":1785***88`) — и делала JSON невалидным, потому что число теряло
     * числовой вид. Ровно тот случай, когда обезличивание уничтожает данные, ради которых
     * логи и собирали.
     */
    private fun maskLine(line: String, mask: MaskMode): String {
        if (mask == MaskMode.FULL) return line
        var masked = maskQuotedValues(line)
        // Имя из телефонной книги — персональные данные третьего лица, и в выгрузке ему делать
        // нечего. Операторская подпись, наоборот, остаётся как есть: она предмет исследования.
        if (masked.contains(CONTACTS_MARKER)) {
            for (key in NAME_KEYS) {
                masked = replaceStringValue(masked, key) { "<contact:${it.length}>" }
            }
        }
        return masked
    }

    /**
     * Применяет маскирование цифр к содержимому строковых литералов JSON, не трогая числа.
     *
     * Ключи тоже строковые литералы, но длинных последовательностей цифр в них нет — состав
     * ключей задаём мы сами, а ключи из `extras` приходят от системы и цифрами не заканчиваются.
     * Даже если такой ключ появится, замаскированный ключ хуже испорченной метки времени
     * ровно в ноль раз: значение всё равно останется читаемым.
     */
    private fun maskQuotedValues(line: String): String {
        val out = StringBuilder(line.length)
        var index = 0
        var inString = false
        val literal = StringBuilder()

        while (index < line.length) {
            val ch = line[index]
            when {
                !inString && ch == '"' -> {
                    inString = true
                    literal.setLength(0)
                }

                inString && ch == '\\' && index + 1 < line.length -> {
                    // Экранированная пара переносится как есть: разбирать её незачем.
                    literal.append(ch).append(line[index + 1])
                    index++
                }

                inString && ch == '"' -> {
                    inString = false
                    out.append('"').append(MaskMode.MASKED.extra(literal.toString())).append('"')
                }

                inString -> literal.append(ch)
                else -> out.append(ch)
            }
            index++
        }
        // Строка обрезана посередине литерала: отдаём как есть, читатель её всё равно отбросит.
        if (inString) out.append('"').append(literal)
        return out.toString()
    }

    /** Заменяет значение строкового поля `"key":"…"`, не разбирая объект целиком. */
    private fun replaceStringValue(line: String, key: String, transform: (String) -> String): String {
        val marker = "\"$key\":\""
        val start = line.indexOf(marker)
        if (start < 0) return line
        val valueStart = start + marker.length
        var index = valueStart
        while (index < line.length) {
            when {
                line[index] == '\\' -> index++
                line[index] == '"' -> {
                    val value = line.substring(valueStart, index)
                    return line.substring(0, valueStart) + transform(value) + line.substring(index)
                }
            }
            index++
        }
        return line
    }

    private fun manifest(request: Request): String = Json.line {
        put("install_id", request.installId)
        put("period", request.periodLabel)
        put("from", request.fromAt)
        put("to", request.toAt)
        put("mask", if (request.mask) "masked" else "full")
        putObject("logging") {
            request.config.toMap().forEach { (key, value) -> put(key, value) }
        }
        putObject("context") {
            request.manifestExtra.forEach { (key, value) -> put(key, value) }
        }
    }

    /** Читает сегмент построчно, распаковывая на лету. Оба вида сегментов — сжатый и нет. */
    private inline fun forEachLine(segment: SegmentStore.Segment, action: (String) -> Unit) {
        if (!segment.file.isFile) return
        try {
            val stream = if (segment.compressed) {
                GZIPInputStream(segment.file.inputStream())
            } else {
                segment.file.inputStream()
            }
            BufferedReader(InputStreamReader(stream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) action(line)
                }
            }
        } catch (_: Throwable) {
            // Обрезанный или битый сегмент пропускается целиком: архив без него полезнее,
            // чем отказ собрать архив вообще.
        }
    }

    public companion object {
        public const val PREFIX: String = "nope-call-logs"
        public const val ENTRY_MANIFEST: String = "manifest.json"
        public const val ENTRY_SUMMARY: String = "summary.txt"
        public const val ENTRY_CALLS: String = "calls.jsonl"
        public const val ENTRY_TECH: String = "tech.log"

        private const val CONTACTS_MARKER = "\"name_source\":\"CONTACTS\""

        /** Поля, содержащие название звонящего. Маскируются только если это имя из контактов. */
        private val NAME_KEYS = listOf(
            "display_name", "name_norm", "name_tokens", "name_fold", "org_fold",
        )
    }
}
