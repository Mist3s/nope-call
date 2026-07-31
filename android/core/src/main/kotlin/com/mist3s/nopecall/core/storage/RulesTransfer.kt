package com.mist3s.nopecall.core.storage

import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.MatchType
import com.mist3s.nopecall.engine.PatternCheck
import com.mist3s.nopecall.engine.RegexField
import com.mist3s.nopecall.engine.RuleTarget
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Режим импорта (ТЗ §15.8). */
public enum class ImportMode {
    /**
     * Результат равен файлу: совпавшие по ключу правила обновляются, отсутствующие в файле
     * удаляются. Разрушающий режим — подтверждение спрашивает интерфейс, здесь оно не
     * подразумевается.
     */
    REPLACE_ALL,

    /** Добавляются только те правила, которых ещё нет. Существующие не меняются. */
    ADD_MISSING,
}

/**
 * Отклонённое правило: номер в файле, чтобы пользователь нашёл строку, и причина.
 *
 * Причина обязательна и берётся из той же проверки, что при сохранении руками: «правило не
 * импортировалось» без объяснения неотлаживаемо.
 */
public data class RejectedRule(
    /** Индекс в массиве `rules`, с нуля — как в файле. */
    val index: Int,
    val title: String?,
    val reason: String,
)

/** Отчёт об импорте (ТЗ §15.8). */
public data class ImportReport(
    val added: Int = 0,
    /** Обновлено на месте: только в режиме [ImportMode.REPLACE_ALL]. */
    val updated: Int = 0,
    /** Пропущено как дубликат по паре `target_type` + `match_type` + `pattern_canonical`. */
    val duplicates: Int = 0,
    /**
     * Названия удалённых правил, а не их количество: отчёт должен объяснять, что именно
     * исчезло. «Удалено 7» после режима «заменить всё» пользователю ничего не говорит.
     */
    val removed: List<String> = emptyList(),
    val rejected: List<RejectedRule> = emptyList(),
    /**
     * Снимок пересобран. `false` означает, что правила в базе новые, а действующая копия
     * осталась прежней — интерфейс обязан об этом сказать, иначе поведение звонков разойдётся
     * со списком правил (ТЗ §8.2).
     */
    val snapshotRebuilt: Boolean = false,
)

/** Итог импорта: либо разобрали файл и получили отчёт, либо файл целиком непригоден. */
public sealed interface ImportResult {
    public data class Done(val report: ImportReport) : ImportResult

    /** Файл не тронул базу вообще. */
    public data class Failed(val reason: String) : ImportResult
}

/**
 * Экспорт и импорт правил — он же формат резервной копии (ТЗ §15.8).
 *
 * Три свойства, из которых выведена вся реализация.
 *
 * **Импорт идёт через [RulesRepository.save].** Собственной записи в Room здесь нет намеренно:
 * иначе появился бы второй путь создания правил со своими проверками, и файл смог бы протащить
 * шаблон, который нельзя создать руками, — например катастрофическое регулярное выражение,
 * из-за которого каждый звонок упирался бы в бюджет (ТЗ §6.5). Цена — пересборка снимка на
 * каждое сохранённое правило. Отклонённая альтернатива: пакетная вставка в обход `save` с
 * повторением проверок на месте; отклонена потому, что повторённая проверка расходится
 * с оригиналом при первой же правке одной из двух.
 *
 * **Канонизация не считается здесь.** Ключ идемпотентности берётся из
 * [RulesRepository.validate], то есть из движка: посчитать его «своим» способом значило бы
 * получить ключи, не совпадающие с колонкой `patternCanonical`, и дедупликация тихо
 * перестала бы работать (архитектура §5.4).
 *
 * **Отклонение поштучное.** Неверный regex или неизвестный тип цели убирает одно правило,
 * а не весь файл: резервная копия из которой не восстановить 40 правил из-за одного —
 * бесполезная резервная копия. Целиком отклоняется только то, что делает файл неинтерпретируемым:
 * не JSON, нет версии схемы, версия из будущего.
 */
public class RulesTransfer(
    private val rules: RulesRepository,
    /** Пишется в файл как справка о происхождении копии. На разбор не влияет. */
    private val appVersion: String = "unknown",
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Все правила пользователя в JSON формата ТЗ §15.8.
     *
     * Порядок правил в файле — порядок `orderIndex`, то есть тот же, что на экране. Сам
     * `orderIndex` не экспортируется: в формате ТЗ его нет, а два источника порядка (номер
     * в файле и поле) расходились бы при правке файла руками. Следствие, оно же известное
     * ограничение: ручная перестановка правил импортом не переносится — порядок после
     * импорта расставляют веса §5.1.
     */
    public suspend fun exportJson(): String {
        val array = JSONArray()
        for (entity in rules.all()) array.put(ruleToJson(entity))

        return JSONObject().apply {
            put(KEY_SCHEMA, SCHEMA_VERSION)
            put(KEY_EXPORTED_AT, timestamp())
            put(KEY_APP_VERSION, appVersion)
            // Массив пишется всегда, даже пустой: `"rules": []` — это «правил нет», а
            // отсутствие ключа импорт обязан считать испорченным файлом (см. importJson).
            put(KEY_RULES, array)
        }.toString(INDENT)
    }

    /**
     * Разбирает файл и применяет его к базе, после чего пересобирает снимок.
     *
     * Транзакции на весь импорт нет сознательно: правило сохраняется тем же путём, что руками,
     * и откатывать половину импорта не требуется — отчёт говорит, что именно применилось.
     */
    public suspend fun importJson(text: String, mode: ImportMode): ImportResult {
        val root = runCatching { JSONObject(text) }.getOrElse {
            return ImportResult.Failed("файл не разбирается как JSON: ${it.message ?: "неизвестная ошибка"}")
        }

        val schema = root.optInt(KEY_SCHEMA, 0)
        if (schema <= 0) {
            return ImportResult.Failed("в файле нет версии схемы: это не набор правил «Отбоя»")
        }
        if (schema > SCHEMA_VERSION) {
            // Файл более новой версии может означать те же ключи с другим смыслом. Импортировать
            // его частично — значит создать правила, которые решают не то, что написано.
            return ImportResult.Failed(
                "файл версии $schema, приложение понимает до $SCHEMA_VERSION: обновите приложение",
            )
        }

        // Отсутствующий массив — не «правил нет», а испорченный файл. Разница существенна:
        // в режиме «заменить всё» пустой список удалил бы всё, что есть у пользователя.
        val array = root.optJSONArray(KEY_RULES)
            ?: return ImportResult.Failed("в файле нет списка правил")

        val existing = rules.all()
        val representatives = LinkedHashMap<RuleKey, RuleEntity>()
        // putIfAbsent: правила с одинаковым ключом в базе возможны (до появления импорта
        // дедупликации не было). «Тем самым» считается первое в порядке orderIndex.
        for (entity in existing) representatives.putIfAbsent(entity.key(), entity)

        var added = 0
        var updated = 0
        var duplicates = 0
        val rejected = mutableListOf<RejectedRule>()
        val seenInFile = HashSet<RuleKey>()
        val survivors = HashSet<Long>()

        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
            if (obj == null) {
                rejected += RejectedRule(index, null, "элемент списка не является объектом")
                continue
            }

            val title = obj.optString(KEY_TITLE).trim()
            val pattern = obj.optString(KEY_PATTERN)
            // В отчёте пустое название — это `null`, а не пустая строка: интерфейсу иначе
            // пришлось бы различать «без названия» и «название из пробелов».
            val shown = title.ifEmpty { null }

            val target = enumOrNull<RuleTarget>(obj.optString(KEY_TARGET))
            if (target == null) {
                rejected += RejectedRule(index, shown, "неизвестный тип цели: «${obj.optString(KEY_TARGET)}»")
                continue
            }
            val matchType = enumOrNull<MatchType>(obj.optString(KEY_MATCH))
            if (matchType == null) {
                rejected += RejectedRule(index, shown, "неизвестный способ сравнения: «${obj.optString(KEY_MATCH)}»")
                continue
            }
            val action = enumOrNull<CallAction>(obj.optString(KEY_ACTION))
            if (action == null) {
                rejected += RejectedRule(index, shown, "неизвестное действие: «${obj.optString(KEY_ACTION)}»")
                continue
            }
            val regexFieldName = optStringOrNull(obj, KEY_REGEX_FIELD)
            val regexField = regexFieldName?.let { enumOrNull<RegexField>(it) }
            if (regexFieldName != null && regexField == null) {
                rejected += RejectedRule(index, shown, "неизвестное поле для regex: «$regexFieldName»")
                continue
            }

            // У правила «в телефонной книге» шаблона нет, и осмысленным его делает только
            // название. Без обоих полей запись показывать пользователю нечем.
            if (title.isEmpty() && pattern.isEmpty()) {
                rejected += RejectedRule(index, null, "ни названия, ни шаблона")
                continue
            }

            // Та же проверка, что при сохранении руками, и тот же канонический вид (ТЗ §15.8).
            val canonical = when (val check = rules.validate(target, matchType, pattern)) {
                is PatternCheck.Ok -> check.canonical
                is PatternCheck.Invalid -> {
                    rejected += RejectedRule(index, shown, check.reason)
                    continue
                }
                is PatternCheck.TooExpensive -> {
                    rejected += RejectedRule(index, shown, check.reason)
                    continue
                }
            }

            val key = RuleKey(target.name, matchType.name, canonical)
            if (!seenInFile.add(key)) {
                // Дубликат внутри одного файла считается дубликатом, а не вторым правилом:
                // иначе «повторный импорт не создаёт дублей» держалось бы только на состоянии
                // базы, а файл с двумя одинаковыми строками их создавал бы.
                duplicates++
                continue
            }

            val twin = representatives[key]
            if (twin != null && mode == ImportMode.ADD_MISSING) {
                duplicates++
                survivors += twin.id
                continue
            }

            val result = rules.save(
                id = twin?.id,
                title = title,
                target = target,
                matchType = matchType,
                pattern = pattern,
                action = action,
                enabled = obj.optBoolean(KEY_ENABLED, true),
                regexField = regexField,
                translitVariants = optBooleanOrNull(obj, KEY_TRANSLIT),
                leetVariants = obj.optBoolean(KEY_LEET, false),
                comment = optStringOrNull(obj, KEY_COMMENT),
            )
            when (result) {
                is SaveResult.Saved -> {
                    survivors += result.id
                    if (twin == null) added++ else updated++
                }
                // Проверка уже прошла, так что сюда попасть не должно. Но если `save` отказал —
                // это отклонение с причиной, а не молча потерянное правило.
                is SaveResult.Rejected -> rejected += RejectedRule(index, shown, result.reason)
            }
        }

        val removed = mutableListOf<String>()
        if (mode == ImportMode.REPLACE_ALL) {
            for (entity in existing) {
                if (entity.id in survivors) continue
                rules.delete(entity.id)
                removed += entity.title
            }
        }

        // Пересборка в конце — отдельно от той, что делает каждый `save`. Она нужна для случаев,
        // когда ни одного `save` не было: файл из одних отклонений или импорт, состоявший только
        // из удалений. Без неё «после импорта снимок пересобран» выполнялось бы не всегда.
        val rebuilt = rules.rebuildSnapshot()

        return ImportResult.Done(
            ImportReport(
                added = added,
                updated = updated,
                duplicates = duplicates,
                removed = removed,
                rejected = rejected,
                snapshotRebuilt = rebuilt,
            ),
        )
    }

    /**
     * Ключ идемпотентности (ТЗ §15.8).
     *
     * Отдельный тип, а не склеенная строка: канонический шаблон regex-правила — это сам текст
     * выражения, в котором может встретиться любой разделитель, и склейка дала бы совпадение
     * ключей у разных правил.
     */
    private data class RuleKey(val target: String, val matchType: String, val canonical: String)

    private fun RuleEntity.key(): RuleKey = RuleKey(targetType, matchType, patternCanonical)

    private fun ruleToJson(entity: RuleEntity): JSONObject = JSONObject().apply {
        put(KEY_TITLE, entity.title)
        put(KEY_TARGET, entity.targetType)
        put(KEY_MATCH, entity.matchType)
        // Шаблон — как ввёл пользователь, а не канонизированный: файл читают и правят руками,
        // а канонизацию импорт посчитает сам, тем же кодом движка.
        put(KEY_PATTERN, entity.pattern)
        put(KEY_ACTION, entity.action)
        put(KEY_ENABLED, entity.isEnabled)
        entity.comment?.takeIf { it.isNotBlank() }?.let { put(KEY_COMMENT, it) }

        // Поля сверх формата ТЗ §15.8 пишутся только когда несут смысл, иначе обычное правило
        // по номеру обросло бы тремя техническими ключами. Без них экспорт-импорт менял бы
        // поведение правила: regex по полю NAME_ORG вернулся бы как regex по полю
        // по умолчанию, то есть стал бы другим правилом.
        entity.regexField?.let { put(KEY_REGEX_FIELD, it) }
        if (entity.translitVariants != defaultTranslit(entity.targetType)) {
            put(KEY_TRANSLIT, entity.translitVariants)
        }
        if (entity.leetVariants) put(KEY_LEET, true)
    }

    /**
     * Значение `translitVariants`, которое поставит [RulesRepository.save], если ключа в файле
     * нет. Держится в паре с логикой `save`: расхождение проявилось бы не отказом, а тем, что
     * восстановленное из копии правило ловит одно написание из двух (ТЗ §6.3.1).
     */
    private fun defaultTranslit(targetType: String): Boolean =
        targetType == RuleTarget.NAME_ORG.name || targetType == RuleTarget.NAME.name

    private fun timestamp(): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(now()).atZone(zone))

    private fun optStringOrNull(obj: JSONObject, key: String): String? =
        if (obj.has(key) && !obj.isNull(key)) obj.optString(key).takeIf { it.isNotEmpty() } else null

    /** `null` значит «в файле ключа нет», то есть решает значение по умолчанию из `save`. */
    private fun optBooleanOrNull(obj: JSONObject, key: String): Boolean? =
        if (obj.has(key) && !obj.isNull(key)) obj.optBoolean(key) else null

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    public companion object {
        /**
         * Версия формата файла. Растёт при несовместимом изменении: файл более новой версии
         * приложение обязано отклонить целиком, а не разобрать наполовину.
         */
        public const val SCHEMA_VERSION: Int = 1

        public const val KEY_SCHEMA: String = "schema"
        public const val KEY_EXPORTED_AT: String = "exported_at"
        public const val KEY_APP_VERSION: String = "app_version"
        public const val KEY_RULES: String = "rules"

        private const val KEY_TITLE = "title"
        private const val KEY_TARGET = "target_type"
        private const val KEY_MATCH = "match_type"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_ACTION = "action"
        private const val KEY_ENABLED = "is_enabled"
        private const val KEY_COMMENT = "comment"
        private const val KEY_REGEX_FIELD = "regex_field"
        private const val KEY_TRANSLIT = "translit_variants"
        private const val KEY_LEET = "leet_variants"

        /** Файл читают и правят руками, поэтому он с отступами, а не одной строкой. */
        private const val INDENT = 2
    }
}
