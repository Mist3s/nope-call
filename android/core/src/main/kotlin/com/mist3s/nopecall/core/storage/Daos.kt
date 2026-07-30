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

    @Query("UPDATE screening_events SET matchedSystemId = :systemId WHERE id = :eventId")
    public suspend fun attachSystemId(eventId: Long, systemId: Long)

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
}
