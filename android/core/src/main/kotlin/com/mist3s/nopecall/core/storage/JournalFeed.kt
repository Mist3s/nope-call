package com.mist3s.nopecall.core.storage

import androidx.room.ColumnInfo

/**
 * Строка объединённого журнала (ТЗ §7.3).
 *
 * Проекция запроса, а не сущность: журнал показывается как **объединение** зеркала системного
 * журнала и собственных событий проверки, которым сшивки не нашлось. Дубли исключены по
 * `matchedSystemId`, поэтому одна строка здесь — это ровно один звонок в глазах пользователя.
 *
 * Половина полей допускает `null`, и это не небрежность: у записи зеркала без нашего события
 * нет ни решения, ни причины, а у нашего события без записи зеркала нет ни исхода,
 * ни длительности. Заполнять их значениями по умолчанию значило бы врать.
 */
public data class JournalFeedRow(
    /** `0` — запись зеркала, `1` — только наше событие. Часть курсора: см. [JournalCursor]. */
    val sourceRank: Int,
    val id: Long,
    val at: Long,
    val eventId: Long?,
    val systemId: Long?,
    val rawNumber: String,
    val digits: String,
    val e164: String?,
    val name: String?,
    val nameFold: String?,
    val nameSource: String,
    /** Название дописано после решения (ТЗ §7.3). `null` — событие записано до появления флага. */
    val nameLate: Boolean?,
    /**
     * Псевдоним в запросе — `decisionAction`, а не `action`: `ACTION` в грамматике SQL,
     * которой разбирает запросы Room, зарезервировано (`ON DELETE NO ACTION`), и как имя
     * столбца оно проходит, а как псевдоним — нет. Ошибка при этом приходит от парсера
     * ANTLR и на реальную причину не указывает.
     */
    @ColumnInfo(name = "decisionAction") val action: String?,
    val reason: String?,
    val matchedRuleId: Long?,
    val matchedRuleTitle: String?,
    val latencyMs: Int?,
    val degradations: Int?,
    /** `null` — исход неизвестен: записи зеркала нет, а сервис проверки длительности не видит. */
    val durationSeconds: Int?,
    val systemType: String?,
    val phoneAccountId: String?,
)

/**
 * Курсор страницы журнала.
 *
 * Тройной, а не по одному времени. Причины ровно две, и обе встречались:
 *  * метки времени в миллисекундах совпадают у соседних записей при пакетной вставке зеркала;
 *  * `id` уникален только внутри своей таблицы, а в объединении встречаются оба — `systemId`
 *    зеркала и `id` события легко совпадают численно.
 */
public data class JournalCursor(val at: Long, val sourceRank: Int, val id: Long) {
    public companion object {
        /** Начало списка: пропускает всё, что старше «бесконечности». */
        public val START: JournalCursor = JournalCursor(Long.MAX_VALUE, -1, Long.MAX_VALUE)
    }
}

/**
 * Фильтры журнала (ТЗ §7.5).
 *
 * Все поля независимы и складываются по «И». `null` — «не фильтровать»: это не то же самое,
 * что пустая строка, и различие важно для поиска по названию.
 */
public data class JournalFilter(
    val kind: String = KIND_ALL,
    /** Подстрока по каноническим цифрам номера. */
    val digitsQuery: String? = null,
    /** Подстрока по свёрнутому названию: транслит уже применён, регистр снят. */
    val nameQuery: String? = null,
    /** `true` — только с операторской подписью, `false` — только без, `null` — все. */
    val hadSignature: Boolean? = null,
    val fromAt: Long? = null,
    val toAt: Long? = null,
    val ruleId: Long? = null,
    val sim: String? = null,
) {
    /** Пустой фильтр показывает всё — и это дефолт: журнал не должен ничего скрывать молча. */
    public val isEmpty: Boolean
        get() = kind == KIND_ALL && digitsQuery == null && nameQuery == null &&
            hadSignature == null && fromAt == null && toAt == null && ruleId == null && sim == null

    public companion object {
        public const val KIND_ALL: String = "ALL"
        public const val KIND_BLOCKED_BY_US: String = "BLOCKED_BY_US"
        public const val KIND_BLOCKED_ANY: String = "BLOCKED_ANY"
        public const val KIND_INCOMING: String = "INCOMING"
        public const val KIND_OUTGOING: String = "OUTGOING"
        public const val KIND_MISSED: String = "MISSED"
        public const val KIND_SILENCED: String = "SILENCED"

        public val KINDS: List<String> = listOf(
            KIND_ALL, KIND_BLOCKED_BY_US, KIND_BLOCKED_ANY,
            KIND_INCOMING, KIND_OUTGOING, KIND_MISSED, KIND_SILENCED,
        )
    }
}

/**
 * Тип записи в интерфейсе (ТЗ §7.4).
 *
 * Разделение «заблокирован приложением» и «заблокирован системой» обязательно: иначе
 * пользователь приписывает нам блокировки встроенного блокировщика прошивки и наоборот.
 */
public object JournalKind {
    public const val BLOCKED_BY_APP: String = "BLOCKED_BY_APP"
    public const val BLOCKED_EXTERNAL: String = "BLOCKED_EXTERNAL"
    public const val SILENCED: String = "SILENCED"
    public const val INCOMING_ANSWERED: String = "INCOMING_ANSWERED"
    public const val MISSED: String = "MISSED"
    public const val REJECTED_BY_USER: String = "REJECTED_BY_USER"
    public const val OUTGOING: String = "OUTGOING"
    public const val VOICEMAIL: String = "VOICEMAIL"

    /** Проверен и пропущен нами, а исхода мы не знаем: записи зеркала для него ещё нет. */
    public const val CHECKED_ALLOWED: String = "CHECKED_ALLOWED"
    public const val UNKNOWN: String = "UNKNOWN"

    /**
     * Тип выводится из решения и записи зеркала, а не хранится.
     *
     * Порядок проверок значим: своё решение важнее системного типа. Система пишет
     * заблокированный нами звонок как `BLOCKED`, и без этого приоритета все наши блокировки
     * выглядели бы как «заблокировано чем-то ещё».
     */
    public fun of(action: String?, systemType: String?, durationSeconds: Int?): String = when {
        action == "REJECT" || action == "DROP" -> BLOCKED_BY_APP
        action == "SILENCE" -> SILENCED
        systemType == "BLOCKED" -> BLOCKED_EXTERNAL
        systemType == "OUTGOING" -> OUTGOING
        systemType == "MISSED" -> MISSED
        systemType == "REJECTED" -> REJECTED_BY_USER
        systemType == "VOICEMAIL" -> VOICEMAIL
        systemType == "INCOMING" && durationSeconds == null -> CHECKED_ALLOWED
        systemType == "INCOMING" -> INCOMING_ANSWERED
        action != null -> CHECKED_ALLOWED
        else -> UNKNOWN
    }
}
