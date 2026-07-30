package com.mist3s.nopecall.core.storage

import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.MatchType
import com.mist3s.nopecall.engine.RegexField
import com.mist3s.nopecall.engine.Rule
import com.mist3s.nopecall.engine.RuleTarget

/**
 * Перевод правил между Room и движком.
 *
 * Перечисления хранятся строками, а не порядковыми номерами: `ordinal` привязан к порядку
 * объявления, и добавление значения в середину перечисления молча переписало бы смысл уже
 * сохранённых правил. Неизвестное значение — причина исключить правило, а не угадать.
 */
internal object RuleMapping {

    fun toEngine(entity: RuleEntity): Rule? {
        val target = enumOrNull<RuleTarget>(entity.targetType) ?: return null
        val matchType = enumOrNull<MatchType>(entity.matchType) ?: return null
        val action = enumOrNull<CallAction>(entity.action) ?: return null
        val regexField = entity.regexField?.let { enumOrNull<RegexField>(it) }

        return Rule(
            id = entity.id,
            title = entity.title,
            target = target,
            matchType = matchType,
            pattern = entity.pattern,
            action = action,
            orderIndex = entity.orderIndex,
            enabled = entity.isEnabled,
            regexField = regexField,
            translitVariants = entity.translitVariants,
            leetVariants = entity.leetVariants,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}

/**
 * Вес правила для разреженной нумерации (ТЗ §5.1).
 *
 * Лестница из §4.1 исходного ТЗ сохранена не как жёсткий закон, а как правило автоматической
 * расстановки: разрешающие выше блокирующих, точные выше шаблонных. Пользователь может
 * перетащить правило куда угодно — порядок определяется только `orderIndex`.
 */
internal object RuleWeights {

    fun weightFor(target: RuleTarget, matchType: MatchType, action: CallAction): Int = when {
        target == RuleTarget.CONTACT -> 100
        action == CallAction.ALLOW && matchType == MatchType.EXACT -> 200
        action == CallAction.ALLOW && matchType == MatchType.REGEX -> 400
        action == CallAction.ALLOW -> 300
        matchType == MatchType.EXACT -> 600
        target == RuleTarget.NUMBER -> 700
        matchType == MatchType.REGEX -> 850
        else -> 900
    }

    /** База нумерации для веса: между весами остаётся место под тысячи правил. */
    fun baseFor(weight: Int): Int = weight * WEIGHT_STRIDE

    const val WEIGHT_STRIDE: Int = 1_000_000
}
