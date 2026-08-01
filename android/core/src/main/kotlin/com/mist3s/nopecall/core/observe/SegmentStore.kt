package com.mist3s.nopecall.core.observe

import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Сегменты лога наблюдения: запись, ротация, предохранители (ТЗ §7.7.2).
 *
 * Ротация по сроку **и** по объёму, что раньше. Одного мало ни в ту, ни в другую сторону:
 * срок не ограничивает телефон, на который звонят двести раз в сутки, а объём оставляет
 * годовой хвост на телефоне, которым почти не пользуются.
 *
 * Текущий сегмент лежит несжатым, закрытые — в gzip. Так сделано намеренно: сжимать каждую
 * строку отдельно значит получить файл из тысяч крошечных gzip-членов, где заголовки весят
 * больше выигрыша, а держать поток сжатия открытым между звонками нельзя — процесс умирает
 * сразу после ответа системе, и незакрытый gzip-поток теряет всё, что в нём накопилось.
 *
 * Работа с диском вся здесь: класс принимает каталог и функции времени и свободного места,
 * поэтому проверяется без устройства.
 */
internal class SegmentStore(
    private val dir: File,
    private val prefix: String,
    private val extension: String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Свободно на разделе. Предохранитель §7.7.2: занимать больше 10 % от этого нельзя. */
    private val freeSpace: () -> Long = { Long.MAX_VALUE },
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {

    /** Один файл лога. `dayStart` — начало суток сегмента, по нему идёт отбор за период. */
    data class Segment(
        val file: File,
        val dayStart: Long,
        val compressed: Boolean,
    ) {
        val bytes: Long get() = if (file.isFile) file.length() else 0L
    }

    data class Stats(
        val bytes: Long,
        val segments: Int,
        /** Начало самых старых суток, за которые есть данные. `null` — данных нет. */
        val oldestAt: Long?,
        val newestAt: Long?,
    )

    /**
     * Дописывает строку.
     *
     * Не бросает никогда: режим наблюдения — инструмент, а не функция приложения, и его отказ
     * не имеет права ронять обработку звонка. Отказ виден по объёму на экране режима.
     *
     * @return `true`, если строка записана
     */
    fun append(line: String, limits: Limits): Boolean = try {
        if (!dir.isDirectory) dir.mkdirs()
        val current = currentFile()
        rotateIfNeeded(current, limits)
        val target = currentFile()
        // Байты, а не символы: файл в UTF-8, и кириллическая строка занимает вдвое больше.
        // Плюс перевод строки, который дописывается ниже.
        val bytes = line.toByteArray().size.toLong() + 1
        if (enforce(limits, incomingBytes = bytes)) {
            FileOutputStream(target, /* append = */ true).use { out ->
                out.write((line + "\n").toByteArray())
                // fsync нет сознательно: строка уже в кэше страниц и переживёт смерть процесса,
                // а стоимость fsync — миллисекунды на каждый звонок.
            }
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    /** Лимиты. Настройки, а не константы: их меняют, чтобы разобрать новое поведение (§7.7.2). */
    data class Limits(val maxAgeDays: Int, val maxBytes: Long, val maxSegmentBytes: Long)

    fun segments(): List<Segment> {
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            val name = file.name
            if (!name.startsWith("$prefix-")) return@mapNotNull null
            val compressed = name.endsWith(".gz")
            val day = dayFromName(name) ?: return@mapNotNull null
            Segment(file, day, compressed)
        }.sortedBy { it.dayStart }
    }

    fun stats(): Stats {
        val segments = segments()
        return Stats(
            bytes = segments.sumOf { it.bytes },
            segments = segments.size,
            oldestAt = segments.firstOrNull()?.dayStart,
            newestAt = segments.lastOrNull()?.dayStart,
        )
    }

    fun deleteAll(): Int {
        val segments = segments()
        segments.forEach { runCatching { it.file.delete() } }
        return segments.size
    }

    /**
     * Сегменты, пересекающиеся с периодом. Границы режутся построчно уже при сборке архива:
     * сегменты суточные, а «за 24 часа» на сутки не ложится (§7.7.3).
     */
    fun segmentsIn(fromAt: Long, toAt: Long): List<Segment> =
        segments().filter { it.dayStart + DAY_MS > fromAt && it.dayStart <= toAt }

    // --- внутреннее -------------------------------------------------------------------------

    private fun currentFile(): File = File(dir, "$prefix-${today()}.$extension")

    private fun today(): String = java.time.Instant.ofEpochMilli(now())
        .atZone(zone)
        .toLocalDate()
        .toString()

    /**
     * Закрывает текущий сегмент, если наступили новые сутки или он перерос предел размера.
     *
     * Закрытый сегмент сжимается сразу. Если сжатие не удалось, файл остаётся несжатым —
     * это хуже по объёму, но лог не теряется.
     */
    private fun rotateIfNeeded(current: File, limits: Limits) {
        val stale = dir.listFiles()
            ?.filter { it.name.startsWith("$prefix-") && it.name.endsWith(".$extension") }
            ?.filter { it != current }
            .orEmpty()
        stale.forEach { compress(it) }

        if (current.isFile && current.length() >= limits.maxSegmentBytes) {
            // Предел размера внутри суток: сегмент уходит с числовым суффиксом, чтобы
            // не дописаться в уже сжатый файл за те же сутки.
            val suffix = generateSequence(2) { it + 1 }
                .first { !File(dir, "$prefix-${today()}-$it.$extension.gz").exists() }
            compress(current, File(dir, "$prefix-${today()}-$suffix.$extension.gz"))
        }
    }

    private fun compress(source: File, target: File = File(source.path + ".gz")) {
        if (!source.isFile) return
        try {
            // Дописываем в возможно существующий .gz: gzip допускает конкатенацию членов,
            // и любой распаковщик читает такой файл как один поток.
            FileOutputStream(target, /* append = */ true).use { raw ->
                GZIPOutputStream(raw).use { gz -> source.inputStream().use { it.copyTo(gz) } }
            }
            source.delete()
        } catch (_: Throwable) {
            // Останется несжатым. Выгрузка умеет читать оба вида.
        }
    }

    /**
     * Применяет лимиты: срок, объём и предохранитель по свободному месту.
     *
     * @return `false`, если писать нельзя даже после удаления всего лишнего
     */
    private fun enforce(limits: Limits, incomingBytes: Long): Boolean {
        // «Лимит не задан» и «места нет» — разные вещи, и раньше они совпадали в одном
        // `hardCap <= 0`, из-за чего на полном диске запись РАЗРЕШАЛАСЬ. Теперь порог считается
        // явно: настройка ≤ 0 означает «без ограничения», а свободное место ограничивает всегда.
        val byConfig = if (limits.maxBytes > 0) limits.maxBytes else Long.MAX_VALUE
        val hardCap = minOf(byConfig, freeSpace() / FREE_SPACE_DIVISOR)

        // Строка не влезает даже в пустой каталог — выходим ДО удаления чего бы то ни было.
        // Иначе предохранитель стирал архив и всё равно не писал: худший из возможных исходов.
        if (incomingBytes > hardCap) return false

        val cutoff = now() - limits.maxAgeDays.toLong() * DAY_MS

        var segments = segments()
        if (limits.maxAgeDays > 0) {
            segments.filter { it.dayStart + DAY_MS < cutoff }.forEach {
                runCatching { it.file.delete() }
            }
            segments = segments()
        }

        var total = segments.sumOf { it.bytes }
        // Удаляем самые старые, пока не влезаем. Запись продолжается — это кольцо, а не стоп.
        var index = 0
        while (total + incomingBytes > hardCap && index < segments.size) {
            val victim = segments[index]
            // Текущий сегмент не удаляем: иначе только что записанное исчезло бы вместе с ним.
            if (victim.file.name == currentFile().name) {
                index++
                continue
            }
            total -= victim.bytes
            runCatching { victim.file.delete() }
            index++
        }
        return total + incomingBytes <= hardCap
    }

    /** `calls-2026-07-31.jsonl.gz` и `calls-2026-07-31-2.jsonl.gz` → начало 31 июля. */
    private fun dayFromName(name: String): Long? {
        val rest = name.removePrefix("$prefix-")
        if (rest.length < DATE_LENGTH) return null
        val date = runCatching {
            java.time.LocalDate.parse(rest.substring(0, DATE_LENGTH))
        }.getOrNull() ?: return null
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    companion object {
        const val DAY_MS: Long = 24L * 60 * 60 * 1000

        /** «Не более 10 % свободного места» из §7.7.2 — предохранитель поверх настроек. */
        const val FREE_SPACE_DIVISOR: Long = 10

        private const val DATE_LENGTH = 10 // yyyy-MM-dd
    }
}
