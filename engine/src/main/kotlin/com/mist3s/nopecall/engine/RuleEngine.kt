package com.mist3s.nopecall.engine

/** Бюджет прохода. Отдельный интерфейс, чтобы движок не зависел от системных часов. */
public interface Budget {
    public fun exceeded(): Boolean

    /** Бюджет на одно regex-правило, наносекунды. */
    public val perRuleNanos: Long

    public companion object {
        /** Для тестов и для проверки шаблонов: не ограничивает. */
        public fun unlimited(perRuleNanos: Long = 10_000_000L): Budget = object : Budget {
            override fun exceeded(): Boolean = false
            override val perRuleNanos: Long = perRuleNanos
        }

        /** Ограничение по времени от момента создания. */
        public fun wallClock(
            totalNanos: Long = 200_000_000L,
            perRuleNanos: Long = 10_000_000L,
            clock: () -> Long = System::nanoTime,
        ): Budget = object : Budget {
            private val deadline = clock() + totalNanos
            override fun exceeded(): Boolean = clock() > deadline
            override val perRuleNanos: Long = perRuleNanos
        }
    }
}

/** Шаг прохода — для тестового прогона в диагностике (ТЗ §9.7). */
public data class TraceStep(
    val rule: CompiledRule,
    val matched: Boolean,
    val skippedReason: String? = null,
)

/**
 * Трасса решения. Собирается только по запросу.
 *
 * Ограничена по числу шагов, потому что режим наблюдения запрашивает её на каждом звонке
 * (ТЗ §7.7.1), а трасса собирается **внутри** прохода по правилам, то есть тратит бюджет
 * решения. Пределом это превращается в фиксированную стоимость: при промахе по всем правилам
 * их могут быть тысячи, и складывать в список каждое — плата за диагностику из бюджета звонка.
 * Усечение видно флагом, а не молчит.
 */
public class DecisionTrace(private val limit: Int = Int.MAX_VALUE) {
    private val _steps = mutableListOf<TraceStep>()
    public val steps: List<TraceStep> get() = _steps

    public var truncated: Boolean = false
        private set

    internal fun add(step: TraceStep) {
        if (_steps.size >= limit) {
            truncated = true
            return
        }
        _steps.add(step)
    }

    public var facts: CallFacts? = null
        internal set
    public var exactCandidate: CompiledRule? = null
        internal set
}

/**
 * Движок правил (архитектура §6).
 *
 * Семантика: **первое совпавшее правило в порядке `orderIndex` выигрывает**. Всё остальное —
 * следствия принципа ТЗ §1.1: любой отказ означает разрешить звонок и записать причину.
 */
public object RuleEngine {

    public fun decide(
        facts: CallFacts,
        snapshot: RuleSnapshot,
        budget: Budget = Budget.wallClock(),
        trace: DecisionTrace? = null,
    ): Decision {
        trace?.facts = facts

        // 1. Экстренные номера — до всего остального, при любых настройках (ТЗ §5.4).
        if (facts.isEmergency || isEmergency(facts, snapshot.settings)) {
            return Decision.allow(DecisionReason.EMERGENCY)
        }

        // 2. Главный выключатель.
        if (!snapshot.settings.blockingEnabled) {
            return Decision.allow(DecisionReason.DISABLED_BY_USER)
        }

        // 3. Нечего сопоставлять: ни номера, ни названия. Решает отдельная настройка,
        //    по умолчанию разрешить (ТЗ §5.4).
        if (!facts.hasNumber && !facts.hasName) {
            val (action, reason) = when (facts.presentation) {
                NumberPresentation.RESTRICTED ->
                    snapshot.settings.restrictedAction to DecisionReason.RESTRICTED_NUMBER
                else ->
                    snapshot.settings.unknownAction to DecisionReason.UNKNOWN_NUMBER
            }
            return Decision(action, reason, degradations = degradationsOf(facts))
        }

        return runRules(facts, snapshot, budget, trace)
    }

    private fun runRules(
        facts: CallFacts,
        snapshot: RuleSnapshot,
        budget: Budget,
        trace: DecisionTrace?,
    ): Decision {
        var flags = degradationsOf(facts)

        // Точные правила по номеру берутся из индекса за постоянное время. Индекс не меняет
        // результат: ниже проход по шаблонным правилам прерывается, как только их orderIndex
        // превысил найденный, — дальше точное правило всё равно выиграет (архитектура §6.2).
        val exactHit = snapshot.minExactOrderIndex(facts)
        trace?.exactCandidate = exactHit

        // Короткий номер: только точное правило по номеру, всё остальное пропускается (ТЗ §5.4).
        //
        // Индекс по построению содержит ровно правила `NUMBER` + `EXACT`, поэтому достаточно
        // не идти в проход по шаблонным. Итог при промахе — `ALLOW`, причём **независимо**
        // от `default_action`: «не блокируются ничем, кроме точного правила» означает и режим
        // «блокировать всё, кроме разрешённого» тоже.
        if (isShortProtected(facts)) {
            exactHit?.let { return hit(it, flags) }
            return Decision(CallAction.ALLOW, DecisionReason.SHORT_NUMBER, degradations = flags)
        }

        for (rule in snapshot.patternRules) {
            if (exactHit != null && rule.orderIndex > exactHit.orderIndex) break

            if (budget.exceeded()) {
                // Бюджет исчерпан — звонок РАЗРЕШАЕТСЯ, независимо от default_action.
                // Иначе в режиме «блокировать всё, кроме разрешённого» таймаут блокировал бы
                // звонок, а это прямое нарушение ТЗ §1.1.
                return Decision(
                    action = CallAction.ALLOW,
                    reason = DecisionReason.ENGINE_BUDGET_EXCEEDED,
                    degradations = flags,
                )
            }

            val matched = try {
                Matcher.matches(rule, facts, budget.perRuleNanos)
            } catch (_: RegexBudgetExceeded) {
                flags = flags or Degradation.RULE_SKIPPED.bit
                if (!rule.action.blocks) flags = flags or Degradation.ALLOW_RULE_SKIPPED.bit
                trace?.add(TraceStep(rule, matched = false, skippedReason = "бюджет regex"))
                continue
            } catch (t: Throwable) {
                flags = flags or Degradation.RULE_SKIPPED.bit
                if (!rule.action.blocks) flags = flags or Degradation.ALLOW_RULE_SKIPPED.bit
                trace?.add(TraceStep(rule, matched = false, skippedReason = t.message ?: "ошибка"))
                continue
            }

            trace?.add(TraceStep(rule, matched))
            if (matched) return hit(rule, flags)
        }

        exactHit?.let { return hit(it, flags) }

        return Decision(
            action = snapshot.settings.defaultAction,
            reason = DecisionReason.DEFAULT_ACTION,
            degradations = flags,
        )
    }

    /**
     * Итог по сработавшему правилу, с одной поправкой.
     *
     * Если по пути было пропущено **не блокирующее** правило, итог понижается до разрешения:
     * по весам ТЗ §5.1 такие правила стоят выше блокирующих, значит пропуск сбойного превратил
     * бы прошедший звонок в заблокированный — ровно то, что запрещает §1.1 (архитектура §6.7).
     *
     * «Не блокирующее», а не «разрешающее»: `SILENCE` звонок тоже пропускает — телефон молчит,
     * но ответить можно. Пропуск сбойного `SILENCE` с последующим `REJECT` — то же превращение
     * «прошёл» в «заблокирован», просто для другого действия.
     *
     * Понижение работает и в обратную сторону: пропущенное `ALLOW` при совпавшем `SILENCE`
     * оставило бы телефон молчащим, то есть врач не дозвонился бы. Поэтому понижается любое
     * действие, кроме самого `ALLOW`, и всегда до `ALLOW` — как самого безопасного исхода.
     */
    private fun hit(rule: CompiledRule, flags: Int): Decision {
        val downgrade = rule.action != CallAction.ALLOW &&
            (flags and Degradation.ALLOW_RULE_SKIPPED.bit) != 0
        return Decision(
            action = if (downgrade) CallAction.ALLOW else rule.action,
            reason = DecisionReason.RULE_MATCH,
            matchedRuleId = rule.id,
            degradations = flags,
        )
    }

    /**
     * Номер, защищённый правилом «короткие не блокируются ничем, кроме точного» (ТЗ §5.4).
     *
     * Порог 3 цифры — из ТЗ, и он **не** совпадает с `NumberForms.isShort` (до 6 цифр):
     * тот отвечает на другой вопрос — переводится ли номер в E.164. Использовать его здесь
     * значило бы молча отключить правила для четырёх-шестизначных сервисных номеров.
     */
    private fun isShortProtected(facts: CallFacts): Boolean {
        val digits = facts.number.digits
        return digits.isNotEmpty() && digits.length <= SHORT_PROTECTED_DIGITS
    }

    /** ТЗ §5.4: «короткие номера ≤ 3 цифр не блокируются ничем, кроме точного правила». */
    public const val SHORT_PROTECTED_DIGITS: Int = 3

    private fun degradationsOf(facts: CallFacts): Int {
        var flags = 0
        if (!facts.hasName) flags = flags or Degradation.NAME_UNAVAILABLE.bit
        if (facts.inContacts == null) flags = flags or Degradation.CONTACT_INDEX_STALE.bit
        return flags
    }

    private fun isEmergency(facts: CallFacts, settings: DecisionSettings): Boolean {
        val digits = facts.number.digits
        return digits.isNotEmpty() && digits in settings.emergencyNumbers
    }

    /**
     * Наивный последовательный перебор — эталон для property-теста.
     *
     * Публичный намеренно: это исполняемая спецификация порядка правил. Любая оптимизация
     * в [decide] обязана давать тот же результат, и именно это делает оптимизации безопасными.
     */
    public fun decideNaive(
        facts: CallFacts,
        snapshot: RuleSnapshot,
        budget: Budget = Budget.unlimited(),
    ): Decision {
        if (facts.isEmergency || isEmergency(facts, snapshot.settings)) {
            return Decision.allow(DecisionReason.EMERGENCY)
        }
        if (!snapshot.settings.blockingEnabled) {
            return Decision.allow(DecisionReason.DISABLED_BY_USER)
        }
        if (!facts.hasNumber && !facts.hasName) {
            val (action, reason) = when (facts.presentation) {
                NumberPresentation.RESTRICTED ->
                    snapshot.settings.restrictedAction to DecisionReason.RESTRICTED_NUMBER
                else ->
                    snapshot.settings.unknownAction to DecisionReason.UNKNOWN_NUMBER
            }
            return Decision(action, reason, degradations = degradationsOf(facts))
        }

        var flags = degradationsOf(facts)

        // Та же защита коротких номеров, что и в быстром проходе: иначе эталон перестанет
        // быть эталоном, и property-тест начнёт доказывать расхождение вместо совпадения.
        if (isShortProtected(facts)) {
            val exact = snapshot.minExactOrderIndex(facts)
            exact?.let { return hit(it, flags) }
            return Decision(CallAction.ALLOW, DecisionReason.SHORT_NUMBER, degradations = flags)
        }

        val all = (snapshot.patternRules + snapshot.exactNumberIndex.values.flatten())
            .distinctBy { it.id to it.orderIndex }
            .sortedBy { it.orderIndex }

        for (rule in all) {
            val matched = try {
                Matcher.matches(rule, facts, budget.perRuleNanos)
            } catch (_: Throwable) {
                flags = flags or Degradation.RULE_SKIPPED.bit
                if (!rule.action.blocks) flags = flags or Degradation.ALLOW_RULE_SKIPPED.bit
                continue
            }
            if (matched) return hit(rule, flags)
        }
        return Decision(
            action = snapshot.settings.defaultAction,
            reason = DecisionReason.DEFAULT_ACTION,
            degradations = flags,
        )
    }
}
