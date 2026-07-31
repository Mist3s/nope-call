package com.mist3s.nopecall.core.storage

import com.mist3s.nopecall.core.contacts.ContactNumberSource
import com.mist3s.nopecall.engine.NameCanonizer

/** Запись журнала в том виде, в каком её показывает интерфейс (ТЗ §9.2, §7.4). */
public data class JournalItem(
    /** Устойчивый в пределах страницы: `id` из своей таблицы. Пара с [sourceRank] — уникальна. */
    val id: Long,
    val sourceRank: Int,
    val occurredAt: Long,
    /** Тип записи для интерфейса: см. [JournalKind]. */
    val kind: String,
    val rawNumber: String,
    val e164: String?,
    val nameRaw: String?,
    val nameSource: String,
    /** `null` — записи зеркала не соответствует наше событие: звонок проверяли не мы. */
    val action: String?,
    val reason: String?,
    val matchedRuleId: Long?,
    val matchedRuleTitle: String?,
    val latencyMs: Int?,
    val degradations: Int?,
    /** `null` — исход неизвестен, а не «ноль секунд» (ТЗ §7.2). */
    val durationSeconds: Int?,
    val eventId: Long?,
    val systemId: Long?,
    val phoneAccountId: String?,
    /** Была ли операторская подпись. Отдельно, потому что это ключевой показатель (ТЗ §7.7.5). */
    val hadSignature: Boolean,
) {
    public val blockedByUs: Boolean
        get() = action == "REJECT" || action == "DROP"
}

/** Страница журнала с курсором на следующую. */
public data class JournalPage(
    val items: List<JournalItem>,
    /** Курсор тройной: одного времени недостаточно (архитектура §7.3). */
    val next: JournalCursor?,
) {
    public val hasMore: Boolean get() = next != null
}

/** Сводка для главного экрана и диагностики (ТЗ §9.1, §9.7). */
public data class JournalSummary(
    val blockedToday: Int,
    val totalEvents: Int,
    val lastEventAt: Long?,
    val withSignatureLast100: Int,
    val checkedLast100: Int,
    val mirrorRecords: Int,
)

/**
 * Журнал звонков (ТЗ §7).
 *
 * Читает объединение двух слоёв: собственные события проверки и зеркало системного журнала.
 * Второй слой требует `READ_CALL_LOG`; без него зеркало пусто, и журнал честно показывает
 * только то, что приложение знает наверняка — без исхода звонка, длительности и исходящих,
 * потому что `CallScreeningService` вызывается один раз, **до** звонка (ТЗ §7.2).
 */
public class JournalRepository(private val db: NopeCallDatabase) {

    public suspend fun page(
        cursor: JournalCursor? = null,
        filter: JournalFilter = JournalFilter(),
        limit: Int = PAGE_SIZE,
    ): JournalPage {
        val from = cursor ?: JournalCursor.START
        val rows = db.feed().page(
            cursorAt = from.at,
            cursorRank = from.sourceRank,
            cursorId = from.id,
            kind = filter.kind,
            digitsQuery = filter.digitsQuery?.takeIf { it.isNotEmpty() },
            // Поиск по названию идёт по свёрнутой форме: пользователь ищет «ромашка»,
            // а в подписи придёт `OOO Romashka`, и без свёртки не нашлось бы ничего.
            nameQuery = filter.nameQuery?.takeIf { it.isNotEmpty() }
                ?.let { NameCanonizer.canonizePattern(it) }?.takeIf { it.isNotEmpty() },
            signature = filter.hadSignature?.let { if (it) 1 else 0 },
            fromAt = filter.fromAt,
            toAt = filter.toAt,
            ruleId = filter.ruleId,
            sim = filter.sim,
            limit = limit + 1,
        )
        val hasMore = rows.size > limit
        val page = rows.take(limit)
        val last = page.lastOrNull()
        return JournalPage(
            items = page.map { it.toItem() },
            next = if (hasMore && last != null) {
                JournalCursor(last.at, last.sourceRank, last.id)
            } else {
                null
            },
        )
    }

    /** Какие SIM встречались в журнале — фильтр по SIM без этого показывать нечем. */
    public suspend fun sims(): List<String> = db.feed().sims()

    /** Скрыть запись зеркала локально. Системный журнал Android при этом не трогается. */
    public suspend fun hide(systemId: Long) {
        db.mirror().hide(systemId)
    }

    public suspend fun summary(now: Long): JournalSummary {
        val startOfDay = now - (now % DAY_MS)
        val recent = db.events().recent(100)
        return JournalSummary(
            blockedToday = db.events().blockedSince(startOfDay),
            totalEvents = db.events().count(),
            lastEventAt = db.events().lastEventAt(),
            withSignatureLast100 = recent.count {
                it.nameSource == "CNAP" || it.nameSource == "CNAP_OPERATOR_LABEL"
            },
            checkedLast100 = recent.size,
            mirrorRecords = db.mirror().count(),
        )
    }

    /**
     * Ретеншен (ТЗ §7.6): по сроку и по числу записей, что раньше.
     *
     * Оба ограничения нужны вместе. Срок без числа не спасает от телефона, на который звонят
     * сто раз в день; число без срока оставляет годовой хвост на редко используемом телефоне.
     *
     * @return сколько записей удалено
     */
    public suspend fun applyRetention(
        now: Long,
        keepDays: Int = RETENTION_DAYS,
        keepRecords: Int = RETENTION_RECORDS,
    ): Int {
        var removed = 0
        if (keepDays > 0) {
            val before = now - keepDays * DAY_MS
            removed += db.events().deleteOlderThan(before)
            removed += db.mirror().deleteOlderThan(before)
        }
        if (keepRecords > 0) removed += db.events().trimTo(keepRecords)
        return removed
    }

    /**
     * Очистка журнала (ТЗ §7.6).
     *
     * Удаляет только собственные данные. Системный журнал вызовов Android не затрагивается —
     * и интерфейс обязан сказать это прямо, иначе пользователь ждёт другого.
     */
    public suspend fun clear(): Int = db.events().deleteAll() + db.mirror().deleteAll()

    /**
     * Предпросмотр правила: три разные величины (ТЗ §9.3, критерий приёмки §18 п. 16).
     *
     * 1. [PreviewResult.count] — сколько **записей журнала** под правило попадёт. Считается
     *    по хранимым канонизированным полям — поэтому они и хранятся: SQL не умеет
     *    ни транслитерировать, ни переписывать префиксы номеров (архитектура §5.4).
     *    Для `REGEX` и правил с вариантами транслитерации точный подсчёт запросом невозможен,
     *    поэтому окно ограничивается, а интерфейс показывает «≥ N».
     * 2. [PreviewResult.allowRulesCovered] — сколько **разрешающих правил** новое правило
     *    перекрывает. Приближение, см. [countAllowRulesCovered].
     * 3. [PreviewResult.contactsCovered] — сколько **номеров телефонной книги** под правило
     *    попадёт, см. [contactPreview].
     *
     * Две последние величины отвечают на вопрос, который по журналу не отвечается: журнал
     * говорит «столько таких звонков уже было», а не «столько своих ты сейчас отрежешь».
     * Номер, по которому ещё ни разу не звонили, в журнале отсутствует, но в книге есть, —
     * и именно он делает жалобу «заблокировали врача» возможной.
     *
     * @param contacts источник номеров книги. По умолчанию «не знаю»: `:core` не решает
     *   за вызывающего, есть ли у него `Context` и разрешение (ТЗ §1.1).
     */
    public suspend fun previewMatches(
        target: String,
        matchType: String,
        canonicalPattern: String,
        contacts: ContactNumberSource = ContactNumberSource.UNAVAILABLE,
        windowSize: Int = PREVIEW_WINDOW,
        contactLimit: Int = CONTACT_PREVIEW_LIMIT,
    ): PreviewResult {
        val allowRules = countAllowRulesCovered(target, matchType, canonicalPattern)
        val contactsPreview =
            contactPreview(target, matchType, canonicalPattern, contacts, contactLimit)

        // Пустой канонический шаблон — это правило «номер в телефонной книге» (`IN_CONTACTS`):
        // сопоставлять по журналу нечем, потому что принадлежность книге в событии не хранится.
        // Показатель по книге при этом осмыслен и уже посчитан, поэтому выход не досрочный,
        // а с обеими новыми величинами.
        if (canonicalPattern.isEmpty()) {
            return PreviewResult(
                count = 0,
                truncated = false,
                allowRulesCovered = allowRules,
                contactsCovered = contactsPreview.count,
                contactsTruncated = contactsPreview.truncated,
                contactsState = contactsPreview.state,
            )
        }

        val rows = db.events().recent(windowSize)
        var matched = rows.count { row ->
            val value = when (target) {
                "NUMBER" -> row.digits
                "NAME_ORG" -> row.orgFold.orEmpty()
                "NAME_CATEGORY" -> row.categoryFold.orEmpty()
                else -> row.nameFold.orEmpty()
            }
            matches(value, row.nameTokens.orEmpty(), matchType, canonicalPattern)
        }

        // Записи зеркала, которым нашего события не нашлось, тоже видны в журнале — значит
        // и в подсчёте они обязаны быть, иначе предпросмотр противоречит тому, что на экране.
        // Только для правил по номеру: названия в зеркале нет ни в токенах, ни по частям,
        // и считать по нему «содержит слово» было бы догадкой.
        var mirrorSize = 0
        if (target == "NUMBER") {
            val mirror = db.mirror().digitsForPreview(windowSize)
            mirrorSize = mirror.size
            matched += mirror.count { matches(it, "", matchType, canonicalPattern) }
        }

        return PreviewResult(
            count = matched,
            truncated = rows.size >= windowSize || mirrorSize >= windowSize,
            allowRulesCovered = allowRules,
            contactsCovered = contactsPreview.count,
            contactsTruncated = contactsPreview.truncated,
            contactsState = contactsPreview.state,
        )
    }

    /**
     * Сколько разрешающих правил перекрывает новое правило (критерий приёмки §18 п. 16).
     *
     * **Это приближение, и точнее посчитать нельзя.** Разрешающее правило — не номер, а шаблон,
     * то есть множество номеров, обычно бесконечное. Вопрос «пересекаются ли два шаблона»
     * в общем виде не арифметический: для `REGEX` это задача о пересечении языков, а её
     * результат ещё и не сводится к числу правил. Поэтому считается ровно то, что проверяемо:
     * **новое правило применяется к каноническому шаблону разрешающего** (и к его вариантам
     * написания — иначе правило, ловящее вариант транслитерации, выглядело бы безобидным).
     *
     * Из этого следуют границы показателя, и их надо назвать вслух:
     *  * приближение **занижает**. Новое `EXACT 74951234567` против разрешающего `PREFIX 7495`
     *    даст ноль, хотя множества пересекаются: сравнение идёт в одну сторону, как задумано —
     *    в обратную оно превратилось бы в предположение о том, чего пользователь не вводил;
     *  * разрешающие правила с `REGEX` пропускаются: применить «начинается с 7495» к тексту
     *    регулярного выражения — значит сравнивать себя с исходным кодом правила, а не с
     *    множеством номеров;
     *  * `TOKEN` сравнивается с шаблоном разрешающего правила целиком: у канонической формы
     *    названия нет пробелов, и слов в ней не видно.
     *
     * `null` — «не знаю»: для `REGEX` и `IN_CONTACTS` показатель не вычисляется вообще, и ноль
     * тут читался бы как «своих не тронет» (ТЗ §1.1).
     *
     * Порядок правил не учитывается **намеренно**: у нового правила `orderIndex` ещё нет, оно
     * не сохранено. По умолчанию разрешающие правила стоят выше блокирующих и пересечение их
     * не отменит, но порядок пользователь меняет руками — а главное, пересечение само по себе
     * означает, что правило шире, чем автор думал.
     */
    private suspend fun countAllowRulesCovered(
        target: String,
        matchType: String,
        canonicalPattern: String,
    ): Int? {
        if (canonicalPattern.isEmpty()) return null
        if (matchType == "REGEX" || matchType == "IN_CONTACTS") return null
        val domain = patternDomainOf(target) ?: return null

        return db.rules().allowing().count { rule ->
            // Правила по номеру и правила по названию сравниваются только между собой:
            // канонический номер и свёрнутое название живут в разных алфавитах, и совпадение
            // между ними было бы случайным.
            patternDomainOf(rule.targetType) == domain &&
                rule.matchType != "REGEX" && rule.matchType != "IN_CONTACTS" &&
                allowPatternsOf(rule).any { pattern ->
                    matches(pattern, " $pattern ", matchType, canonicalPattern)
                }
        }
    }

    /** Канонический шаблон разрешающего правила и его варианты написания, без пустых. */
    private fun allowPatternsOf(rule: RuleEntity): List<String> =
        (listOf(rule.patternCanonical) + rule.patternVariants.split('\n'))
            .filter { it.isNotEmpty() }

    /**
     * Показатель «сколько номеров книги зацепит правило» (критерий приёмки §18 п. 16).
     *
     * Книга читается только когда показатель осмыслен, и это не оптимизация: обращаться
     * к книге ради правила по названию — значит запрашивать данные, которые заведомо
     * не участвуют в ответе.
     *
     * Правила по названию дают `null`, а не ноль. Соблазн ответить нулём есть: правило по
     * названию действительно не сопоставляется с цифрами. Но зацепить контакт оно может —
     * если оператор пришлёт подпись, попадающую под шаблон, — а название контакта из книги
     * в решении не участвует вовсе (в фактах звонка есть только подпись). Сравнивать шаблон
     * с именем контакта означало бы догадываться, какую подпись пришлёт оператор, то есть
     * эвристику, которых в этой кодовой базе нет. «Не знаю» — единственный честный ответ.
     *
     * Сопоставление идёт по **всем видам** номера контакта, как в движке (`8495…`, `+7495…`,
     * национальная часть), а не по одной канонической форме, как в подсчёте по журналу.
     * Разница не произвол: в журнале хранится только каноническая форма — индексируемый
     * столбец, — а у номера из книги на руках есть весь разбор, и занижать показатель,
     * имея данные, было бы хуже.
     */
    private fun contactPreview(
        target: String,
        matchType: String,
        canonicalPattern: String,
        contacts: ContactNumberSource,
        limit: Int,
    ): ContactPreview {
        // Правило «номер в телефонной книге» попадает под всю книгу — без сопоставления
        // шаблонов: именно этот случай показатель и обязан поймать, потому что такое правило
        // с блокирующим действием отрезает всех своих сразу.
        val everyContact = matchType == "IN_CONTACTS"
        val byNumber = target == "NUMBER" &&
            canonicalPattern.isNotEmpty() &&
            matchType in NUMBER_MATCH_TYPES
        // Правило по названию звонящего к телефонной книге отношения не имеет: у контакта
        // есть номер, а не операторская подпись. Это «неприменимо», а не «не смогли проверить».
        if (!everyContact && !byNumber) return ContactPreview.NOT_APPLICABLE

        val numbers = contacts.numbers(limit) ?: return ContactPreview.NO_ACCESS
        return ContactPreview(
            count = if (everyContact) {
                numbers.size
            } else {
                numbers.count { forms ->
                    forms.candidates.any { matches(it, "", matchType, canonicalPattern) }
                }
            },
            // Предел достигнут — показатель нижняя граница. Молча выдать его за точное число
            // нельзя: «ровно 3 контакта» и «не меньше 3» пользователь читает по-разному.
            truncated = numbers.size >= limit,
            state = ContactsState.COUNTED,
        )
    }

    /**
     * Область, в которой шаблоны сопоставимы. `null` — шаблона у цели нет (`CONTACT`).
     *
     * `NAME`, `NAME_ORG` и `NAME_CATEGORY` в одной области: все три канонизируются в одну
     * и ту же свёрнутую форму, и правило по всей подписи вполне перекрывает правило
     * по наименованию организации.
     */
    private fun patternDomainOf(target: String): String? = when (target) {
        "NUMBER" -> "NUMBER"
        "NAME", "NAME_ORG", "NAME_CATEGORY" -> "NAME"
        else -> null
    }

    private fun matches(
        value: String,
        tokens: String,
        matchType: String,
        canonicalPattern: String,
    ): Boolean = when (matchType) {
        "EXACT" -> value == canonicalPattern
        "PREFIX" -> value.startsWith(canonicalPattern)
        "SUFFIX" -> value.endsWith(canonicalPattern)
        "CONTAINS" -> value.isNotEmpty() && value.contains(canonicalPattern)
        "TOKEN" -> tokens.contains(" $canonicalPattern ")
        else -> false
    }

    /**
     * Результат предпросмотра (ТЗ §9.3, критерий приёмки §18 п. 16).
     *
     * Три величины, а не одна сумма: «столько звонков уже было», «столько своих правил
     * перекроется» и «столько номеров книги зацепится» — разные утверждения, и складывать
     * их нельзя. `null` в двух последних — «не смогли проверить», а не «ноль».
     */
    public data class PreviewResult(
        val count: Int,
        val truncated: Boolean,
        /** Перекрытых разрешающих правил. Приближение, см. `countAllowRulesCovered`. */
        val allowRulesCovered: Int? = null,
        /** Номеров телефонной книги. Осмысленно только при [ContactsState.COUNTED]. */
        val contactsCovered: Int? = null,
        /** Книга прочитана не до конца: [contactsCovered] — нижняя граница, «≥ N». */
        val contactsTruncated: Boolean = false,
        /** Что вообще произошло с проверкой книги. См. [ContactsState]. */
        val contactsState: ContactsState = ContactsState.NOT_APPLICABLE,
    )

    /**
     * Итог проверки телефонной книги.
     *
     * Состояние, а не один `null` на все случаи. Раньше `contactsCovered = null` значило
     * и «доступа к книге нет», и «к такому правилу показатель не применяется», и интерфейс
     * печатал первое всегда: у правила по операторской подписи он сообщал «нет доступа
     * к телефонной книге» при выданном разрешении. «Не знаю» и «неприменимо» — разные
     * утверждения, и различать их обязан источник, а не тот, кто рисует текст.
     */
    public enum class ContactsState {
        /** Книга прочитана, [PreviewResult.contactsCovered] — число попавших номеров. */
        COUNTED,

        /** Правило не про номера: телефонная книга к нему отношения не имеет. */
        NOT_APPLICABLE,

        /** Правило про номера, но книга недоступна: нет `READ_CONTACTS`. */
        NO_ACCESS,
    }

    /** Внутренний результат прохода по книге: число, усечение и состояние ходят только вместе. */
    private data class ContactPreview(
        val count: Int?,
        val truncated: Boolean,
        val state: ContactsState,
    ) {
        companion object {
            val NOT_APPLICABLE =
                ContactPreview(null, truncated = false, state = ContactsState.NOT_APPLICABLE)
            val NO_ACCESS =
                ContactPreview(null, truncated = false, state = ContactsState.NO_ACCESS)
        }
    }

    private fun JournalFeedRow.toItem() = JournalItem(
        id = id,
        sourceRank = sourceRank,
        occurredAt = at,
        kind = JournalKind.of(action, systemType, durationSeconds),
        rawNumber = rawNumber,
        e164 = e164,
        nameRaw = name,
        nameSource = nameSource,
        action = action,
        reason = reason,
        matchedRuleId = matchedRuleId,
        matchedRuleTitle = matchedRuleTitle,
        latencyMs = latencyMs,
        degradations = degradations,
        durationSeconds = durationSeconds,
        eventId = eventId,
        systemId = systemId,
        phoneAccountId = phoneAccountId,
        hadSignature = nameSource == "CNAP" || nameSource == "CNAP_OPERATOR_LABEL",
    )

    /** Канонизация введённого пользователем шаблона названия — для предпросмотра. */
    public fun canonizeNamePattern(pattern: String): String = NameCanonizer.canonizePattern(pattern)

    public companion object {
        public const val PAGE_SIZE: Int = 50

        /**
         * Окно предпросмотра. Ограничено намеренно: перебирать сотни тысяч записей ради
         * подсказки в редакторе незачем, а показать «≥ N» честнее, чем ждать.
         */
        public const val PREVIEW_WINDOW: Int = 500

        /**
         * Сколько номеров книги читать максимум.
         *
         * Предел на порядок больше правдоподобной адресной книги: он существует не ради
         * скорости, а как страховка от провайдера, который отдаёт неожиданно много строк
         * (объединённые аккаунты, синхронизация мессенджеров). Достижение предела не
         * замалчивается — показатель помечается как нижняя граница.
         */
        public const val CONTACT_PREVIEW_LIMIT: Int = 20_000

        /** Типы сопоставления, применимые к номеру из книги: остальным сравнивать нечего. */
        private val NUMBER_MATCH_TYPES = setOf("EXACT", "PREFIX", "SUFFIX", "CONTAINS")

        /** Значения по умолчанию из ТЗ §7.6. Настраиваются, поэтому и параметры, а не константы. */
        public const val RETENTION_DAYS: Int = 365
        public const val RETENTION_RECORDS: Int = 20_000

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
