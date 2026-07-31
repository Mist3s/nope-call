package com.mist3s.nopecall.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

@Dao
public interface RuleDao {

    @Query("SELECT * FROM block_rules ORDER BY orderIndex ASC")
    public suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM block_rules WHERE isEnabled = 1 ORDER BY orderIndex ASC")
    public suspend fun enabled(): List<RuleEntity>

    @Query("SELECT * FROM block_rules WHERE id = :id")
    public suspend fun byId(id: Long): RuleEntity?

    @Insert
    public suspend fun insert(rule: RuleEntity): Long

    @Update
    public suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM block_rules WHERE id = :id")
    public suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM block_rules WHERE isEnabled = 1")
    public suspend fun enabledCount(): Int

    @Query("SELECT MAX(orderIndex) FROM block_rules WHERE orderIndex BETWEEN :from AND :to")
    public suspend fun maxOrderIndexInRange(from: Int, to: Int): Int?

    @Query("UPDATE block_rules SET orderIndex = :orderIndex, updatedAt = :now WHERE id = :id")
    public suspend fun setOrderIndex(id: Long, orderIndex: Int, now: Long)

    /**
     * Перенумерация в два прохода внутри одной транзакции.
     *
     * Первый проход уводит значения в заведомо свободный диапазон. Без него любое пересечение
     * со старой нумерацией дало бы конфликт — в SQLite уникальность проверяется построчно,
     * и отложить её нельзя (архитектура §5.4). Даже без `UNIQUE` в схеме порядок должен быть
     * согласован на каждом шаге, иначе промежуточное состояние даёт неверные решения.
     */
    @Transaction
    public suspend fun reorder(idsInOrder: List<Long>, weightBase: Int, now: Long) {
        idsInOrder.forEachIndexed { index, id ->
            setOrderIndex(id, PARKING_OFFSET + index, now)
        }
        idsInOrder.forEachIndexed { index, id ->
            setOrderIndex(id, weightBase + index * ORDER_STEP, now)
        }
    }

    @Query(
        """
        UPDATE block_rules
        SET matchCount = matchCount + 1, lastMatchedAt = :now, errorCount = 0, lastError = NULL
        WHERE id = :id
        """
    )
    public suspend fun recordMatch(id: Long, now: Long)

    @Query(
        """
        UPDATE block_rules
        SET errorCount = errorCount + 1, lastError = :error,
            isEnabled = CASE WHEN errorCount + 1 >= :disableAfter THEN 0 ELSE isEnabled END
        WHERE id = :id
        """
    )
    public suspend fun recordError(id: Long, error: String, disableAfter: Int)

    /**
     * Включённые разрешающие правила — для предпросмотра «сколько своих зацепит новое правило»
     * (ТЗ §9.3, критерий приёмки §18 п. 16).
     *
     * Только включённые: выключенное правило никого не пропускает, и предупреждать о его
     * перекрытии значило бы поднимать тревогу там, где ничего не изменится. Альтернатива —
     * считать все разрешающие правила — отклонена именно поэтому.
     *
     * Отдаётся целиком, а не проекцией: разрешающих правил единицы, а показатель считается
     * по `patternCanonical` **и** по `patternVariants`, то есть проекция всё равно вышла бы
     * почти полной строкой.
     */
    @Query(
        """
        SELECT * FROM block_rules
        WHERE action = 'ALLOW' AND isEnabled = 1
        ORDER BY orderIndex ASC
        """
    )
    public suspend fun allowing(): List<RuleEntity>

    /** Пересчёт канонизации при смене алгоритма — синхронно и до сборки снимка (§6.2.2 ТЗ). */
    @Query("SELECT * FROM block_rules WHERE canonVersion != :version")
    public suspend fun withStaleCanon(version: Int): List<RuleEntity>

    /** Названия правил по идентификаторам — для подстановки в записи журнала. */
    @Query("SELECT id, title FROM block_rules WHERE id IN (:ids)")
    public suspend fun titles(ids: List<Long>): List<RuleTitle>

    public companion object {
        /** Шаг разреженной нумерации: вставка между соседями не требует перенумерации. */
        public const val ORDER_STEP: Int = 1024

        /** Заведомо свободный диапазон для первого прохода перенумерации. */
        internal const val PARKING_OFFSET: Int = 1_000_000_000
    }
}

@Dao
public interface SettingsDao {
    @Query("SELECT * FROM app_settings")
    public suspend fun all(): List<SettingEntity>

    @Query("SELECT value FROM app_settings WHERE key = :key")
    public suspend fun get(key: String): String?

    @Upsert
    public suspend fun put(setting: SettingEntity)
}

@Dao
public interface ScreeningEventDao {

    @Insert
    public suspend fun insert(event: ScreeningEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertAll(events: List<ScreeningEventEntity>)

    @Query("SELECT * FROM screening_events ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<ScreeningEventEntity>

    /**
     * Курсор всегда составной: `(occurredAt, id)`.
     *
     * Значения времени в миллисекундах совпадают чаще, чем кажется — пакетная вставка при
     * первичной выгрузке зеркала даёт одинаковые метки у соседних записей, и курсор по одному
     * полю пропускал бы или дублировал строки на границе страницы (архитектура §7.3).
     */
    @Query(
        """
        SELECT * FROM screening_events
        WHERE (occurredAt < :beforeTime) OR (occurredAt = :beforeTime AND id < :beforeId)
        ORDER BY occurredAt DESC, id DESC LIMIT :limit
        """
    )
    public suspend fun page(beforeTime: Long, beforeId: Long, limit: Int): List<ScreeningEventEntity>

    @Query("SELECT COUNT(*) FROM screening_events WHERE action IN ('REJECT','DROP') AND occurredAt >= :since")
    public suspend fun blockedSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM screening_events")
    public suspend fun count(): Int

    @Query("SELECT MAX(occurredAt) FROM screening_events")
    public suspend fun lastEventAt(): Long?

    /** Доля звонков с операторской подписью — показатель для диагностики (ТЗ §7.7.5). */
    @Query("SELECT COUNT(*) FROM screening_events WHERE nameSource IN ('CNAP','CNAP_OPERATOR_LABEL') AND occurredAt >= :since")
    public suspend fun withSignatureSince(since: Long): Int

    @Query("DELETE FROM screening_events WHERE occurredAt < :before")
    public suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM screening_events")
    public suspend fun deleteAll(): Int

    /**
     * Оставить только `keep` последних записей (ТЗ §7.6).
     *
     * Ограничение по числу нужно вместе со сроком, а не вместо: за месяц можно получить
     * и десять записей, и десять тысяч, и «12 месяцев» ни того, ни другого не ограничивает.
     */
    @Query(
        """
        DELETE FROM screening_events WHERE id NOT IN (
            SELECT id FROM screening_events ORDER BY occurredAt DESC, id DESC LIMIT :keep
        )
        """
    )
    public suspend fun trimTo(keep: Int): Int

    @Query("UPDATE screening_events SET matchedSystemId = :systemId WHERE id = :eventId")
    public suspend fun attachSystemId(eventId: Long, systemId: Long)

    // --- сводка режима наблюдения (ТЗ §7.7.5) -------------------------------------------------
    //
    // Агрегаты запросами, а не разбором сегментов JSONL: экран сводки открывают часто,
    // а читать ради него десятки мегабайт JSON — значит сделать его бесполезным.

    @Query("SELECT COUNT(*) FROM screening_events WHERE occurredAt >= :since")
    public suspend fun countSince(since: Long): Int

    /** Разбивка по причинам решения — счётчики деградаций для диагностики (ТЗ §9.7). */
    @Query(
        """
        SELECT reason AS bucket, COUNT(*) AS total FROM screening_events
        WHERE occurredAt >= :since GROUP BY reason ORDER BY total DESC
        """
    )
    public suspend fun byReason(since: Long): List<Bucket>

    @Query(
        """
        SELECT nameSource AS bucket, COUNT(*) AS total FROM screening_events
        WHERE occurredAt >= :since GROUP BY nameSource ORDER BY total DESC
        """
    )
    public suspend fun byNameSource(since: Long): List<Bucket>

    @Query(
        """
        SELECT COALESCE(networkType, 'UNKNOWN') AS bucket, COUNT(*) AS total FROM screening_events
        WHERE occurredAt >= :since GROUP BY networkType ORDER BY total DESC
        """
    )
    public suspend fun byNetworkType(since: Long): List<Bucket>

    @Query(
        """
        SELECT
            CASE volte WHEN 1 THEN 'VOLTE' WHEN 0 THEN 'NO_VOLTE' ELSE 'UNKNOWN' END AS bucket,
            COUNT(*) AS total
        FROM screening_events WHERE occurredAt >= :since GROUP BY volte ORDER BY total DESC
        """
    )
    public suspend fun byVolte(since: Long): List<Bucket>

    /** Задержки решения по возрастанию — из них считаются p50/p95/max. */
    @Query("SELECT latencyMs FROM screening_events WHERE occurredAt >= :since ORDER BY latencyMs ASC")
    public suspend fun latencies(since: Long): List<Int>

    @Query("SELECT COUNT(*) FROM screening_events WHERE occurredAt >= :since AND coldStart = 1")
    public suspend fun coldStartsSince(since: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM screening_events
        WHERE occurredAt >= :since AND (degradations & :watchdogBit) != 0
        """
    )
    public suspend fun watchdogSince(since: Long, watchdogBit: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM screening_events
        WHERE occurredAt >= :since AND presentation IN ('RESTRICTED','UNKNOWN','PAYPHONE')
        """
    )
    public suspend fun hiddenNumbersSince(since: Long): Int

    /** Доля подписей, досланных **после** решения — ключевой показатель §21 п. 4. */
    @Query(
        """
        SELECT COUNT(*) FROM screening_events
        WHERE occurredAt >= :since AND nameSource = 'SYSTEM_LOG'
        """
    )
    public suspend fun lateNamesSince(since: Long): Int

    @Query(
        """
        SELECT extrasKeys FROM screening_events
        WHERE occurredAt >= :since AND extrasKeys IS NOT NULL AND extrasKeys <> ''
        ORDER BY occurredAt DESC LIMIT :limit
        """
    )
    public suspend fun extrasKeys(since: Long, limit: Int): List<String>

    /**
     * Примеры реальных подписей дословно, с их свёрнутой формой рядом (ТЗ §7.7.5).
     *
     * Именно дословно: смысл в том, чтобы сразу видеть и формат оператора, и что с ним
     * сделала канонизация. Группировка по `nameRaw` убирает повторы одного и того же юрлица.
     */
    @Query(
        """
        SELECT nameRaw AS raw, nameFold AS fold, COUNT(*) AS total, MAX(occurredAt) AS lastAt
        FROM screening_events
        WHERE occurredAt >= :since AND nameRaw IS NOT NULL AND nameRaw <> ''
          AND nameSource IN ('CNAP','CNAP_OPERATOR_LABEL')
        GROUP BY nameRaw ORDER BY total DESC, lastAt DESC LIMIT :limit
        """
    )
    public suspend fun signatureSamples(since: Long, limit: Int): List<SignatureSample>

    @Query(
        """
        UPDATE screening_events
        SET nameRaw = :nameRaw, nameFold = :nameFold, nameSource = 'SYSTEM_LOG'
        WHERE id = :eventId AND (nameRaw IS NULL OR nameRaw = '')
        """
    )
    public suspend fun attachLateName(eventId: Long, nameRaw: String, nameFold: String)
}

@Dao
public interface CallLogMirrorDao {

    /**
     * Вставка через `ON CONFLICT ... DO UPDATE`, а НЕ `INSERT OR IGNORE`.
     *
     * Система дописывает запись после звонка: длительность появляется по завершении, имя может
     * заполниться позже, тип может измениться. При игнорировании конфликта ни перекрытие в 24
     * часа, ни цель «названия, ставшие известными позже» не работали бы вообще (ТЗ §7.2).
     *
     * `hiddenLocally` не трогается: скрытая пользователем запись не должна воскресать.
     */
    @Query(
        """
        INSERT INTO call_log_mirror
            (systemId, startedAt, rawNumber, digits, e164, name, nameFold, type,
             durationSeconds, phoneAccountId, hiddenLocally, syncedAt, canonVersion)
        VALUES (:systemId, :startedAt, :rawNumber, :digits, :e164, :name, :nameFold, :type,
                :durationSeconds, :phoneAccountId, 0, :syncedAt, :canonVersion)
        ON CONFLICT(systemId) DO UPDATE SET
            name = COALESCE(excluded.name, name),
            nameFold = COALESCE(excluded.nameFold, nameFold),
            durationSeconds = excluded.durationSeconds,
            type = excluded.type,
            startedAt = excluded.startedAt,
            syncedAt = excluded.syncedAt
        """
    )
    public suspend fun upsert(
        systemId: Long,
        startedAt: Long,
        rawNumber: String,
        digits: String,
        e164: String?,
        name: String?,
        nameFold: String?,
        type: String,
        durationSeconds: Int,
        phoneAccountId: String?,
        syncedAt: Long,
        canonVersion: Int,
    )

    @Query("SELECT MAX(startedAt) FROM call_log_mirror")
    public suspend fun watermark(): Long?

    @Query("SELECT * FROM call_log_mirror WHERE systemId = :systemId")
    public suspend fun bySystemId(systemId: Long): CallLogMirrorEntity?

    /**
     * Поиск записи зеркала для сшивки с событием проверки (ТЗ §7.3).
     *
     * Два условия обязательны и оба выведены из реальных дефектов:
     *  * **пустой `digits` не сшивается ни с чем** — у скрытого номера он пуст, и без этого
     *    условия любое такое событие сошлось бы с произвольной записью в окне ±20 с;
     *  * **уже занятая запись исключается** — иначе два звонка с одного номера в пределах окна
     *    сошлись бы с одной записью, а дедупликация затем скрыла бы одно из них, и запись
     *    пропала бы из журнала.
     */
    @Query(
        """
        SELECT * FROM call_log_mirror
        WHERE :digits <> '' AND digits = :digits
          AND ABS(startedAt - :occurredAt) <= :windowMs
          AND type IN ('BLOCKED','INCOMING','MISSED','REJECTED')
          AND systemId NOT IN (
              SELECT matchedSystemId FROM screening_events WHERE matchedSystemId IS NOT NULL
          )
        ORDER BY ABS(startedAt - :occurredAt) ASC
        LIMIT 1
        """
    )
    public suspend fun findForStitching(
        digits: String,
        occurredAt: Long,
        windowMs: Long,
    ): CallLogMirrorEntity?

    @Query("UPDATE call_log_mirror SET hiddenLocally = 1 WHERE systemId = :systemId")
    public suspend fun hide(systemId: Long)

    @Query("SELECT COUNT(*) FROM call_log_mirror")
    public suspend fun count(): Int

    @Query("DELETE FROM call_log_mirror WHERE startedAt < :before")
    public suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM call_log_mirror")
    public suspend fun deleteAll(): Int

    /**
     * Номера входящих записей зеркала, которым не соответствует наше событие — для
     * предпросмотра правила (ТЗ §9.3).
     *
     * Без них предпросмотр говорил «в журнале нет подходящих записей», хотя в журнале эти
     * записи прямо видны: он считал только собственные проверки. Расхождение между тем,
     * что показано, и тем, что посчитано, читается как ошибка подсчёта.
     *
     * Исходящие исключены: правило к ним не применяется никогда. Сшитые исключены, чтобы
     * не считать один звонок дважды.
     */
    @Query(
        """
        SELECT digits FROM call_log_mirror
        WHERE hiddenLocally = 0
          AND digits <> ''
          AND type IN ('INCOMING','MISSED','BLOCKED','REJECTED')
          AND systemId NOT IN (
              SELECT matchedSystemId FROM screening_events WHERE matchedSystemId IS NOT NULL
          )
        ORDER BY startedAt DESC LIMIT :limit
        """
    )
    public suspend fun digitsForPreview(limit: Int): List<String>
}

/**
 * Объединённая поверхность журнала (ТЗ §7.3, §7.5).
 *
 * Единственное место, где склеиваются два слоя. Отдельный DAO, а не метод в одном из двух:
 * запрос принадлежит обеим таблицам, и прятать его в один из слоёв значило бы делать вид,
 * что слой знает про соседний.
 */
@Dao
public interface JournalFeedDao {

    /**
     * Страница журнала: все записи зеркала плюс наши события без сшивки.
     *
     * Три вещи в этом запросе неочевидны и все три обязательны.
     *
     * **Границы по времени продублированы внутрь обеих половин объединения.** Снаружи их
     * недостаточно: SQLite не проталкивает условие в `UNION ALL` через подзапрос, и индексы
     * `screening_events(occurredAt)` / `call_log_mirror(startedAt)` перестали бы работать —
     * каждая страница читала бы обе таблицы целиком.
     *
     * **Тип записи для события без зеркала выводится из решения.** Иначе фильтр «входящие»
     * не показывал бы ничего, пока не выдан `READ_CALL_LOG`: у события системного типа нет,
     * а звонок при этом был именно входящим.
     *
     * **Длительность у события без зеркала остаётся `NULL`.** Это не «ноль секунд», а «исход
     * неизвестен»: сервис проверки вызывается до звонка и не узнаёт, чем он кончился.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT
                0 AS sourceRank,
                m.systemId AS id,
                m.startedAt AS at,
                e.id AS eventId,
                m.systemId AS systemId,
                m.rawNumber AS rawNumber,
                m.digits AS digits,
                m.e164 AS e164,
                COALESCE(e.nameRaw, m.name) AS name,
                COALESCE(e.nameFold, m.nameFold) AS nameFold,
                COALESCE(
                    e.nameSource,
                    CASE WHEN m.name IS NOT NULL THEN 'SYSTEM_LOG' ELSE 'NONE' END
                ) AS nameSource,
                e.action AS decisionAction,
                e.reason AS reason,
                e.matchedRuleId AS matchedRuleId,
                e.matchedRuleTitle AS matchedRuleTitle,
                e.latencyMs AS latencyMs,
                e.degradations AS degradations,
                m.durationSeconds AS durationSeconds,
                m.type AS systemType,
                COALESCE(e.phoneAccountId, m.phoneAccountId) AS phoneAccountId
            FROM call_log_mirror m
            LEFT JOIN screening_events e ON e.matchedSystemId = m.systemId
            WHERE m.hiddenLocally = 0
              AND m.startedAt <= :cursorAt
              AND (:fromAt IS NULL OR m.startedAt >= :fromAt)
              AND (:toAt IS NULL OR m.startedAt <= :toAt)
            UNION ALL
            SELECT
                1, e.id, e.occurredAt, e.id, NULL,
                e.rawNumber, e.digits, e.e164, e.nameRaw, e.nameFold, e.nameSource,
                e.action, e.reason, e.matchedRuleId, e.matchedRuleTitle, e.latencyMs,
                e.degradations, NULL,
                CASE WHEN e.action IN ('REJECT','DROP') THEN 'BLOCKED' ELSE 'INCOMING' END,
                e.phoneAccountId
            FROM screening_events e
            WHERE e.matchedSystemId IS NULL
              AND e.occurredAt <= :cursorAt
              AND (:fromAt IS NULL OR e.occurredAt >= :fromAt)
              AND (:toAt IS NULL OR e.occurredAt <= :toAt)
        )
        WHERE (
                at < :cursorAt
                OR (at = :cursorAt AND sourceRank > :cursorRank)
                OR (at = :cursorAt AND sourceRank = :cursorRank AND id < :cursorId)
              )
          AND (
                :kind = 'ALL'
                OR (:kind = 'BLOCKED_BY_US' AND decisionAction IN ('REJECT','DROP'))
                OR (:kind = 'BLOCKED_ANY' AND (decisionAction IN ('REJECT','DROP') OR systemType = 'BLOCKED'))
                OR (:kind = 'INCOMING' AND systemType = 'INCOMING')
                OR (:kind = 'OUTGOING' AND systemType = 'OUTGOING')
                OR (:kind = 'MISSED' AND systemType = 'MISSED')
                OR (:kind = 'SILENCED' AND decisionAction = 'SILENCE')
              )
          AND (:digitsQuery IS NULL OR digits LIKE '%' || :digitsQuery || '%')
          AND (:nameQuery IS NULL OR nameFold LIKE '%' || :nameQuery || '%')
          AND (
                :signature IS NULL
                OR (CASE WHEN nameSource IN ('CNAP','CNAP_OPERATOR_LABEL') THEN 1 ELSE 0 END) = :signature
              )
          AND (:ruleId IS NULL OR matchedRuleId = :ruleId)
          AND (:sim IS NULL OR phoneAccountId = :sim)
        ORDER BY at DESC, sourceRank ASC, id DESC
        LIMIT :limit
        """
    )
    public suspend fun page(
        cursorAt: Long,
        cursorRank: Int,
        cursorId: Long,
        kind: String,
        digitsQuery: String?,
        nameQuery: String?,
        signature: Int?,
        fromAt: Long?,
        toAt: Long?,
        ruleId: Long?,
        sim: String?,
        limit: Int,
    ): List<JournalFeedRow>

    /**
     * Какие SIM встречались в журнале — для фильтра по SIM (ТЗ §7.5).
     *
     * Из обоих слоёв: у исходящих звонков нашего события нет вообще, а именно они чаще всего
     * показывают, что вторая SIM используется.
     */
    @Query(
        """
        SELECT phoneAccountId FROM screening_events
        WHERE phoneAccountId IS NOT NULL AND phoneAccountId <> ''
        UNION
        SELECT phoneAccountId FROM call_log_mirror
        WHERE phoneAccountId IS NOT NULL AND phoneAccountId <> ''
        ORDER BY phoneAccountId
        """
    )
    public suspend fun sims(): List<String>
}
