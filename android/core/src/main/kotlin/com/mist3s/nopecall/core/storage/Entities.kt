package com.mist3s.nopecall.core.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Правило пользователя (ТЗ §8.3).
 *
 * `orderIndex` — разреженная нумерация `вес × 1 000 000 + слот × 1024` (ТЗ §5.1). Индекс
 * обычный, а не `UNIQUE`: в SQLite уникальность проверяется построчно, поэтому перенумерация
 * одним `UPDATE` упала бы на первом пересечении со старой нумерацией. Уникальность держится
 * инвариантом и тестом.
 */
@Entity(
    tableName = "block_rules",
    indices = [Index("isEnabled", "orderIndex"), Index("orderIndex")],
)
public data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** `NUMBER` / `NAME` / `NAME_ORG` / `NAME_CATEGORY` / `CONTACT`. */
    val targetType: String,
    /** `EXACT` / `PREFIX` / `SUFFIX` / `CONTAINS` / `TOKEN` / `REGEX` / `IN_CONTACTS`. */
    val matchType: String,
    /** Как ввёл пользователь. Показывается ему же. */
    val pattern: String,
    /**
     * Канонизированный шаблон. Хранится, а не вычисляется при чтении: канонизация — это
     * транслитерация и переписывание префиксов, чего SQL не умеет, а предпросмотр и фильтры
     * должны работать по индексам (архитектура §5.4).
     */
    val patternCanonical: String,
    /** Предвычисленные варианты написания, через `\n`. Пусто, если не нужны. */
    val patternVariants: String = "",
    val action: String,
    val orderIndex: Int,
    val isEnabled: Boolean = true,
    val regexField: String? = null,
    val translitVariants: Boolean = false,
    val leetVariants: Boolean = false,
    val comment: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val matchCount: Long = 0,
    val lastMatchedAt: Long? = null,
    /** Подряд идущих ошибок. После трёх правило выключается автоматически (ТЗ §6.5). */
    val errorCount: Int = 0,
    val lastError: String? = null,
    /**
     * Версия канонизации, с которой посчитан `patternCanonical`. При смене алгоритма правила
     * пересчитываются **до** сборки снимка: иначе они начнут принимать неверные решения,
     * а не просто врать в фильтрах (архитектура §5.4).
     */
    val canonVersion: Int,
)

/** Настройки приложения. Одна строка: ключ — значение. */
@Entity(tableName = "app_settings")
public data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * Слой 1 журнала: наши собственные события проверки (ТЗ §7.1).
 *
 * Пишутся всегда, без разрешений — это единственное, что приложение знает наверняка.
 * Всё остальное (ответили ли, сколько длился, исходящие) берётся из зеркала системного журнала.
 */
@Entity(
    tableName = "screening_events",
    indices = [
        Index("occurredAt"),
        Index("digits"),
        Index("matchedRuleId"),
        Index(value = ["matchedSystemId"], unique = true),
    ],
)
public data class ScreeningEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val rawNumber: String,
    val digits: String,
    val e164: String? = null,
    val presentation: String,
    val nameRaw: String? = null,
    val nameNorm: String? = null,
    /** Слова через пробел с ограничителями: `" pao sovkombank "`. Даёт `LIKE '% pao %'` в SQL. */
    val nameTokens: String? = null,
    val nameFold: String? = null,
    val orgFold: String? = null,
    val categoryFold: String? = null,
    val nameSource: String,
    /**
     * Название стало известно **после** решения, при сшивке с системным журналом (ТЗ §7.3).
     *
     * Отдельный флаг, а не значение `nameSource`. Раньше позднее название затирало источник
     * на `SYSTEM_LOG`, и из этого получались два неверных вывода сразу: показатель «подпись
     * оператора пришла позже» считал таковой любое позднее имя, хотя `CallLog.CACHED_NAME`
     * для номера из книги — это имя контакта; а показатель «с названием в момент проверки»
     * считал позднее имя известным в момент решения, то есть прямо наоборот.
     *
     * `null` — событие записано до появления флага: тогда про источник позднего названия
     * ничего не известно, и в показатель подписи оно не идёт.
     */
    val nameLate: Boolean? = null,
    val action: String,
    val reason: String,
    /** Битовая маска деградаций: одного `reason` мало, состояния совмещаются (архитектура §6.7). */
    val degradations: Int = 0,
    val matchedRuleId: Long? = null,
    /**
     * Название правила на момент срабатывания. Дублируется намеренно: правило можно удалить
     * или переписать, а запись журнала должна остаться объяснимой (ТЗ §7.1).
     */
    val matchedRuleTitle: String? = null,
    val latencyMs: Int,
    val budgetMs: Int,
    val phoneAccountId: String? = null,
    /** `null` на API 29: поля просто нет, и это не то же самое, что «не проверен» (ТЗ §6.4). */
    val verificationStatus: Int? = null,
    /** Сшивка с зеркалом системного журнала (ТЗ §7.3). */
    @ColumnInfo(name = "matchedSystemId") val matchedSystemId: Long? = null,
    val canonVersion: Int,

    // --- диагностика и сводка режима наблюдения (ТЗ §7.1, §7.7.5) ---------------------------
    //
    // Эти поля живут здесь, а не только в сегментах JSONL, ради сводки §7.7.5: иначе экран
    // режима пришлось бы считать разбором логов, то есть читать десятки мегабайт JSON на
    // каждое открытие. `null` везде значит «не определяли», а не «нет».
    val coldStart: Boolean? = null,
    val directBoot: Boolean? = null,
    val networkType: String? = null,
    val volte: Boolean? = null,
    val operatorName: String? = null,
    val roaming: Boolean? = null,
    /**
     * Ключи `extras`, встретившиеся в звонке, через запятую — без значений.
     *
     * Только ключи: сводка отвечает на вопрос «куда вендор мог положить подпись», а значения
     * для этого не нужны и содержат персональные данные. Полный дамп со значениями остаётся
     * в сегментах режима наблюдения (ТЗ §7.7.1).
     */
    val extrasKeys: String? = null,
)

/**
 * Слой 2: зеркало системного журнала звонков (ТЗ §7.2).
 *
 * Нужен `READ_CALL_LOG`. Без него раздел «Журнал» показывает только слой 1 — потому что
 * `CallScreeningService` не узнаёт ни исхода звонка, ни длительности, а исходящие в него
 * не приходят вообще.
 */
@Entity(
    tableName = "call_log_mirror",
    indices = [Index("startedAt"), Index("digits"), Index("type")],
)
public data class CallLogMirrorEntity(
    @PrimaryKey val systemId: Long,
    val startedAt: Long,
    val rawNumber: String,
    val digits: String,
    val e164: String? = null,
    val name: String? = null,
    val nameFold: String? = null,
    val type: String,
    val durationSeconds: Int = 0,
    val phoneAccountId: String? = null,
    /**
     * Скрыто локально пользователем. При повторной синхронизации НЕ сбрасывается: иначе
     * скрытая запись воскресала бы (ТЗ §7.2, критерий приёмки §18 п. 18).
     */
    val hiddenLocally: Boolean = false,
    val syncedAt: Long,
    val canonVersion: Int,
)

/** Проекция «идентификатор — название правила». */
public data class RuleTitle(val id: Long, val title: String)

/** Проекция «значение — сколько раз встретилось». Для разбивок сводки (ТЗ §7.7.5). */
public data class Bucket(val bucket: String, val total: Int)

/** Наблюдённая операторская подпись: дословно, со свёрнутой формой рядом (ТЗ §7.7.5). */
public data class SignatureSample(
    val raw: String,
    val fold: String?,
    val total: Int,
    val lastAt: Long,
)
