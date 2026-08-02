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
    /** Начало окна, по которому посчитана сводка. */
    val fromAt: Long,
    /** Конец окна. Границы печатаются дословно: сводка «за 30 суток» рядом с логами за одни
     *  сутки — это расхождение внутри одного архива, и разбирающий жалобу верит сводке. */
    val toAt: Long,
    val checks: Int,
    val withSignature: Int,
    val withoutName: Int,
    /** Поздних названий любого происхождения: имена из книги, сетевые и неустановленные. */
    val lateNames: Int,
    /**
     * Из них — операторских подписей. Сейчас всегда 0: позднее название приходит только из
     * зеркала, а оно об источнике не свидетельствует. Показывать это как «0 %» нельзя —
     * см. [toText] и `lateSignaturesSince`.
     */
    val lateSignatures: Int,
    /** Названий, известных **в момент решения**. Поздние сюда не входят. */
    val namesAtDecision: Int,
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
    /**
     * Доля операторских подписей, досланных после решения (§21 п. 4).
     *
     * Пока не измеряется: канала наблюдения нет, поэтому [lateSignatures] тождественно ноль,
     * а ноль здесь неотличим от «не измерено» — и читается как «оператор подпись не досылает».
     * Поэтому наружу выводится словами, а не процентом (см. [toText]).
     */
    public val lateSignatureShare: Double
        get() = if (checks == 0) 0.0 else lateSignatures.toDouble() / checks

    public val signatureShare: Double
        get() = if (checks == 0) 0.0 else withSignature.toDouble() / checks

    /**
     * Проверок на сети без VoLTE. Остаётся как наблюдение о сети — к наличию подписи
     * отношения не имеет: её не передают независимо от типа сети (см. [toText]).
     */
    public val checksWithoutVolte: Int
        get() = volte.firstOrNull { it.bucket == "NO_VOLTE" }?.total ?: 0

    /**
     * Человекочитаемая сводка для `summary.txt` в архиве (ТЗ §7.7.3).
     *
     * Нужна, чтобы понять картину, не разбирая JSONL: присланный архив открывают, чтобы
     * ответить на вопрос, а не чтобы писать под него парсер.
     */
    public fun toText(): String = buildString {
        appendLine("Отбой — сводка режима наблюдения")
        appendLine("Период: с ${stamp(fromAt)} по ${stamp(toAt)}")
        appendLine()
        appendLine("Проверок: $checks")
        appendLine("  название было в момент решения: $namesAtDecision")
        appendLine("  название дописано позже: $lateNames, из них подпись оператора: $lateSignatures")
        if (lateNames > lateSignatures) {
            appendLine("    (у остальных источник не установлен: происхождение названия")
            appendLine("     из системного журнала определить нельзя, его дописывает и диалер)")
        }
        appendLine("  названия не было вообще: $withoutName")
        appendLine("  подпись оператора в момент решения: $withSignature (${percent(signatureShare)})")
        // Ноль здесь неотличим от «не измеряли», а прочитан будет как факт «оператор не
        // досылает». Один раз на этом уже ошиблись, поэтому пишется словами.
        if (lateSignatures > 0) {
            appendLine("  подпись оператора ПОСЛЕ решения: $lateSignatures (${percent(lateSignatureShare)})")
        } else {
            appendLine("  подпись оператора ПОСЛЕ решения: не измеряется")
            appendLine("    (нужен собственный канал наблюдения; по системному журналу")
            appendLine("     отличить «дослал оператор» от «телефон записал сам» нельзя)")
        }
        appendLine("  скрытый или неопределённый номер: $hiddenNumbers")
        // Связка «подписей нет» и «звонки шли без VoLTE» — это и есть ответ, ради которого
        // режим включён. Без неё ноль подписей читается как «оператор не передаёт».
        if (checks > 0) {
            appendLine()
            appendLine("  Названия в момент проверки не бывает, и это не отказ оператора:")
            appendLine("  система его не передаёт приложениям проверки звонков. Telecom при")
            appendLine("  подготовке данных подставляет callerDisplayName = null и presentation = 0")
            appendLine("  (ParcelableCallUtils.toParcelableCallForScreening). Название видит")
            appendLine("  звонилка — она получает полные данные о звонке, а мы номер.")
            appendLine("  Поэтому правила по названию срабатывать не могут, работают по номеру.")
        }
        appendLine()
        appendLine("Источник названия:")
        // По-русски: этот файл читает человек, который разбирает жалобу, а не парсер.
        nameSources.forEach { appendLine("  ${sourceLabel(it.bucket)}: ${it.total}") }
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

    private fun stamp(at: Long): String = java.time.Instant.ofEpochMilli(at)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private fun sourceLabel(bucket: String): String = when (bucket) {
        "CNAP", "CNAP_OPERATOR_LABEL" -> "подпись оператора в момент решения"
        "CONTACTS" -> "телефонная книга"
        "LATE_CNAP" -> "подпись оператора, дошла позже"
        "LATE_CONTACTS" -> "телефонная книга, узнали позже"
        "LATE_UNKNOWN" -> "дошло позже, источник не установлен"
        "NONE" -> "названия не было"
        else -> bucket
    }

    /** Байты показываются байтами: «0 КБ» при непустом логе читается как «ничего не пишется». */
    private fun mb(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> "${bytes / (1024 * 1024)} МБ"
    }
}

/** Построение сводки. Отдельно от данных, чтобы отчёт оставался простой структурой. */
public class ObservationReporter(
    private val db: NopeCallDatabase,
    private val log: ObservationLog,
    private val now: () -> Long = System::currentTimeMillis,
) {
    public suspend fun report(periodDays: Int = DEFAULT_PERIOD_DAYS): ObservationReport =
        report(since = now() - periodDays.toLong() * DAY_MS)

    /**
     * Сводка от заданного момента и **до настоящего времени**.
     *
     * Верхней границы у сводки нет сознательно. Все агрегаты считаются запросами вида
     * `occurredAt >= :since`, и вторая граница потребовала бы её в пятнадцати запросах ради
     * случая, которого нет: выгрузка всегда заканчивается «сейчас». Раз границы нет в коде —
     * её нет и в подписи, и печатается ровно то, что посчитано. Иначе получилось бы то же
     * расхождение, из-за которого сводка за 30 суток лежала в архиве за одни сутки.
     */
    public suspend fun report(since: Long): ObservationReport {
        val until = now()
        val periodDays = (((until - since) + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)
        val events = db.events()

        val latencies = events.latencies(since)
        val nameSources = events.byNameSource(since)
        val checks = events.countSince(since)

        return ObservationReport(
            periodDays = periodDays,
            fromAt = since,
            toAt = until,
            checks = checks,
            withSignature = nameSources
                .filter { it.bucket == "CNAP" || it.bucket == "CNAP_OPERATOR_LABEL" }
                .sumOf { it.total },
            withoutName = nameSources.filter { it.bucket == "NONE" }.sumOf { it.total },
            lateNames = events.lateNamesSince(since),
            lateSignatures = events.lateSignaturesSince(since),
            namesAtDecision = events.namesAtDecisionSince(since),
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
        // Ближайший ранг: индекс `ceil(p/100 * n) - 1`. Округление вниз, как было раньше,
        // сдвигало результат на ранг вниз — p95 по сотне значений показывал 96-е по величине
        // вместо 95-го, а p50 по двум значениям — меньшее вместо большего.
        val rank = kotlin.math.ceil(p / 100.0 * size).toInt()
        return this[(rank - 1).coerceIn(0, size - 1)]
    }

    public companion object {
        public const val DEFAULT_PERIOD_DAYS: Int = 30
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val EXTRAS_WINDOW = 2_000
        private const val SIGNATURE_SAMPLES = 50
    }
}
