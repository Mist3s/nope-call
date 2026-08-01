package com.mist3s.nopecall.engine

/**
 * Снимок правил — то, что читает горячий путь (ТЗ §8.2, архитектура §5.2).
 *
 * Точные правила по номеру вынесены в хеш-индекс: их может быть 10 000 (ТЗ §11.2), и линейный
 * проход по ним был бы расточительным. Всё остальное — упорядоченный список: шаблонных правил
 * не более 1000, а 1000 сравнений коротких строк это десятки микросекунд против бюджета 200 мс.
 *
 * **Индекс не имеет права менять результат.** Семантика — первое совпавшее правило в порядке
 * `orderIndex`, и она закреплена property-тестом «результат = наивный последовательный перебор».
 * Именно этот инвариант делает безопасной любую последующую оптимизацию.
 */
public class RuleSnapshot internal constructor(
    public val settings: DecisionSettings,
    /** Шаблонные и прочие правила в порядке `orderIndex`. */
    internal val patternRules: List<CompiledRule>,
    /** Точные правила по номеру: канонический вид номера → правила, отсортированные по порядку. */
    internal val exactNumberIndex: Map<String, List<CompiledRule>>,
    /** Версия алгоритма канонизации, с которой собран снимок (ТЗ §6.2.2). */
    public val canonVersion: Int,
) {
    public val ruleCount: Int
        get() = patternRules.size + exactNumberIndex.values.sumOf { it.size }

    /**
     * Минимальный `orderIndex` среди совпавших точных правил, либо `null`.
     * Стоимость не зависит от числа правил — это и есть смысл индекса.
     */
    internal fun minExactOrderIndex(facts: CallFacts): CompiledRule? {
        if (!facts.hasNumber) return null
        var best: CompiledRule? = null
        for (candidate in facts.number.candidates) {
            val hits = exactNumberIndex[candidate] ?: continue
            val first = hits.firstOrNull() ?: continue
            if (best == null || first.orderIndex < best.orderIndex) best = first
        }
        return best
    }

    public companion object {
        public const val CURRENT_CANON_VERSION: Int = 1
    }
}

/**
 * Сборка снимка из правил пользователя: канонизация шаблонов, раскрытие вариантов, извлечение
 * литералов, раскладка точных правил в индекс.
 *
 * Выполняется вне горячего пути — при изменении правил и при пересборке снимка.
 */
public class SnapshotBuilder(
    private val normalizer: PhoneNumberNormalizer,
) {
    /** Предел раскрытия вариантов на одно правило (ТЗ §6.3.2). */
    public val variantLimit: Int = 64

    public fun build(
        rules: List<Rule>,
        settings: DecisionSettings = DecisionSettings(),
        canonVersion: Int = RuleSnapshot.CURRENT_CANON_VERSION,
    ): RuleSnapshot {
        val compiled = rules
            .filter { it.enabled }
            .sortedBy { it.orderIndex }
            .mapNotNull { compile(it, settings) }

        // Точное правило по номеру — единственный случай, который стоит индексировать.
        val exact = LinkedHashMap<String, MutableList<CompiledRule>>()
        val patterns = ArrayList<CompiledRule>(compiled.size)

        for (rule in compiled) {
            if (rule.target == RuleTarget.NUMBER && rule.matchType == MatchType.EXACT) {
                for (key in rule.allPatterns) {
                    if (key.isEmpty()) continue
                    exact.getOrPut(key) { mutableListOf() }.add(rule)
                }
            } else {
                patterns.add(rule)
            }
        }
        exact.values.forEach { it.sortBy { r -> r.orderIndex } }

        return RuleSnapshot(
            settings = settings,
            patternRules = patterns,
            exactNumberIndex = exact,
            canonVersion = canonVersion,
        )
    }

    /** Возвращает `null`, если правило некорректно и должно быть исключено из снимка. */
    public fun compile(rule: Rule, settings: DecisionSettings = DecisionSettings()): CompiledRule? {
        if (rule.target == RuleTarget.CONTACT || rule.matchType == MatchType.IN_CONTACTS) {
            return CompiledRule(
                id = rule.id,
                title = rule.title,
                target = RuleTarget.CONTACT,
                matchType = MatchType.IN_CONTACTS,
                action = rule.action,
                orderIndex = rule.orderIndex,
                regexField = RegexField.DIGITS,
                canonical = "",
                variants = emptyList(),
                regexSource = null,
                regexLiteral = null,
            )
        }

        if (rule.matchType == MatchType.REGEX) {
            val check = RegexValidator.validate(rule.pattern)
            if (check !is PatternCheck.Ok) return null
            return CompiledRule(
                id = rule.id,
                title = rule.title,
                target = rule.target,
                matchType = MatchType.REGEX,
                action = rule.action,
                orderIndex = rule.orderIndex,
                regexField = rule.regexField ?: defaultRegexField(rule.target),
                canonical = rule.pattern,
                variants = emptyList(),
                regexSource = rule.pattern,
                regexLiteral = check.literal,
            )
        }

        // Правило по категории может перечислять несколько категорий через запятую.
        // Сопоставление ORом уже есть — это `allPatterns`, тот же набор, в котором лежат
        // варианты написания. Ничего нового заводить не пришлось.
        val parts = patternsOf(rule).map { canonizePattern(rule, settings, it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val canonical = parts.firstOrNull() ?: return null

        val variants = when {
            rule.translitVariants || rule.leetVariants ->
                parts.flatMap { Translit.variants(it, leet = rule.leetVariants, limit = variantLimit) }
                    .distinct()
                    .take(variantLimit)
            // Без вариантов написания набор всё равно нужен, если категорий несколько:
            // `allPatterns` при пустых вариантах отдаёт только канонический шаблон,
            // и остальные категории правило искать перестало бы.
            parts.size > 1 -> parts
            else -> emptyList()
        }

        return CompiledRule(
            id = rule.id,
            title = rule.title,
            target = rule.target,
            matchType = rule.matchType,
            action = rule.action,
            orderIndex = rule.orderIndex,
            regexField = rule.regexField ?: defaultRegexField(rule.target),
            canonical = canonical,
            variants = variants,
            regexSource = null,
            regexLiteral = null,
        )
    }

    /**
     * Шаблон проходит **тот же** конвейер, что входные данные. Иначе `8495` не поймает
     * `+74951234567`, а `реклама` не поймает `Reklama` (ТЗ §6.2.1, §6.3.2).
     */
    /** Шаблоны правила: у категории их может быть несколько, у остальных целей — один. */
    private fun patternsOf(rule: Rule): List<String> =
        if (rule.target == RuleTarget.NAME_CATEGORY) {
            splitCategoryPatterns(rule.pattern).ifEmpty { listOf(rule.pattern) }
        } else {
            listOf(rule.pattern)
        }

    private fun canonizePattern(
        rule: Rule,
        settings: DecisionSettings,
        pattern: String = rule.pattern,
    ): String =
        when (rule.target) {
            RuleTarget.NUMBER -> {
                val forms = normalizer.normalize(pattern, settings.region)
                when (rule.matchType) {
                    // У точного правила шаблон — целый номер, значит его надо привести
                    // к каноническому виду целиком.
                    MatchType.EXACT -> forms.canonicalDigits.ifEmpty { forms.digits }
                    // У префикса и подстроки шаблон — часть номера, его нельзя «дописывать»
                    // до полного: `8495` это префикс, а не номер. Достаточно цифр
                    // с переписанным магистральным префиксом.
                    else -> canonizeNumberFragment(pattern)
                }
            }

            RuleTarget.NAME, RuleTarget.NAME_ORG, RuleTarget.NAME_CATEGORY ->
                if (rule.matchType == MatchType.TOKEN) {
                    NameCanonizer.patternTokens(pattern).joinToString("")
                } else {
                    NameCanonizer.canonizePattern(pattern)
                }

            RuleTarget.CONTACT -> ""
        }

    /**
     * Фрагмент номера: только цифры, а ведущая `8` переписывается в `7`, чтобы шаблон `8495`
     * совпал с каноническим `74951234567`.
     */
    private fun canonizeNumberFragment(pattern: String): String {
        val digits = pattern.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        val hasPlus = pattern.trimStart().startsWith("+")
        // Ведущая 8 — магистральный префикс, но только если её не ввели как часть кода страны.
        return if (!hasPlus && digits.length > 1 && digits[0] == '8') "7" + digits.substring(1)
        else digits
    }

    private fun defaultRegexField(target: RuleTarget): RegexField = when (target) {
        RuleTarget.NUMBER -> RegexField.E164
        else -> RegexField.NAME_NORM
    }
}
