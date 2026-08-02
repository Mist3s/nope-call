package com.mist3s.nopecall.core

import com.mist3s.nopecall.core.observe.CallObservation
import com.mist3s.nopecall.core.observe.Json
import com.mist3s.nopecall.core.observe.ExtraEntry
import com.mist3s.nopecall.core.observe.LogExporter
import com.mist3s.nopecall.core.observe.NetworkContext
import com.mist3s.nopecall.core.observe.ObservationConfig
import com.mist3s.nopecall.core.observe.ObservationLog
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Режим наблюдения (ТЗ §7.7).
 *
 * Проверяется на голой JVM: писатель принимает каталог и функции времени и свободного места,
 * поэтому всё, кроме `Bundle` и `TelephonyManager`, тестируется без устройства. Это не удобство,
 * а необходимость: ротацию, предохранители и резку периода иначе пришлось бы проверять
 * недельным прогоном на телефоне.
 */
class ObservationTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private var now = NOW
    private var free = 100L * 1024 * 1024 * 1024

    private fun log(config: ObservationConfig = ObservationConfig()): ObservationLog =
        ObservationLog(
            dir = temp.root,
            configProvider = { config },
            now = { now },
            freeSpace = { free },
        )

    private fun observation(
        at: Long = NOW,
        number: String? = "+74951234567",
        name: String? = "OOO Romashka: reklama",
        nameSource: String? = "CNAP",
        extras: List<ExtraEntry> = emptyList(),
        raw: List<ExtraEntry> = emptyList(),
    ) = CallObservation(
        at = at,
        handleScheme = "tel",
        handleValue = number,
        handlePresentation = 1,
        displayNameRaw = name,
        displayNamePresentation = 1,
        verificationStatus = 1,
        creationTimeMillis = at - 300,
        extras = extras,
        raw = raw,
        digits = number?.filter { it.isDigit() },
        e164 = number,
        nameFold = "ooo romashka reklama",
        nameSource = nameSource,
        action = "REJECT",
        reason = "RULE_MATCH",
        degradations = 0,
        matchedRuleId = 7,
        checkedRuleIds = listOf(1, 2, 7),
        latencyMs = 14,
        budgetMs = 1500,
        coldStart = true,
        directBoot = false,
        watchdogFired = false,
        network = NetworkContext(networkType = "LTE", volte = true, operatorName = "MTS"),
    )

    // --- формат строки ------------------------------------------------------------------------

    @Test
    fun `метка времени стоит первой и достаётся без разбора JSON`() {
        // На этом держится выгрузка за период: сегменты режутся построчно и потоково,
        // и разбирать каждый объект ради метки времени — значит сделать выгрузку 500 МБ
        // неприемлемо долгой (ТЗ §7.7.3).
        val line = observation().toJsonLine()
        assertTrue(line.startsWith("{\"at\":$NOW,"), "фактически: ${line.take(40)}")
        assertEquals(NOW, Json.timestampOf(line))
    }

    @Test
    fun `управляющие символы в подписи не ломают строку JSONL`() {
        // В подписи оператора встречались невидимые символы, а в extras может оказаться
        // что угодно. Одна неэкранированная строка ломает сегмент для любого читателя.
        val line = observation(name = "OOO\t\"Ромашка\"\nимусор").toJsonLine()
        assertEquals(1, line.lines().size, "перевод строки обязан быть экранирован")
        assertTrue(line.contains("\\t") && line.contains("\\\"") && line.contains("\\u0001"))
    }

    @Test
    fun `дамп extras пишется вместе с типами`() {
        val line = observation(
            extras = listOf(
                ExtraEntry("android.telecom.extra.CALL_SUBJECT", "String", "Реклама"),
                ExtraEntry("vendor.hidden", "Bundle", "k=v"),
            )
        ).toJsonLine()
        assertTrue(line.contains("\"key\":\"android.telecom.extra.CALL_SUBJECT\""))
        assertTrue(line.contains("\"type\":\"Bundle\""))
    }

    @Test
    fun `сырой дамп деталей звонка попадает в лог`() {
        // По разобранным полям нельзя отличить «система не дала названия» от «дала,
        // но мы его не читаем». Именно этот вопрос возник на реальном звонке: подпись
        // отображалась на экране вызова, а в getCallerDisplayName() был null.
        val line = observation(
            raw = listOf(
                ExtraEntry("details.toString", "String", "[hdl: tel:*, caps: 0, props: 1]"),
                ExtraEntry("callProperties", "Integer", "1"),
            )
        ).toJsonLine()

        assertTrue(line.contains("\"raw\""), line)
        assertTrue(line.contains("\"key\":\"details.toString\""), line)
        assertTrue(line.contains("\"key\":\"callProperties\""), line)
    }

    // --- маскирование при выгрузке (ТЗ §7.7.4) ------------------------------------------------
    //
    // Проверяется на выгрузке, а не на записи: на диске лог лежит полным по требованию §7.7.4,
    // и обезличивание существует только в архиве.

    private fun exportedLine(
        observation: CallObservation,
        mask: Boolean,
        dir: String,
    ): String {
        val log = log()
        assertTrue(log.observeCall(observation))
        val result = LogExporter(log, temp.newFolder(dir)).export(
            LogExporter.Request(
                fromAt = 0,
                toAt = now,
                mask = mask,
                installId = "id",
                periodLabel = "all",
                config = ObservationConfig(),
                summary = "",
            )
        )
        return ZipFile(result.file).use { zip ->
            zip.getInputStream(zip.getEntry(LogExporter.ENTRY_CALLS)).readBytes()
                .decodeToString().trim()
        }
    }

    @Test
    fun `обезличивание маскирует номер и оставляет операторскую подпись`() {
        // Разделение по источнику названия — не формальность: подпись юрлица предмет
        // исследования, а имя из телефонной книги — персональные данные третьего лица.
        val masked = exportedLine(observation(), mask = true, dir = "m1")
        assertFalse(masked.contains("74951234567"), "номер обязан быть замаскирован")
        assertTrue(masked.contains("7495***67"))
        assertTrue(masked.contains("OOO Romashka: reklama"), "подпись остаётся как есть")
    }

    @Test
    fun `маскирование не портит числовые поля`() {
        // Сплошная замена длинных последовательностей цифр по всей строке ломала метки
        // времени: `"at":1785484813888` превращалось в `"at":1785***88`, то есть JSON
        // становился невалидным, а лог — бесполезным.
        val masked = exportedLine(observation(), mask = true, dir = "m2")
        assertEquals(NOW, Json.timestampOf(masked), "метка времени обязана уцелеть")
        assertTrue(masked.contains("\"created_at\":${NOW - 300}"))
        assertTrue(masked.contains("\"latency_ms\":14"))
    }

    @Test
    fun `имя из телефонной книги при обезличивании заменяется`() {
        val masked = exportedLine(
            observation(name = "Мама", nameSource = "CONTACTS"),
            mask = true,
            dir = "m3",
        )
        assertFalse(masked.contains("Мама"))
        assertTrue(masked.contains("<contact:4>"))
    }

    @Test
    fun `номер внутри значения extras тоже маскируется`() {
        // Состав extras заранее неизвестен — ради него режим и существует. Значит «неизвестный
        // ключ» здесь означает «может содержать номер».
        val masked = exportedLine(
            observation(
                extras = listOf(ExtraEntry("vendor.caller", "String", "call from 74951234567 now"))
            ),
            mask = true,
            dir = "m4",
        )
        assertFalse(masked.contains("74951234567"))
        assertTrue(masked.contains("call from 7495***67 now"))
    }

    @Test
    fun `полная выгрузка отдаёт всё как есть`() {
        val full = exportedLine(observation(), mask = false, dir = "m5")
        assertTrue(full.contains("74951234567"))
        assertEquals(NOW, Json.timestampOf(full))
    }

    // --- запись и ротация (ТЗ §7.7.2) ---------------------------------------------------------

    @Test
    fun `при выключенном режиме не пишется ничего`() {
        val log = log(ObservationConfig(enabled = false))
        assertFalse(log.observeCall(observation()))
        assertEquals(0, log.stats().callsSegments)
    }

    @Test
    fun `сегменты режутся по суткам, закрытые сжимаются`() {
        val log = log()
        assertTrue(log.observeCall(observation()))

        now += DAY
        assertTrue(log.observeCall(observation(at = now)))

        val files = File(temp.root, "calls").listFiles()!!.map { it.name }.sorted()
        assertEquals(2, files.size, "два сегмента: $files")
        assertEquals(1, files.count { it.endsWith(".gz") }, "закрытый сегмент обязан быть сжат")
        assertEquals(1, files.count { it.endsWith(".jsonl") }, "текущий остаётся несжатым")
    }

    @Test
    fun `просроченные сегменты удаляются, запись продолжается`() {
        val log = log(ObservationConfig(callsRetentionDays = 2))
        log.observeCall(observation())

        now += 5 * DAY
        log.observeCall(observation(at = now))

        val stats = log.stats()
        assertEquals(1, stats.callsSegments, "старый сегмент обязан уйти")
        assertTrue(stats.callsBytes > 0, "новый обязан записаться")
    }

    @Test
    fun `предохранитель по свободному месту не даёт занять больше десятой части`() {
        // Независимо от настроенных лимитов (ТЗ §7.7.2).
        free = 2_000 // 10 % — это 200 байт, одна запись длиннее
        val log = log()
        assertFalse(log.observeCall(observation()), "запись не должна пройти")
        free = 100L * 1024 * 1024
        assertTrue(log.observeCall(observation()), "при нормальном месте — должна")
    }

    @Test
    fun `на полном диске запись не проходит и архив остаётся цел`() {
        // Условие писалось под «лимит не задан», а совпало с «места нет»: при нулевом
        // свободном месте предохранитель РАЗРЕШАЛ запись. Хуже того, до отказа он успевал
        // удалить старые сегменты — то есть архив стирался, а запись всё равно не проходила.
        val log = log()
        assertTrue(log.observeCall(observation()), "сначала пишем, чтобы было что потерять")
        val bytesBefore = log.stats().callsBytes
        assertTrue(bytesBefore > 0)

        free = 0
        assertFalse(log.observeCall(observation()), "на полном диске писать нельзя")
        assertEquals(bytesBefore, log.stats().callsBytes, "и накопленное обязано остаться")
    }

    @Test
    fun `выключение режима не удаляет накопленное, удаление отдельное`() {
        // Иначе выключение молча уничтожало бы данные, которые как раз собирались отправить.
        val log = log()
        log.observeCall(observation())
        val bytesBefore = log.stats().callsBytes
        assertTrue(bytesBefore > 0)

        val disabled = log(ObservationConfig(enabled = false))
        assertTrue(disabled.stats().callsBytes > 0, "данные обязаны остаться")
        assertTrue(disabled.deleteAll() > 0)
        assertEquals(0, disabled.stats().callsBytes)
    }

    @Test
    fun `позднее имя пишется отдельной связанной записью`() {
        // Прямое доказательство того, что подпись досылается после решения — главный
        // измеряемый показатель проекта (ТЗ §21 п. 4).
        val log = log()
        log.observeLateName(NOW - 60_000, "74951234567", "PAO SOVKOMBANK", "paosovkombank")

        val line = currentSegmentLines("calls").single()
        assertTrue(line.contains("\"kind\":\"late_name\""))
        assertTrue(line.contains("\"event_at\":${NOW - 60_000}"))
    }

    // --- выгрузка за период (ТЗ §7.7.3) -------------------------------------------------------

    @Test
    fun `в архив попадают только строки выбранного периода`() {
        // Сегменты суточные, а «за 24 часа» на сутки не ложится: границу режем построчно.
        val log = log()
        log.observeCall(observation(at = NOW - 3 * DAY))
        now += 1
        log.observeCall(observation(at = NOW - 10_000))
        log.observeCall(observation(at = NOW - 5_000))

        val exporter = LogExporter(log, temp.newFolder("out"))
        val request = LogExporter.Request(
            fromAt = NOW - DAY,
            toAt = NOW,
            mask = true,
            installId = "abcd1234",
            periodLabel = "24h",
            config = ObservationConfig(),
            summary = "сводка",
        )
        val estimate = exporter.estimate(request.fromAt, request.toAt)
        assertEquals(2, estimate.callLines)

        val result = exporter.export(request)
        assertEquals(2, result.callLines, "строка трёхдневной давности не должна попасть")

        ZipFile(result.file).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(LogExporter.ENTRY_MANIFEST in names)
            assertTrue(LogExporter.ENTRY_SUMMARY in names)
            assertTrue(LogExporter.ENTRY_CALLS in names)

            val manifest = zip.getInputStream(zip.getEntry(LogExporter.ENTRY_MANIFEST))
                .readBytes().decodeToString()
            // Без манифеста по присланному архиву невозможно понять, что в нём есть
            // и почему чего-то нет (ТЗ §7.7.3).
            assertTrue(manifest.contains("\"install_id\":\"abcd1234\""))
            assertTrue(manifest.contains("\"mask\":\"masked\""))
            assertTrue(manifest.contains(ObservationConfig.KEY_CALLS_DAYS))

            val calls = zip.getInputStream(zip.getEntry(LogExporter.ENTRY_CALLS))
                .readBytes().decodeToString()
            assertFalse(calls.contains("74951234567"), "обезличенная выгрузка не отдаёт номер")
        }
    }

    @Test
    fun `выгрузка читает и сжатые сегменты`() {
        val log = log()
        log.observeCall(observation(at = NOW))
        now += DAY // прошлый сегмент уйдёт в gzip при следующей записи
        log.observeCall(observation(at = now))

        val exporter = LogExporter(log, temp.newFolder("out2"))
        val result = exporter.export(
            LogExporter.Request(
                fromAt = NOW - DAY,
                toAt = now,
                mask = false,
                installId = "id",
                periodLabel = "all",
                config = ObservationConfig(),
                summary = "",
            )
        )
        assertEquals(2, result.callLines, "сжатый сегмент обязан быть прочитан")
    }

    @Test
    fun `битый сегмент пропускается, а архив собирается`() {
        // Архив без одного сегмента полезнее, чем отказ собрать архив вообще.
        val log = log()
        log.observeCall(observation())
        File(temp.root, "calls/calls-1999-01-01.jsonl.gz").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val result = LogExporter(log, temp.newFolder("out3")).export(
            LogExporter.Request(
                fromAt = 0,
                toAt = NOW,
                mask = false,
                installId = "id",
                periodLabel = "all",
                config = ObservationConfig(),
                summary = "",
            )
        )
        assertEquals(1, result.callLines)
        assertTrue(result.bytes > 0)
    }

    // --- технический лог ----------------------------------------------------------------------

    @Test
    fun `подробные записи пишутся только при включённой подробности`() {
        val quiet = log(ObservationConfig(techVerbose = false))
        quiet.tech(ObservationLog.LEVEL_TRACE, "мелочь")
        Thread.sleep(WRITER_WAIT)
        assertEquals(0, File(temp.root, "tech").listFiles()?.size ?: 0)

        val verbose = log(ObservationConfig(techVerbose = true))
        verbose.tech(ObservationLog.LEVEL_TRACE, "мелочь")
        waitForTech()
        assertTrue(currentSegmentLines("tech").any { it.contains("мелочь") })
    }

    @Test
    fun `технический лог начинается с метки времени - по ней режется период`() {
        val log = log()
        log.tech(ObservationLog.LEVEL_INFO, "снимок пересобран")
        waitForTech()
        val line = currentSegmentLines("tech").single()
        assertEquals(now, line.substringBefore('\t').toLong())
    }

    // --- вспомогательное ---------------------------------------------------------------------

    /** Поток B пишется отдельным потоком: ждём появления непустого сегмента, а не файла. */
    private fun waitForTech() {
        repeat(WRITER_ATTEMPTS) {
            val bytes = File(temp.root, "tech").listFiles().orEmpty().sumOf { it.length() }
            if (bytes > 0) return
            Thread.sleep(WRITER_WAIT)
        }
    }

    private fun currentSegmentLines(dir: String): List<String> {
        val files = File(temp.root, dir).listFiles().orEmpty()
        assertTrue(files.isNotEmpty(), "сегментов нет в $dir")
        return files.flatMap { file ->
            val stream = if (file.name.endsWith(".gz")) {
                GZIPInputStream(file.inputStream())
            } else {
                file.inputStream()
            }
            stream.bufferedReader().readLines().filter { it.isNotBlank() }
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
        const val WRITER_WAIT = 20L
        const val WRITER_ATTEMPTS = 50
    }
}
