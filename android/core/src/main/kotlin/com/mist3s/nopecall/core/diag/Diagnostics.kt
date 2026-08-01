package com.mist3s.nopecall.core.diag

import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.core.storage.Bucket
import com.mist3s.nopecall.core.storage.EventSpool
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.engine.Budget
import com.mist3s.nopecall.engine.CallFacts
import com.mist3s.nopecall.engine.DecisionTrace
import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.NameSource
import com.mist3s.nopecall.engine.NumberPresentation
import com.mist3s.nopecall.engine.PhoneNumberNormalizer
import com.mist3s.nopecall.engine.RuleEngine

/**
 * Диагностика (ТЗ §9.7).
 *
 * Экран обязательный, а не «если останется время»: блокировка звонков отказывает **тихо** —
 * роль отозвали, разрешение отобрали, прошивка убила процесс, — и без диагностики единственным
 * способом поддержки становится подключение к телефону пользователя.
 */
public data class DiagnosticsReport(
    val checksLast7Days: Int,
    val latencyP50: Int,
    val latencyP95: Int,
    val latencyMax: Int,
    /** Счётчики причин, из-за которых звонок был пропущен не по решению правила. */
    val degradedCounts: List<Bucket>,
    val ruleErrors: List<RuleError>,
    val snapshotFormatVersion: Int?,
    val snapshotCanonVersion: Int?,
    val snapshotRuleCount: Int?,
    val snapshotBuiltAt: Long?,
    val snapshotError: String?,
    val lastEvents: List<EventLine>,
    /** Разбивка по источнику названия за последние 100 проверок (§6.3.1). */
    val nameSources: List<Bucket>,
    val withSignatureLast100: Int,
    val checkedLast100: Int,
    val volte: List<Bucket>,
    /**
     * Записей события, отброшенных по достижении предела спула (архитектура §9.2).
     *
     * Показывается всегда, а не только при ненулевом значении: «0» здесь — это утверждение
     * «ничего не потеряно», и оно должно быть видно.
     */
    val droppedPendingEvents: Long,
) {
    public data class RuleError(val title: String, val errorCount: Int, val lastError: String?)

    public data class EventLine(
        val occurredAt: Long,
        val number: String,
        val action: String,
        val reason: String,
        val latencyMs: Int,
        val coldStart: Boolean?,
    )

    /**
     * Есть ли смысл строить правила по названию (ТЗ §9.7).
     *
     * Вопрос решается **до** того, как пользователь построит десяток таких правил и решит,
     * что приложение не работает: если оператор подпись не передаёт, правила по названию
     * не сработают никогда, и честнее сказать это заранее.
     */
    public val signatureLooksUnavailable: Boolean
        get() = checkedLast100 >= MIN_SAMPLE && withSignatureLast100 == 0

    /**
     * Отчёт для кнопки «Скопировать»: номера маскируются (ТЗ §9.7).
     *
     * Отчёт уходит в переписку с поддержкой, то есть третьему лицу. Полные номера там
     * не нужны никому: для разбора хватает решения, причины и задержки.
     */
    public fun toText(device: String, role: String, permissions: String): String = buildString {
        appendLine("Отбой — диагностика")
        appendLine(device)
        appendLine()
        appendLine("Роль: $role")
        appendLine("Разрешения: $permissions")
        appendLine()
        appendLine("Снимок правил: формат ${snapshotFormatVersion ?: "—"}, " +
            "канонизация ${snapshotCanonVersion ?: "—"}, правил ${snapshotRuleCount ?: "—"}")
        if (snapshotError != null) appendLine("Ошибка снимка: $snapshotError")
        appendLine()
        appendLine("Проверок за 7 суток: $checksLast7Days")
        appendLine("Задержка решения: p50 $latencyP50 мс, p95 $latencyP95 мс, max $latencyMax мс")
        if (degradedCounts.isNotEmpty()) {
            appendLine("Пропуски не по правилу:")
            degradedCounts.forEach { appendLine("  ${it.bucket}: ${it.total}") }
        }
        if (ruleErrors.isNotEmpty()) {
            appendLine("Правила с ошибками:")
            ruleErrors.forEach { appendLine("  ${it.title}: ${it.errorCount}, ${it.lastError}") }
        }
        appendLine()
        appendLine("Названия звонящих: подпись была у $withSignatureLast100 из $checkedLast100")
        nameSources.forEach { appendLine("  ${it.bucket}: ${it.total}") }
        appendLine()
        appendLine("Последние события:")
        lastEvents.forEach {
            appendLine(
                "  ${it.occurredAt} ${mask(it.number)} ${it.action}/${it.reason} " +
                    "${it.latencyMs} мс${if (it.coldStart == true) " (холодный старт)" else ""}"
            )
        }
    }

    private companion object {
        const val MIN_SAMPLE = 10

        /** Тот же вид маски, что и в выгрузке логов: код и последние две цифры (ТЗ §7.7.4). */
        fun mask(number: String): String {
            val digits = number.filter { it.isDigit() }
            return if (digits.length < 7) "***" else "+${digits.take(4)}***${digits.takeLast(2)}"
        }
    }
}

/**
 * Тестовый прогон (ТЗ §9.7).
 *
 * Способ проверить, как решится звонок, **без второго телефона**: ввести номер и название
 * и увидеть весь путь — нормализацию, кандидатов, какие правила проверялись и какое сработало.
 * Он же — способ проверить критерии приёмки.
 */
public data class TestRunResult(
    val digits: String,
    val e164: String?,
    val candidates: List<String>,
    val nameNorm: String,
    val nameFold: String,
    val orgFold: String,
    val categoryFold: String?,
    val action: String,
    val reason: String,
    val matchedRuleId: Long?,
    val matchedRuleTitle: String?,
    val elapsedMicros: Long,
    val steps: List<Step>,
    val snapshotMissing: Boolean,
) {
    public data class Step(
        val ruleId: Long,
        val title: String,
        val target: String,
        val matchType: String,
        val canonical: String,
        val matched: Boolean,
        val skippedReason: String?,
    )
}

public class DiagnosticsRepository(
    private val db: NopeCallDatabase,
    private val snapshots: SnapshotStore,
    private val normalizer: PhoneNumberNormalizer,
    /** Очередь событий из Direct Boot: нужна ради счётчика отброшенного (§9.2). */
    private val spool: EventSpool? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    public suspend fun report(): DiagnosticsReport {
        val since = now() - WEEK_MS
        val events = db.events()
        val latencies = events.latencies(since)
        val recent = events.recent(100)
        val header = snapshots.readHeader()

        return DiagnosticsReport(
            checksLast7Days = events.countSince(since),
            latencyP50 = latencies.percentile(50),
            latencyP95 = latencies.percentile(95),
            latencyMax = latencies.lastOrNull() ?: 0,
            degradedCounts = events.byReason(since).filter { it.bucket != "RULE_MATCH" },
            ruleErrors = db.rules().all()
                .filter { it.errorCount > 0 }
                .map { DiagnosticsReport.RuleError(it.title, it.errorCount, it.lastError) },
            snapshotFormatVersion = header?.formatVersion,
            snapshotCanonVersion = header?.canonVersion,
            snapshotRuleCount = header?.ruleCount,
            snapshotBuiltAt = snapshots.lastModified(),
            snapshotError = snapshots.lastFailure,
            lastEvents = recent.take(EVENT_LINES).map {
                DiagnosticsReport.EventLine(
                    occurredAt = it.occurredAt,
                    number = it.rawNumber,
                    action = it.action,
                    reason = it.reason,
                    latencyMs = it.latencyMs,
                    coldStart = it.coldStart,
                )
            },
            nameSources = events.byNameSource(since),
            // Поздние подписи исключены: на этом экране показатель отвечает на вопрос
            // «стоит ли вообще строить правила по названию», а для этого важно, была ли
            // подпись в момент решения.
            withSignatureLast100 = recent.count {
                (it.nameSource == "CNAP" || it.nameSource == "CNAP_OPERATOR_LABEL") &&
                    it.nameLate != true
            },
            checkedLast100 = recent.size,
            volte = events.byVolte(since),
            droppedPendingEvents = spool?.droppedCount() ?: 0L,
        )
    }

    /**
     * Прогоняет придуманный звонок через настоящий снимок и настоящий движок.
     *
     * Именно через настоящие: смысл прогона в том, чтобы увидеть решение, которое будет принято
     * на самом деле. Вторая реализация сопоставления «для диагностики» тут же начала бы
     * расходиться с первой и врать в самый нужный момент.
     */
    public fun testRun(number: String, name: String?, region: String = "RU"): TestRunResult {
        val snapshot = snapshots.current()
        val forms = normalizer.normalize(number, region)
        val nameForms = NameCanonizer.canonize(name)

        if (snapshot == null) {
            return TestRunResult(
                digits = forms.canonicalDigits.ifEmpty { forms.digits },
                e164 = forms.e164,
                candidates = forms.candidates,
                nameNorm = nameForms.whole.norm,
                nameFold = nameForms.whole.fold,
                orgFold = nameForms.org.fold,
                categoryFold = nameForms.category?.fold,
                action = "ALLOW",
                reason = "SNAPSHOT_UNAVAILABLE",
                matchedRuleId = null,
                matchedRuleTitle = null,
                elapsedMicros = 0,
                steps = emptyList(),
                snapshotMissing = true,
            )
        }

        val facts = CallFacts(
            number = forms,
            presentation = if (forms.digits.isEmpty()) {
                NumberPresentation.RESTRICTED
            } else {
                NumberPresentation.ALLOWED
            },
            name = nameForms,
            nameSource = if (name.isNullOrBlank()) NameSource.NONE else NameSource.CNAP,
            // «Не знаю» вместо «нет»: иначе прогон утверждал бы, что номера нет в контактах,
            // хотя проверить это здесь нечем.
            inContacts = null,
            isEmergency = false,
        )

        val trace = DecisionTrace()
        val decision = RuleEngine.decide(facts, snapshot, Budget.wallClock(), trace)

        return TestRunResult(
            digits = forms.canonicalDigits.ifEmpty { forms.digits },
            e164 = forms.e164,
            candidates = forms.candidates,
            nameNorm = nameForms.whole.norm,
            nameFold = nameForms.whole.fold,
            orgFold = nameForms.org.fold,
            categoryFold = nameForms.category?.fold,
            action = decision.action.name,
            reason = decision.reason.name,
            matchedRuleId = decision.matchedRuleId,
            matchedRuleTitle = trace.steps.firstOrNull { it.matched }?.rule?.title
                ?: trace.exactCandidate?.takeIf { it.id == decision.matchedRuleId }?.title,
            elapsedMicros = decision.elapsedNanos / 1_000,
            steps = trace.steps.map { step ->
                TestRunResult.Step(
                    ruleId = step.rule.id,
                    title = step.rule.title,
                    target = step.rule.target.name,
                    matchType = step.rule.matchType.name,
                    canonical = step.rule.canonical,
                    matched = step.matched,
                    skippedReason = step.skippedReason,
                )
            },
            snapshotMissing = false,
        )
    }

    private fun List<Int>.percentile(p: Int): Int {
        if (isEmpty()) return 0
        val index = ((p / 100.0) * size).toInt().coerceIn(0, size - 1)
        return this[index]
    }

    private companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        const val EVENT_LINES = 50
    }
}
