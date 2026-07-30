package com.mist3s.nopecall.core.storage

import com.mist3s.nopecall.engine.NameCanonizer

/** Запись журнала в том виде, в каком её показывает интерфейс (ТЗ §9.2). */
public data class JournalItem(
    val id: Long,
    val occurredAt: Long,
    val rawNumber: String,
    val e164: String?,
    val nameRaw: String?,
    val nameSource: String,
    val action: String,
    val reason: String,
    val matchedRuleId: Long?,
    val matchedRuleTitle: String?,
    val latencyMs: Int,
    val degradations: Int,
    /** Была ли операторская подпись. Отдельно, потому что это ключевой показатель (ТЗ §7.7.5). */
    val hadSignature: Boolean,
) {
    public val blockedByUs: Boolean
        get() = action == "REJECT" || action == "DROP"
}

/** Страница журнала с курсором на следующую. */
public data class JournalPage(
    val items: List<JournalItem>,
    /** Курсор составной: одного времени недостаточно (архитектура §7.3). */
    val nextBeforeTime: Long?,
    val nextBeforeId: Long?,
) {
    public val hasMore: Boolean get() = nextBeforeTime != null
}

/** Сводка для главного экрана и диагностики (ТЗ §9.1, §9.7). */
public data class JournalSummary(
    val blockedToday: Int,
    val totalEvents: Int,
    val lastEventAt: Long?,
    val withSignatureLast100: Int,
    val checkedLast100: Int,
)

/**
 * Журнал звонков (ТЗ §7).
 *
 * Пока читается только слой 1 — наши события проверки. Зеркало системного журнала требует
 * `READ_CALL_LOG` и добавляется отдельно; до него журнал честно показывает лишь то, что
 * приложение знает наверняка, и интерфейс обязан это объяснять (ТЗ §7.2).
 */
public class JournalRepository(private val db: NopeCallDatabase) {

    public suspend fun page(
        beforeTime: Long? = null,
        beforeId: Long? = null,
        limit: Int = PAGE_SIZE,
    ): JournalPage {
        val rows = if (beforeTime == null) {
            db.events().recent(limit + 1)
        } else {
            db.events().page(beforeTime, beforeId ?: Long.MAX_VALUE, limit + 1)
        }
        val hasMore = rows.size > limit
        val items = rows.take(limit).map { it.toItem() }
        val last = items.lastOrNull()
        return JournalPage(
            items = items,
            nextBeforeTime = if (hasMore) last?.occurredAt else null,
            nextBeforeId = if (hasMore) last?.id else null,
        )
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
        )
    }

    /**
     * Предпросмотр правила: сколько записей журнала под него попадёт (ТЗ §9.3).
     *
     * Считается по хранимым канонизированным полям — поэтому и хранятся: SQL не умеет
     * ни транслитерировать, ни переписывать префиксы номеров (архитектура §5.4).
     *
     * Для `REGEX` и правил с вариантами транслитерации точный подсчёт запросом невозможен,
     * поэтому окно ограничивается, а интерфейс показывает «≥ N» (ТЗ §18 п. 16).
     */
    public suspend fun previewMatches(
        target: String,
        matchType: String,
        canonicalPattern: String,
        windowSize: Int = PREVIEW_WINDOW,
    ): PreviewResult {
        if (canonicalPattern.isEmpty()) return PreviewResult(0, truncated = false)

        val rows = db.events().recent(windowSize)
        val matched = rows.count { row ->
            val value = when (target) {
                "NUMBER" -> row.digits
                "NAME_ORG" -> row.orgFold.orEmpty()
                "NAME_CATEGORY" -> row.categoryFold.orEmpty()
                else -> row.nameFold.orEmpty()
            }
            val tokens = row.nameTokens.orEmpty()
            when (matchType) {
                "EXACT" -> value == canonicalPattern
                "PREFIX" -> value.startsWith(canonicalPattern)
                "SUFFIX" -> value.endsWith(canonicalPattern)
                "CONTAINS" -> value.isNotEmpty() && value.contains(canonicalPattern)
                "TOKEN" -> tokens.contains(" $canonicalPattern ")
                else -> false
            }
        }
        return PreviewResult(matched, truncated = rows.size >= windowSize)
    }

    public data class PreviewResult(val count: Int, val truncated: Boolean)

    private fun ScreeningEventEntity.toItem() = JournalItem(
        id = id,
        occurredAt = occurredAt,
        rawNumber = rawNumber,
        e164 = e164,
        nameRaw = nameRaw,
        nameSource = nameSource,
        action = action,
        reason = reason,
        matchedRuleId = matchedRuleId,
        matchedRuleTitle = matchedRuleTitle,
        latencyMs = latencyMs,
        degradations = degradations,
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

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
