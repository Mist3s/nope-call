package com.mist3s.nopecall.core.storage

import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.RuleSnapshot

/**
 * Разбор строки очереди событий обратно в запись журнала.
 *
 * Формат табулированный и позиционный: строка собирается в горячем пути сразу после ответа
 * системе, поэтому она максимально дешёвая в записи, а вся возня с разбором — здесь,
 * в спокойном коде после разблокировки.
 *
 * Неразобранная строка **отбрасывается молча**. Она может быть обрезана: процесс мог умереть
 * посередине записи, и это ожидаемый случай, а не ошибка.
 */
internal object SpoolLine {

    private const val FIELD_COUNT = 14

    fun parse(line: String): ScreeningEventEntity? {
        val parts = line.split('\t')
        if (parts.size != FIELD_COUNT) return null

        val occurredAt = parts[0].toLongOrNull() ?: return null
        val nameRaw = parts[5].takeIf { it.isNotEmpty() }
        val name = nameRaw?.let { NameCanonizer.canonize(it) }

        return ScreeningEventEntity(
            occurredAt = occurredAt,
            rawNumber = parts[1],
            digits = parts[2],
            e164 = parts[3].takeIf { it.isNotEmpty() },
            presentation = parts[4].ifEmpty { "UNKNOWN" },
            nameRaw = nameRaw,
            nameNorm = name?.whole?.norm?.takeIf { it.isNotEmpty() },
            nameTokens = name?.whole?.tokens
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(" ", prefix = " ", postfix = " "),
            nameFold = name?.whole?.fold?.takeIf { it.isNotEmpty() },
            orgFold = name?.org?.fold?.takeIf { it.isNotEmpty() },
            categoryFold = name?.category?.fold,
            nameSource = parts[6].ifEmpty { "NONE" },
            action = parts[7].ifEmpty { return null },
            reason = parts[8].ifEmpty { return null },
            degradations = parts[9].toIntOrNull() ?: 0,
            matchedRuleId = parts[10].toLongOrNull(),
            matchedRuleTitle = parts[11].takeIf { it.isNotEmpty() },
            latencyMs = parts[12].toIntOrNull() ?: 0,
            budgetMs = parts[13].toIntOrNull() ?: 0,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )
    }
}
