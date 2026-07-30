package com.mist3s.nopecall.engine

/** К чему применяется правило (ТЗ §8.3, §6.3.1). */
public enum class RuleTarget {
    /** Номер во всех его видах. */
    NUMBER,

    /** Вся подпись целиком. */
    NAME,

    /** Только наименование организации — часть до первого `:`. */
    NAME_ORG,

    /** Только категория вызова. */
    NAME_CATEGORY,

    /** Системное правило «номер в телефонной книге» (ТЗ §6.4). */
    CONTACT,
}

/** Способ сопоставления (ТЗ §3.2, §6.3.2). */
public enum class MatchType {
    EXACT,
    PREFIX,
    SUFFIX,
    CONTAINS,

    /**
     * «Содержит слово» — сравнение с целыми словами, а не с подстрокой склейки.
     * Нужен из-за пробелов в подписях: `PAO SOVKOMBANK` → `[pao, sovkombank]`, и правило
     * «содержит слово `pao`» не срабатывает на слово, внутри которого случайно оказалось `pao`.
     */
    TOKEN,

    REGEX,

    /** Только для [RuleTarget.CONTACT]: сопоставление по индексу контактов. */
    IN_CONTACTS,
}

/** К какому представлению применять регулярное выражение (ТЗ §8.3). */
public enum class RegexField {
    E164,
    DIGITS,
    RAW,

    /** Канонизированное название со словами и кириллицей — по умолчанию для названий. */
    NAME_NORM,

    /** Канонизированное в латиницу без разделителей. */
    NAME_FOLD,

    /** Название как пришло. Для продвинутых случаев. */
    NAME_RAW,
}

/**
 * Правило, как его задал пользователь. Хранится в Room, редактируется в UI.
 *
 * @param orderIndex разреженная нумерация `вес × 1 000 000 + слот × 1024` (ТЗ §5.1).
 *   Первое совпавшее правило в порядке возрастания выигрывает.
 * @param translitVariants учитывать варианты транслитерации. Для наименований включается
 *   по умолчанию: у одного юрлица наблюдались `Poleznyy` и `Polezniy` (ТЗ §6.3.1).
 */
public data class Rule(
    val id: Long,
    val title: String,
    val target: RuleTarget,
    val matchType: MatchType,
    val pattern: String,
    val action: CallAction,
    val orderIndex: Int,
    val enabled: Boolean = true,
    val regexField: RegexField? = null,
    val translitVariants: Boolean = false,
    val leetVariants: Boolean = false,
)

/**
 * Правило, готовое к сопоставлению: шаблон уже канонизирован, варианты раскрыты, литерал
 * для префильтра извлечён.
 *
 * Всё это считается **при сохранении правила**, а не в момент звонка: в горячем пути
 * канонизируются только входные данные (архитектура §6.3).
 */
public class CompiledRule internal constructor(
    public val id: Long,
    public val title: String,
    public val target: RuleTarget,
    public val matchType: MatchType,
    public val action: CallAction,
    public val orderIndex: Int,
    public val regexField: RegexField,
    /** Канонизированный шаблон: `digits` для номеров, `fold` для названий. */
    public val canonical: String,
    /** Раскрытые варианты написания. Пустой список — вариантов нет. */
    public val variants: List<String>,
    /** Исходный текст регулярного выражения; `null` для остальных типов. */
    private val regexSource: String?,
    /**
     * Обязательный литерал регулярного выражения — префильтр (архитектура §6.4).
     *
     * Правило, чей литерал отсутствует во входе, отбрасывается одним `indexOf`: без компиляции
     * и без матчинга. Это важно, потому что самый частый исход звонка — «ни одно правило
     * не совпало», то есть полный проход по всем regex-правилам.
     */
    public val regexLiteral: String?,
) {
    /** Ленивая компиляция: тысяча `Pattern.compile` на холодном старте в бюджет не влезает. */
    internal val regex: Regex? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        regexSource?.let { runCatching { Regex(it) }.getOrNull() }
    }

    /** Все варианты шаблона, по которым идёт сопоставление, включая сам канонический. */
    public val allPatterns: List<String>
        get() = if (variants.isEmpty()) listOf(canonical) else variants

    override fun toString(): String = "CompiledRule(#$orderIndex $title: $target/$matchType -> $action)"
}

/** Настройки, влияющие на решение. Лежат в снимке рядом с правилами (ТЗ §8.2). */
public data class DecisionSettings(
    /** Главный выключатель. */
    val blockingEnabled: Boolean = true,
    /** Что делать, если проход завершился честно и ничего не совпало. */
    val defaultAction: CallAction = CallAction.ALLOW,
    /** Скрытый номер (ТЗ §5.4). */
    val restrictedAction: CallAction = CallAction.ALLOW,
    /** Номер не определён (ТЗ §5.4). */
    val unknownAction: CallAction = CallAction.ALLOW,
    /** Регион по умолчанию для нормализации. */
    val region: String = "RU",
    /** Корни категорий для подписей без двоеточия (ТЗ §6.3.1). */
    val categoryDictionary: Set<String> = emptySet(),
    /**
     * Экстренные номера. Резервный список нужен потому, что
     * `TelephonyManager.isEmergencyNumber` может требовать `READ_PHONE_STATE`, а оно
     * опционально — без резерва гарантия молча исчезла бы (ТЗ §5.4).
     */
    val emergencyNumbers: Set<String> = setOf(
        "112", "101", "102", "103", "104", "911", "01", "02", "03", "04",
    ),
)

/** Результат проверки шаблона при сохранении правила (ТЗ §6.5). */
public sealed interface PatternCheck {
    public data class Ok(
        val canonical: String,
        val variants: List<String>,
        val literal: String?,
        /** Вариантов оказалось больше предела, лишние отброшены — предупредить пользователя. */
        val variantsTruncated: Boolean = false,
    ) : PatternCheck

    public data class Invalid(val reason: String) : PatternCheck

    /** Выражение компилируется, но слишком дорогое: сохранять нельзя (ТЗ §6.5). */
    public data class TooExpensive(val reason: String) : PatternCheck
}
