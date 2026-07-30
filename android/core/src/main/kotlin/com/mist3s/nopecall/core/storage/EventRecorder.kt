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
) {
    /**
     * Одна строка для синхронной дописи. Формат — табулированный, а не JSON: строка собирается
     * в горячем пути сразу после ответа системе, и разбор её потом делает спокойный код.
     */
    internal fun toLine(): String = listOf(
        occurredAt.toString(),
        facts?.number?.raw.orEmpty().sanitize(),
        facts?.number?.digits.orEmpty(),
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
    ).joinToString("\t")

    private fun String.sanitize(): String = replace('\t', ' ').replace('\n', ' ')
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
     * Дописывает строку синхронно. Стоимость — единицы сотен микросекунд.
     *
     * Не бросает: потеря записи хуже, чем её отсутствие, но обрушить процесс после ответа
     * системе — ещё хуже.
     */
    public fun append(record: ScreeningRecord) {
        try {
            if (!dir.isDirectory) dir.mkdirs()
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

    /** Строки, ожидающие переноса в Room. Читается в фазе 2, после разблокировки. */
    public fun drain(): List<String> {
        if (!file.isFile) return emptyList()
        val lines = try {
            file.readLines().filter { it.isNotBlank() }
        } catch (_: Throwable) {
            return emptyList()
        }
        return lines
    }

    /**
     * Помечает перенесённое.
     *
     * Идемпотентность обеспечивается переименованием: если перенос прервётся на середине,
     * повторный слив прочитает тот же файл целиком, а не половину дважды (находка ревью Су26).
     */
    public fun clear() {
        try {
            file.delete()
        } catch (_: Throwable) {
            // не критично: следующий слив просто повторится
        }
    }

    public fun sizeBytes(): Long = if (file.isFile) file.length() else 0L

    public companion object {
        public const val FILE_NAME: String = "pending_events.jsonl"

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
            digits = facts?.number?.digits.orEmpty(),
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
            verificationStatus = null,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
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
