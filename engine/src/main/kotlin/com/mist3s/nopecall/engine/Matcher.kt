package com.mist3s.nopecall.engine

/**
 * Сопоставление одного правила с фактами о звонке (ТЗ §6.2, §6.3.2).
 *
 * Здесь нет ни порядка правил, ни решения — только «совпало или нет». Порядок в [RuleEngine],
 * решение об отказах там же.
 */
internal object Matcher {

    /**
     * @throws RegexBudgetExceeded если regex-правило не уложилось в бюджет. Ловится проходом.
     */
    fun matches(rule: CompiledRule, facts: CallFacts, budgetNanos: Long): Boolean =
        when (rule.target) {
            RuleTarget.CONTACT -> facts.inContacts == true

            RuleTarget.NUMBER -> {
                // Скрытый номер сопоставлять нечем: у него нет ни цифр, ни форм (ТЗ §5.4).
                if (!facts.hasNumber) false else matchNumber(rule, facts, budgetNanos)
            }

            RuleTarget.NAME, RuleTarget.NAME_ORG, RuleTarget.NAME_CATEGORY -> {
                val text = when (rule.target) {
                    RuleTarget.NAME -> facts.name.whole
                    RuleTarget.NAME_ORG -> facts.name.org
                    else -> facts.name.category
                }
                // Названия нет — правила по названию пропускаются, решение по номеру (ТЗ §6.3).
                if (text == null || text.fold.isEmpty()) false
                else matchText(rule, text, facts, budgetNanos)
            }
        }

    private fun matchNumber(rule: CompiledRule, facts: CallFacts, budgetNanos: Long): Boolean {
        if (rule.matchType == MatchType.REGEX) {
            val field = when (rule.regexField) {
                RegexField.E164 -> facts.number.e164 ?: return false
                RegexField.RAW -> facts.number.raw
                else -> facts.number.canonicalDigits
            }
            return regexMatches(rule, field, budgetNanos)
        }

        // Совпадение по любому виду номера считается совпадением: пользователь не обязан
        // угадывать, в каком виде система отдаст номер (ТЗ §6.2.1).
        val candidates = facts.number.candidates
        return rule.allPatterns.any { pattern ->
            pattern.isNotEmpty() && candidates.any { candidate -> compare(rule.matchType, candidate, pattern) }
        }
    }

    private fun matchText(
        rule: CompiledRule,
        text: CanonicalText,
        facts: CallFacts,
        budgetNanos: Long,
    ): Boolean {
        if (rule.matchType == MatchType.REGEX) {
            val field = when (rule.regexField) {
                RegexField.NAME_RAW -> text.raw
                RegexField.NAME_FOLD -> text.fold
                else -> text.norm
            }
            return regexMatches(rule, field, budgetNanos)
        }

        if (rule.matchType == MatchType.TOKEN) {
            // Сравнение с целыми словами: `pao` совпадает с `PAO SOVKOMBANK`, но не с серединой
            // чужого слова. Ради этого тип и введён (ТЗ §6.3.2).
            return rule.allPatterns.any { pattern ->
                pattern.isNotEmpty() && text.tokens.any { it == pattern }
            }
        }

        return rule.allPatterns.any { pattern ->
            pattern.isNotEmpty() && compare(rule.matchType, text.fold, pattern)
        }
    }

    private fun compare(type: MatchType, value: String, pattern: String): Boolean = when (type) {
        MatchType.EXACT -> value == pattern
        MatchType.PREFIX -> value.startsWith(pattern)
        MatchType.SUFFIX -> value.endsWith(pattern)
        MatchType.CONTAINS -> value.contains(pattern)
        MatchType.TOKEN -> value == pattern
        MatchType.REGEX, MatchType.IN_CONTACTS -> false
    }

    private fun regexMatches(rule: CompiledRule, input: String, budgetNanos: Long): Boolean {
        // Префильтр: правило, чей обязательный литерал отсутствует во входе, отбрасывается
        // одним indexOf — без компиляции и без матчинга (архитектура §6.4).
        rule.regexLiteral?.let { literal ->
            if (!input.contains(literal, ignoreCase = true)) return false
        }
        val regex = rule.regex ?: return false
        val guarded = DeadlineCharSequence.forRule(input, budgetNanos)
        return regex.containsMatchIn(guarded)
    }
}
