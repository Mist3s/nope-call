package com.mist3s.nopecall.core.calllog

/** Строка системного журнала звонков в виде, независимом от Android. */
public data class CallLogRow(
    val systemId: Long,
    val dateMillis: Long,
    val number: String?,
    /** Имя, которое система сохранила при звонке. Может появиться позже самого звонка. */
    val cachedName: String?,
    /** `CallLog.Calls.TYPE`. */
    val type: Int,
    val durationSeconds: Int,
    val phoneAccountId: String?,
)

/**
 * Источник системного журнала звонков (ТЗ §7.2).
 *
 * Интерфейс, а не прямой `ContentResolver`, по той же причине, что и [CallDetailsReader]:
 * иначе синхронизацию и сшивку нельзя проверить без устройства, а это самая тонкая часть
 * журнала — там легко получить дубли и потерянные записи.
 */
public interface CallLogSource {
    /**
     * @param sinceMillis нижняя граница по времени звонка
     * @param after курсор предыдущей страницы. `null` — с начала.
     * @return страница записей в порядке возрастания пары «время, идентификатор»
     */
    public fun query(sinceMillis: Long, after: CallLogCursor?, limit: Int): List<CallLogRow>

    /** Есть ли доступ. Без него зеркало не наполняется, и интерфейс обязан это объяснять. */
    public fun isAvailable(): Boolean

    public companion object {
        /** Источника нет: разрешение не выдано. */
        public val NONE: CallLogSource = object : CallLogSource {
            override fun query(sinceMillis: Long, after: CallLogCursor?, limit: Int): List<CallLogRow> =
                emptyList()

            override fun isAvailable(): Boolean = false
        }
    }
}

/**
 * Курсор постраничного обхода: **пара**, а не одно время.
 *
 * Одного времени недостаточно: в системном журнале встречаются записи с равным `DATE`
 * (на реальном телефоне нашлись две таких пары). При строгом «новее метки» запись на границе
 * страницы выпадала молча, а при нестрогом курсор мог не сдвинуться вовсе, если вся страница
 * заполнена одним временем. Пара «время, идентификатор» строго возрастает всегда — значит
 * ни потерь, ни зацикливания.
 */
public data class CallLogCursor(val dateMillis: Long, val systemId: Long)

/**
 * Типы записей системного журнала (ТЗ §7.4).
 *
 * Хранятся строками, а не числами: числовые константы `CallLog.Calls` могут пополняться,
 * и неизвестное значение должно оставаться отличимым, а не сливаться с известными.
 */
public object CallType {
    public const val INCOMING: String = "INCOMING"
    public const val OUTGOING: String = "OUTGOING"
    public const val MISSED: String = "MISSED"
    public const val VOICEMAIL: String = "VOICEMAIL"
    public const val REJECTED: String = "REJECTED"

    /** Заблокирован — системой, нами или другим приложением. Кем именно, покажет сшивка. */
    public const val BLOCKED: String = "BLOCKED"
    public const val EXTERNAL: String = "EXTERNAL"
    public const val UNKNOWN: String = "UNKNOWN"

    /** Значения `CallLog.Calls.*_TYPE`. Продублированы, чтобы разбор тестировался без Android. */
    public fun fromSystem(type: Int): String = when (type) {
        1 -> INCOMING
        2 -> OUTGOING
        3 -> MISSED
        4 -> VOICEMAIL
        5 -> REJECTED
        6 -> BLOCKED
        7 -> EXTERNAL
        else -> UNKNOWN
    }
}
