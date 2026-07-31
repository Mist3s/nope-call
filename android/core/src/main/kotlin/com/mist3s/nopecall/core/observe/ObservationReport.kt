package com.mist3s.nopecall.core.observe

import com.mist3s.nopecall.core.storage.Bucket
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.SignatureSample
import com.mist3s.nopecall.engine.Degradation

/**
 * Сводка режима наблюдения (ТЗ §7.7.5).
 *
 * Половина ответов, ради которых собираются логи, видна уже здесь — без выгрузки и без разбора
 * JSONL. Поэтому считается запросами по журналу, а не чтением сегментов: экран открывают часто,
 * и читать ради него десятки мегабайт JSON значило бы сделать его бесполезным.
 */
public data class ObservationReport(
    val periodDays: Int,
    val checks: Int,
    val withSignature: Int,
    val withoutName: Int,
    val lateNames: Int,
    val nameSources: List<Bucket>,
    val networkTypes: List<Bucket>,
    val volte: List<Bucket>,
    val latencyP50: Int,
    val latencyP95: Int,
    val latencyMax: Int,
    val coldStarts: Int,
    val watchdogFired: Int,
    val hiddenNumbers: Int,
    val extrasKeys: List<Bucket>,
    val signatures: List<SignatureSample>,
    val stats: ObservationLog.Stats,
    val droppedTechLines: Long,
) {
    /** Доля подписей, досланных после решения. Ключевой показатель §21 п. 4. */
    public val lateNameShare: Double
        get() = if (checks == 0) 0.0 else lateNames.toDouble() / checks

    public val signatureShare: Double
        get() = if (checks == 0) 0.0 else withSignature.toDouble() / checks

    /**
     * Человекочитаемая сводка для `summary.txt` в архиве (ТЗ §7.7.3).
     *
     * Нужна, чтобы понять картину, не разбирая JSONL: присланный архив открывают, чтобы
     * ответить на вопрос, а не чтобы писать под него парсер.
     */
    public fun toText(): String = buildString {
        appendLine("Отбой — сводка режима наблюдения")
        appendLine("Период: последние $periodDays сут.")
        appendLine()
        appendLine("Проверок: $checks")
        appendLine("  с названием в момент проверки: ${checks - withoutName}")
        appendLine("  без названия: $withoutName")
        appendLine("  с операторской подписью: $withSignature (${percent(signatureShare)})")
        appendLine("  подпись пришла ПОСЛЕ решения: $lateNames (${percent(lateNameShare)})")
        appendLine("  скрытый или неопределённый номер: $hiddenNumbers")
        appendLine()
        appendLine("Источник названия:")
        nameSources.forEach { appendLine("  ${it.bucket}: ${it.total}") }
        appendLine()
        appendLine("Тип сети:")
        networkTypes.forEach { appendLine("  ${it.bucket}: ${it.total}") }
        appendLine("VoLTE:")
        volte.forEach { appendLine("  ${it.bucket}: ${it.total}") }
        appendLine()
        appendLine("Задержка решения: p50 $latencyP50 мс, p95 $latencyP95 мс, max $latencyMax мс")
        appendLine("Холодных стартов: $coldStarts")
        appendLine("Срабатываний сторожевого таймера: $watchdogFired")
        appendLine()
        if (extrasKeys.isNotEmpty()) {
            appendLine("Ключи extras (сколько звонков их содержали):")
            extrasKeys.forEach { appendLine("  ${it.bucket}: ${it.total}") }
            appendLine()
        }
        if (signatures.isNotEmpty()) {
            appendLine("Наблюдённые подписи (дословно → свёрнутая форма):")
            signatures.forEach { appendLine("  ${it.raw}  →  ${it.fold ?: "—"}  ×${it.total}") }
            appendLine()
        }
        appendLine("Объём логов: события ${mb(stats.callsBytes)}, технический ${mb(stats.techBytes)}")
        appendLine("Прирост в сутки (оценка): ${mb(stats.dailyBytesEstimate)}")
        if (droppedTechLines > 0) {
            appendLine("Потеряно строк технического лога из-за переполнения: $droppedTechLines")
        }
    }

    private fun percent(value: Double): String = "${(value * 100).toInt()} %"

    private fun mb(bytes: Long): String =
        if (bytes < 1024 * 1024) "${bytes / 1024} КБ" else "${bytes / (1024 * 1024)} МБ"
}

/** Построение сводки. Отдельно от данных, чтобы отчёт оставался простой структурой. */
public class ObservationReporter(
    private val db: NopeCallDatabase,
    private val log: ObservationLog,
    private val now: () -> Long = System::currentTimeMillis,
) {
    public suspend fun report(periodDays: Int = DEFAULT_PERIOD_DAYS): ObservationReport {
        val since = now() - periodDays.toLong() * DAY_MS
        val events = db.events()

        val latencies = events.latencies(since)
        val nameSources = events.byNameSource(since)
        val checks = events.countSince(since)

        return ObservationReport(
            periodDays = periodDays,
            checks = checks,
            withSignature = nameSources
                .filter { it.bucket == "CNAP" || it.bucket == "CNAP_OPERATOR_LABEL" }
                .sumOf { it.total },
            withoutName = nameSources.filter { it.bucket == "NONE" }.sumOf { it.total },
            lateNames = events.lateNamesSince(since),
            nameSources = nameSources,
            networkTypes = events.byNetworkType(since),
            volte = events.byVolte(since),
            latencyP50 = latencies.percentile(50),
            latencyP95 = latencies.percentile(95),
            latencyMax = latencies.lastOrNull() ?: 0,
            coldStarts = events.coldStartsSince(since),
            watchdogFired = events.watchdogSince(since, Degradation.WATCHDOG_ANSWERED.bit),
            hiddenNumbers = events.hiddenNumbersSince(since),
            extrasKeys = countExtrasKeys(events.extrasKeys(since, EXTRAS_WINDOW)),
            signatures = events.signatureSamples(since, SIGNATURE_SAMPLES),
            stats = log.stats(),
            droppedTechLines = log.droppedTechLines(),
        )
    }

    /**
     * Частота ключей `extras` по звонкам, а не по вхождениям: вопрос «куда вендор кладёт
     * подпись» — это вопрос «в каком количестве звонков вообще встретился такой ключ».
     */
    private fun countExtrasKeys(rows: List<String>): List<Bucket> {
        val counts = mutableMapOf<String, Int>()
        for (row in rows) {
            row.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { key ->
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { Bucket(it.key, it.value) }
    }

    /**
     * Перцентиль по уже отсортированному списку.
     *
     * Метод «ближайший ранг», без интерполяции: по десятку значений интерполяция создаёт
     * впечатление точности, которой нет.
     */
    private fun List<Int>.percentile(p: Int): Int {
        if (isEmpty()) return 0
        val index = ((p / 100.0) * size).toInt().coerceIn(0, size - 1)
        return this[index]
    }

    public companion object {
        public const val DEFAULT_PERIOD_DAYS: Int = 30
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val EXTRAS_WINDOW = 2_000
        private const val SIGNATURE_SAMPLES = 50
    }
}
