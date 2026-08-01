package com.mist3s.nopecall.core.storage

import com.mist3s.nopecall.engine.CallFacts
import com.mist3s.nopecall.engine.Decision
import com.mist3s.nopecall.engine.RuleSnapshot
import java.io.File
import java.io.FileOutputStream

/**
 * Событие проверки в виде, пригодном и для Room, и для строки в файле.
 */
public data class ScreeningRecord(
    val occurredAt: Long,
    val facts: CallFacts?,
    val decision: Decision,
    val matchedRuleTitle: String?,
    val budgetMs: Int,
    /**
     * Диагностика и контекст сети (ТЗ §7.1, §7.7.5).
     *
     * Хранится в журнале, а не только в сегментах режима наблюдения: иначе сводку §7.7.5
     * пришлось бы считать разбором логов на каждое открытие экрана.
     */
    val diagnostics: ScreeningDiagnostics = ScreeningDiagnostics.NONE,
) {
    /**
     * Одна строка для синхронной дописи. Формат — табулированный, а не JSON: строка собирается
     * в горячем пути сразу после ответа системе, и разбор её потом делает спокойный код.
     *
     * Поля только дописываются в конец. Разбор принимает и короткую строку: после обновления
     * приложения в очереди может лежать запись прежнего формата, и терять её незачем.
     */
    internal fun toLine(): String = listOf(
        occurredAt.toString(),
        facts?.number?.raw.orEmpty().sanitize(),
        facts?.number?.let { it.canonicalDigits.ifEmpty { it.digits } }.orEmpty(),
        facts?.number?.e164.orEmpty(),
        facts?.presentation?.name.orEmpty(),
        facts?.name?.whole?.raw.orEmpty().sanitize(),
        facts?.nameSource?.name.orEmpty(),
        decision.action.name,
        decision.reason.name,
        decision.degradations.toString(),
        decision.matchedRuleId?.toString().orEmpty(),
        matchedRuleTitle.orEmpty().sanitize(),
        (decision.elapsedNanos / 1_000_000).toString(),
        budgetMs.toString(),
        diagnostics.coldStart.toFlag(),
        diagnostics.directBoot.toFlag(),
        diagnostics.networkType.orEmpty().sanitize(),
        diagnostics.volte.toFlag(),
        diagnostics.operatorName.orEmpty().sanitize(),
        diagnostics.roaming.toFlag(),
        diagnostics.extrasKeys.joinToString(",").sanitize(),
        diagnostics.verificationStatus?.toString().orEmpty(),
    ).joinToString("\t")

    private fun String.sanitize(): String = replace('\t', ' ').replace('\n', ' ')

    /** Три состояния, а не два: «не определяли» — не то же самое, что «нет». */
    private fun Boolean?.toFlag(): String = when (this) {
        true -> "1"
        false -> "0"
        null -> ""
    }
}

/** Диагностика одной проверки: как шёл звонок и в какой сети (ТЗ §7.1, §7.7.1). */
public data class ScreeningDiagnostics(
    val coldStart: Boolean? = null,
    val directBoot: Boolean? = null,
    val networkType: String? = null,
    val volte: Boolean? = null,
    val operatorName: String? = null,
    val roaming: Boolean? = null,
    val extrasKeys: List<String> = emptyList(),
    val verificationStatus: Int? = null,
) {
    public companion object {
        public val NONE: ScreeningDiagnostics = ScreeningDiagnostics()
    }
}

/**
 * Запись события проверки (архитектура §4.6).
 *
 * Порядок строго такой:
 *  1. ответ системе уже отправлен — задержки звонка здесь не существует по определению;
 *  2. **синхронная допись одной строки** в открытый файл, без `fsync`, не выходя
 *     из `onScreenCall`;
 *  3. всё остальное — асинхронно.
 *
 * Шаг 2 не оптимизация, а необходимость. Сразу после ответа Telecom отвязывается, и при закрытом
 * интерфейсе процесс становится кэшированным. На прошивках, которые агрессивно завершают
 * процессы, асинхронная запись систематически теряла бы именно те события, ради которых
 * существует режим наблюдения — то есть главный измеряемый показатель не набрал бы данных
 * (ТЗ §21 п. 4, находка ревью Су27).
 *
 * Файл лежит в Device Protected Storage: до первой разблокировки Room недоступен, а событие
 * всё равно надо сохранить.
 */
public class EventSpool(private val dir: File) {

    private val file = File(dir, FILE_NAME)

    /**
     * Файл, взятый в работу сливом. Существует только между [drain] и [clear].
     *
     * Нужен для идемпотентности: событие, пришедшее во время слива, дописывается в новый
     * `pending_events.jsonl`, а `clear()` удаляет только то, что было прочитано. Раньше `clear()`
     * удалял файл целиком — и такое событие исчезало, хотя запись делалась синхронно ровно
     * ради того, чтобы не потеряться (архитектура §9.2).
     */
    private val draining = File(dir, DRAINING_NAME)

    /**
     * Файл счётчика отброшенного. Именно файл, а не поле в памяти: предел спула достигается
     * тогда, когда Room долго недоступен, то есть в Direct Boot и через перезагрузки —
     * счётчик в памяти показывал бы ноль ровно в том случае, ради которого он нужен.
     */
    private val droppedFile = File(dir, DROPPED_NAME)

    /** Сколько записей отброшено по достижении предела (архитектура §9.2). */
    public fun droppedCount(): Long =
        runCatching { droppedFile.readText().trim().toLong() }.getOrDefault(0L)

    /**
     * Дописывает строку синхронно. Стоимость — единицы сотен микросекунд.
     *
     * Не бросает: потеря записи хуже, чем её отсутствие, но обрушить процесс после ответа
     * системе — ещё хуже.
     */
    public fun append(record: ScreeningRecord) {
        try {
            if (!dir.isDirectory) dir.mkdirs()
            // Предел размера: без него спул растёт неограниченно, если Room долго недоступен
            // (архитектура §9.2). Отбрасывается НОВОЕ, а не переписывается старое: перезапись
            // файла в горячем пути после ответа стоит дороже, чем потеря одной записи,
            // а отброшенное видно счётчиком.
            if (file.length() >= MAX_BYTES) {
                runCatching { droppedFile.writeText((droppedCount() + 1).toString()) }
                return
            }
            // Открываем и закрываем на каждую запись: держать дескриптор открытым между звонками
            // незачем — процесс всё равно умирает, а событий единицы в час.
            FileOutputStream(file, /* append = */ true).use { out ->
                out.write((record.toLine() + "\n").toByteArray())
                // fsync СОЗНАТЕЛЬНО нет: он стоит миллисекунды, а данные в кэше страниц
                // переживут смерть процесса — теряются они только при потере питания.
            }
        } catch (_: Throwable) {
            // Молча: диагностика этого пути живёт в счётчиках, а не в исключениях.
        }
    }

    /**
     * Строки, ожидающие переноса в Room. Читается в фазе 2, после разблокировки.
     *
     * Забирает файл переименованием, а не читает на месте: пока идёт перенос (открытие Room,
     * вставки), может прийти звонок и дописать строку. С переименованием она попадает в новый
     * файл и доживёт до следующего слива.
     *
     * Если `.draining` уже существует, значит предыдущий слив прервался — читается он, и его
     * строки переносятся заново. Повторный перенос безопаснее потери: у записи есть монотонный
     * идентификатор, а дубль виден в журнале, тогда как пропажу заметить нечем.
     */
    public fun drain(): List<String> {
        if (!draining.isFile && file.isFile) {
            // Отказ переименования не критичен: читаем на месте, как раньше.
            runCatching { file.renameTo(draining) }
        }
        val source = if (draining.isFile) draining else file
        if (!source.isFile) return emptyList()
        return try {
            source.readLines().filter { it.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Помечает перенесённое: удаляется только то, что [drain] взял в работу.
     *
     * Прерывание между [drain] и [clear] означает повторный перенос тех же строк, а не потерю
     * пришедших в это время (архитектура §9.2).
     */
    public fun clear() {
        try {
            if (draining.isFile) draining.delete() else file.delete()
        } catch (_: Throwable) {
            // не критично: следующий слив просто повторится
        }
    }

    public fun sizeBytes(): Long =
        (if (file.isFile) file.length() else 0L) + (if (draining.isFile) draining.length() else 0L)

    public companion object {
        public const val FILE_NAME: String = "pending_events.jsonl"

        /** Файл, взятый в работу сливом. Виден только между [drain] и [clear]. */
        public const val DRAINING_NAME: String = "pending_events.draining.jsonl"

        /** Счётчик отброшенных записей. Переживает перезагрузку, потому и файл. */
        public const val DROPPED_NAME: String = "pending_events.dropped"

        /** Предел размера: без него спул растёт неограниченно, если Room долго недоступен. */
        public const val MAX_BYTES: Long = 2L * 1024 * 1024
    }
}

/**
 * Перенос события в Room и обновление счётчиков правила.
 *
 * Живёт отдельно от [EventSpool], потому что работает уже после разблокировки и может
 * позволить себе транзакции и запросы.
 */
public class EventRecorder(
    private val db: NopeCallDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** @return идентификатор записи журнала. */
    public suspend fun record(record: ScreeningRecord): Long {
        val facts = record.facts
        val entity = ScreeningEventEntity(
            occurredAt = record.occurredAt,
            rawNumber = facts?.number?.raw.orEmpty(),
            // Каноническая форма: по ней идут сшивка, фильтры и предпросмотр. Как пришёл
            // номер, видно в rawNumber (архитектура §5.4).
            digits = facts?.number?.let { it.canonicalDigits.ifEmpty { it.digits } }.orEmpty(),
            e164 = facts?.number?.e164,
            presentation = facts?.presentation?.name ?: "UNKNOWN",
            nameRaw = facts?.name?.whole?.raw?.takeIf { it.isNotEmpty() },
            nameNorm = facts?.name?.whole?.norm?.takeIf { it.isNotEmpty() },
            // Ограничители по краям дают корректное «содержит слово» через LIKE '% pao %'
            // в предпросмотре и фильтрах (архитектура §5.4).
            nameTokens = facts?.name?.whole?.tokens
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" ", prefix = " ", postfix = " "),
            nameFold = facts?.name?.whole?.fold?.takeIf { it.isNotEmpty() },
            orgFold = facts?.name?.org?.fold?.takeIf { it.isNotEmpty() },
            categoryFold = facts?.name?.category?.fold,
            nameSource = facts?.nameSource?.name ?: "NONE",
            action = record.decision.action.name,
            reason = record.decision.reason.name,
            degradations = record.decision.degradations,
            matchedRuleId = record.decision.matchedRuleId,
            matchedRuleTitle = record.matchedRuleTitle,
            latencyMs = (record.decision.elapsedNanos / 1_000_000).toInt(),
            budgetMs = record.budgetMs,
            verificationStatus = record.diagnostics.verificationStatus,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
            coldStart = record.diagnostics.coldStart,
            directBoot = record.diagnostics.directBoot,
            networkType = record.diagnostics.networkType,
            volte = record.diagnostics.volte,
            operatorName = record.diagnostics.operatorName,
            roaming = record.diagnostics.roaming,
            extrasKeys = record.diagnostics.extrasKeys.takeIf { it.isNotEmpty() }?.joinToString(","),
        )
        val id = db.events().insert(entity)

        // Счётчик срабатываний обновляется только когда решение действительно приняло правило.
        // Если ответил сторож, инкрементировать нельзя: правило не срабатывало (архитектура §6.7).
        record.decision.matchedRuleId?.let { ruleId ->
            if (record.decision.reason == com.mist3s.nopecall.engine.DecisionReason.RULE_MATCH) {
                db.rules().recordMatch(ruleId, now())
            }
        }
        return id
    }

    /**
     * Переносит накопленные строки очереди в Room.
     *
     * Идемпотентность: очередь читается целиком и удаляется только после успешной вставки.
     * Прерванный перенос повторится, но не продублирует записи наполовину (находка ревью Су26).
     * Неразобранные строки отбрасываются молча — процесс мог умереть посередине записи,
     * и это ожидаемый случай.
     */
    public suspend fun drain(spool: EventSpool): Int {
        val lines = spool.drain()
        if (lines.isEmpty()) return 0
        val parsed = lines.mapNotNull { SpoolLine.parse(it) }
        if (parsed.isEmpty()) {
            spool.clear()
            return 0
        }

        // Название правила подставляется здесь, а не в горячем пути: там его негде взять —
        // движок возвращает идентификатор, а обращаться к Room во время звонка нельзя.
        // Без названия журнал не выполнял бы критерий приёмки ТЗ §18 п. 10.
        val ids = parsed.mapNotNull { it.matchedRuleId }.distinct()
        val titles = if (ids.isEmpty()) emptyMap() else db.rules().titles(ids).associate { it.id to it.title }
        val events = parsed.map { event ->
            val title = event.matchedRuleId?.let { titles[it] }
            if (title == null) event else event.copy(matchedRuleTitle = title)
        }

        db.events().insertAll(events)

        // Счётчики срабатываний: только там, где решение приняло правило. Если ответил
        // сторож, инкрементировать нельзя — правило не срабатывало (архитектура §6.7).
        for (event in events) {
            if (event.reason == "RULE_MATCH") {
                event.matchedRuleId?.let { db.rules().recordMatch(it, event.occurredAt) }
            }
        }

        spool.clear()
        return events.size
    }
}
