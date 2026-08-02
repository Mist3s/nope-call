package com.mist3s.nopecall.core.facts

/**
 * Шов над `Call.Details` (архитектура §12.1).
 *
 * `Call.Details` не имеет публичного конструктора, а Robolectric-шэдоу для него не является
 * готовым и стабильным решением. Без этого интерфейса построение фактов о звонке пришлось бы
 * проверять только инструментальными тестами на устройстве — то есть почти никогда.
 *
 * Здесь **нет** типов Android: ни `Uri`, ни `Bundle`. Иначе тесты на голой JVM падали бы
 * на заглушках android.jar. Разбор `Uri` и дамп `extras` — забота реализации и режима
 * наблюдения соответственно.
 */
public interface CallDetailsReader {
    /** Схема обработчика: `tel`, `sip`, `voicemail`. `null`, если обработчика нет. */
    public val handleScheme: String?

    /** Значимая часть обработчика: номер для `tel`, пользователь для `sip`. */
    public val handleValue: String?

    /** `TelecomManager.PRESENTATION_*`: как система представила номер. */
    public val handlePresentation: Int

    /** Операторская подпись или имя из контактов — то, что система отдала на момент проверки. */
    public val callerDisplayName: String?

    /** `TelecomManager.PRESENTATION_*` для названия. */
    public val callerDisplayNamePresentation: Int

    /** `Call.Details.getCallerNumberVerificationStatus()`; `null` на API 29 (ТЗ §6.4). */
    public val verificationStatus: Int?

    /** Момент создания звонка в Telecom — точка отсчёта системного дедлайна (архитектура §4.3). */
    public val creationTimeMillis: Long

    /** `Call.Details.getCallDirection()`; `null` до Android 10 включительно на части прошивок. */
    public val callDirection: Int?
        get() = null

    public val connectTimeMillis: Long?
        get() = null

    /** `getAccountHandle()` в виде строки: SIM, через которую пришёл звонок. */
    public val accountHandle: String?
        get() = null

    /**
     * Сырой `Call.Details` как его печатает система, плюс поля, которые построение фактов
     * не использует (ТЗ §7.7.1).
     *
     * Нужно ровно для одного вопроса: «что вообще система нам даёт». Разобранных полей для
     * него мало — по ним не отличить «поля не было» от «поле есть, но мы его не читаем».
     * Именно этот вопрос и возник на реальном звонке: подпись `OOO SDEK-GLOBAL: dostavka`
     * отображалась на экране вызова, а в `getCallerDisplayName()` на момент проверки был `null`.
     */
    public fun rawDump(): List<com.mist3s.nopecall.core.observe.ExtraEntry> = emptyList()

    /**
     * Полный дамп `getExtras()` для режима наблюдения (ТЗ §7.7.1).
     *
     * По умолчанию пусто: построение фактов эти данные не использует, а реализация обязана
     * ловить исключение **на каждом ключе отдельно** — вендорская реализация может положить
     * в `Bundle` что угодно, включая объект, чей `toString` бросает.
     */
    public fun extrasDump(): List<com.mist3s.nopecall.core.observe.ExtraEntry> = emptyList()

    public fun intentExtrasDump(): List<com.mist3s.nopecall.core.observe.ExtraEntry> = emptyList()

    public companion object {
        // Значения TelecomManager.PRESENTATION_*. Продублированы, а не импортированы, чтобы
        // интерфейс и построение фактов оставались проверяемыми без Android.
        public const val PRESENTATION_ALLOWED: Int = 1
        public const val PRESENTATION_RESTRICTED: Int = 2
        public const val PRESENTATION_PAYPHONE: Int = 3
        public const val PRESENTATION_UNKNOWN: Int = 4
    }
}

/**
 * Проверка принадлежности номера телефонной книге (ТЗ §6.4).
 *
 * В горячем пути используется **только заранее построенный индекс**: прямой запрос
 * к `ContactsContract.PhoneLookup` из `onScreenCall` не выполняется. Синхронный
 * `ContentResolver.query()` нельзя прервать по таймауту — `CancellationSignal` кооперативен,
 * а типовой отказ это как раз холодный старт провайдера или залипший синк-адаптер, где запрос
 * висит до конца (архитектура §5.3, отступление от ТЗ §6.3).
 */
public fun interface ContactMembership {
    /**
     * @return `true`/`false` — ответ известен; `null` — индекс недоступен или устарел,
     *   и тогда решение помечается флагом `CONTACT_INDEX_STALE`.
     */
    public fun contains(e164: String?): Boolean?

    public companion object {
        /** Индекса нет: ответ неизвестен. */
        public val UNKNOWN: ContactMembership = ContactMembership { null }

        /** Ответ «не в контактах» — когда разрешение не выдано и индекс пуст по определению. */
        public val NONE: ContactMembership = ContactMembership { false }
    }
}

/** Проверка экстренного номера (ТЗ §5.4). */
public fun interface EmergencyNumbers {
    public fun isEmergency(digits: String): Boolean

    public companion object {
        /**
         * Ничего не знает. Резервный список в настройках снимка всё равно проверяется движком,
         * поэтому гарантия не исчезает целиком, даже если `TelephonyManager` недоступен.
         */
        public val NONE: EmergencyNumbers = EmergencyNumbers { false }
    }
}
