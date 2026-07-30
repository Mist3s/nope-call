package com.mist3s.nopecall.engine

/**
 * Канонизированный текст в четырёх видах (ТЗ §6.3.2).
 *
 * Четыре, а не один, потому что разные типы сопоставления требуют разного:
 * «содержит слово» работает по [tokens], «содержит» — по [fold], regex — по [norm] или [raw],
 * а пользователю всегда показывается [raw].
 */
public data class CanonicalText(
    /** Как пришло, без изменений. Показывается пользователю. */
    val raw: String,
    /** Канонизировано с сохранением слов и кириллицы. Для regex по словам. */
    val norm: String,
    /** Слова, каждое транслитерировано в латиницу. Для «содержит слово». */
    val tokens: List<String>,
    /** Склейка [tokens] без разделителей. Для точного, начинается/заканчивается, содержит. */
    val fold: String,
) {
    public companion object {
        public val EMPTY: CanonicalText = CanonicalText("", "", emptyList(), "")
    }
}

/**
 * Разобранное название звонящего.
 *
 * Наименование и категория разделены, потому что сопоставлять их вместе бесполезно: правило
 * «содержит `it`» иначе одновременно ловит категорию `IT` у `Yandex: IT`, фрагмент `IT Link`
 * в наименовании `Agent Rostelecom IT Link Sol` и середину чужих слов (ТЗ §6.3.1).
 */
public data class NameForms(
    /** Вся подпись целиком. */
    val whole: CanonicalText,
    /** Наименование организации: часть до первого `:`, либо вся подпись. */
    val org: CanonicalText,
    /** Категория вызова, если её удалось выделить. */
    val category: CanonicalText?,
    /** Опознана ли подпись как служебная метка оператора, а не наименование. */
    val isOperatorLabel: Boolean = false,
) {
    public companion object {
        public val NONE: NameForms = NameForms(CanonicalText.EMPTY, CanonicalText.EMPTY, null)
    }
}

/** Откуда взято название (ТЗ §6.3). */
public enum class NameSource {
    /** Имя из телефонной книги. */
    CONTACTS,

    /** Операторская подпись по 41-ФЗ. */
    CNAP,

    /** Служебная метка оператора — например «Zvonok bez markirovki». */
    CNAP_OPERATOR_LABEL,

    /** Появилось после звонка, при синхронизации системного журнала. */
    SYSTEM_LOG,

    /** Названия не было. */
    NONE,
}

/** Как система представила номер (`TelecomManager.PRESENTATION_*`, ТЗ §5.4). */
public enum class NumberPresentation {
    ALLOWED,
    RESTRICTED,
    PAYPHONE,
    UNKNOWN,
}

/**
 * Номер во всех видах, нужных для сопоставления (ТЗ §6.1).
 *
 * Видов несколько, потому что один и тот же номер приходит по-разному, а правило пользователя
 * записано в третьем виде: `8495…`, `+7495…` и `7495…` должны совпадать друг с другом.
 */
public data class NumberForms(
    /** Строка обработчика как пришла. */
    val raw: String,
    /** Только цифры из [raw]. */
    val digits: String,
    /** E.164, если номер удалось разобрать. */
    val e164: String?,
    /** Национальная значащая часть, если известна. */
    val national: String?,
    /** Цифры с переписанным магистральным префиксом: для RU ведущая `8` заменена на `7`. */
    val canonicalDigits: String,
    /** Короткий или служебный номер. */
    val isShort: Boolean,
    /** Схема обработчика: `tel`, `sip` и т. п. */
    val scheme: String = "tel",
) {
    /**
     * Виды, по которым идёт сопоставление префикса и суффикса. Совпадение по любому считается
     * совпадением: пользователь не обязан угадывать, в каком виде придёт номер.
     */
    public val candidates: List<String>
        get() = buildList {
            add(canonicalDigits)
            e164?.let { add(it.removePrefix("+")) }
            national?.let { add(it) }
            if (digits != canonicalDigits) add(digits)
        }.distinct().filter { it.isNotEmpty() }

    public companion object {
        public val EMPTY: NumberForms = NumberForms(
            raw = "",
            digits = "",
            e164 = null,
            national = null,
            canonicalDigits = "",
            isShort = false,
        )
    }
}

/**
 * Всё, что движок знает о звонке. Собирается адаптером, движок только читает (архитектура §6.1).
 */
public data class CallFacts(
    val number: NumberForms,
    val presentation: NumberPresentation,
    val name: NameForms,
    val nameSource: NameSource,
    /** Номер найден в телефонной книге. `null` — проверить не удалось. */
    val inContacts: Boolean?,
    /** Экстренный номер: разрешается до прохода по правилам, при любых настройках. */
    val isEmergency: Boolean,
) {
    /** Есть ли что сопоставлять шаблонами. У скрытого номера — нечего. */
    public val hasNumber: Boolean
        get() = presentation == NumberPresentation.ALLOWED && number.digits.isNotEmpty()

    public val hasName: Boolean
        get() = name.whole.fold.isNotEmpty()
}
