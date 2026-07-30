package com.mist3s.nopecall.core.storage

import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.DecisionSettings
import com.mist3s.nopecall.engine.MatchType
import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.PatternCheck
import com.mist3s.nopecall.engine.PhoneNumberNormalizer
import com.mist3s.nopecall.engine.RegexField
import com.mist3s.nopecall.engine.RegexValidator
import com.mist3s.nopecall.engine.Rule
import com.mist3s.nopecall.engine.RuleSnapshot
import com.mist3s.nopecall.engine.RuleTarget
import com.mist3s.nopecall.engine.SnapshotBuilder

/** Результат сохранения правила: либо оно сохранено, либо шаблон отвергнут с причиной. */
public sealed interface SaveResult {
    public data class Saved(
        val id: Long,
        /** Варианты, по которым правило будет искать. Показываются пользователю (ТЗ §6.3.2). */
        val variants: List<String>,
        val variantsTruncated: Boolean = false,
    ) : SaveResult

    public data class Rejected(val reason: String) : SaveResult
}

/**
 * Правила: источник истины в Room, действующая копия — в снимке.
 *
 * Ключевое свойство: **любое изменение правил заканчивается пересборкой снимка**. Иначе
 * пользователь создаст правило, увидит его в списке и не поймёт, почему звонки не блокируются.
 * Пересборка идёт здесь, вне горячего пути: править снимок из `onScreenCall` нельзя.
 */
public class RulesRepository(
    private val db: NopeCallDatabase,
    private val snapshots: SnapshotStore,
    private val normalizer: PhoneNumberNormalizer,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val builder = SnapshotBuilder(normalizer)

    public suspend fun all(): List<RuleEntity> = db.rules().all()

    public suspend fun byId(id: Long): RuleEntity? = db.rules().byId(id)

    public suspend fun enabledCount(): Int = db.rules().enabledCount()

    /**
     * Сохраняет правило и пересобирает снимок.
     *
     * Шаблон проверяется **до** записи: некорректное или катастрофически дорогое регулярное
     * выражение не должно попасть в базу, иначе каждый звонок будет упираться в бюджет,
     * а пользователь увидит только «правило не сработало» (ТЗ §6.5).
     */
    public suspend fun save(
        id: Long? = null,
        title: String,
        target: RuleTarget,
        matchType: MatchType,
        pattern: String,
        action: CallAction,
        enabled: Boolean = true,
        regexField: RegexField? = null,
        translitVariants: Boolean? = null,
        leetVariants: Boolean = false,
        comment: String? = null,
    ): SaveResult {
        val check = validate(target, matchType, pattern)
        if (check is PatternCheck.Invalid) return SaveResult.Rejected(check.reason)
        if (check is PatternCheck.TooExpensive) return SaveResult.Rejected(check.reason)
        val ok = check as PatternCheck.Ok

        // Для наименований варианты транслитерации включены по умолчанию: у одного юрлица
        // наблюдались `Poleznyy` и `Polezniy`, и без вариантов правило поймало бы одно
        // написание и пропустило второе (ТЗ §6.3.1).
        val useVariants = translitVariants ?: (target == RuleTarget.NAME_ORG || target == RuleTarget.NAME)

        val timestamp = now()
        val existing = id?.let { db.rules().byId(it) }
        val entity = RuleEntity(
            id = existing?.id ?: 0,
            title = title.ifBlank { pattern },
            targetType = target.name,
            matchType = matchType.name,
            pattern = pattern,
            patternCanonical = ok.canonical,
            patternVariants = ok.variants.joinToString("\n"),
            action = action.name,
            orderIndex = existing?.orderIndex ?: nextOrderIndex(target, matchType, action),
            isEnabled = enabled,
            regexField = regexField?.name,
            translitVariants = useVariants,
            leetVariants = leetVariants,
            comment = comment,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
            matchCount = existing?.matchCount ?: 0,
            lastMatchedAt = existing?.lastMatchedAt,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )

        val savedId = if (existing == null) {
            db.rules().insert(entity)
        } else {
            db.rules().update(entity)
            existing.id
        }

        rebuildSnapshot()
        return SaveResult.Saved(savedId, ok.variants, ok.variantsTruncated)
    }

    public suspend fun setEnabled(id: Long, enabled: Boolean) {
        val rule = db.rules().byId(id) ?: return
        db.rules().update(rule.copy(isEnabled = enabled, updatedAt = now()))
        rebuildSnapshot()
    }

    public suspend fun delete(id: Long) {
        db.rules().delete(id)
        rebuildSnapshot()
    }

    /**
     * Переупорядочивание одной операцией, а не N вызовами: перенумерация идёт в одной
     * транзакции, иначе промежуточное состояние дало бы неверный порядок правил.
     */
    public suspend fun reorder(idsInOrder: List<Long>) {
        if (idsInOrder.isEmpty()) return
        db.rules().reorder(idsInOrder, weightBase = RuleWeights.WEIGHT_STRIDE, now = now())
        rebuildSnapshot()
    }

    /** Проверка шаблона для редактора: без записи, с показом вариантов (ТЗ §9.5). */
    public fun validate(target: RuleTarget, matchType: MatchType, pattern: String): PatternCheck {
        if (target == RuleTarget.CONTACT || matchType == MatchType.IN_CONTACTS) {
            return PatternCheck.Ok(canonical = "", variants = emptyList(), literal = null)
        }
        if (pattern.isBlank()) return PatternCheck.Invalid("шаблон пустой")

        if (matchType == MatchType.REGEX) return RegexValidator.validate(pattern)

        val canonical = when (target) {
            RuleTarget.NUMBER -> canonizeNumberPattern(matchType, pattern)
            else -> if (matchType == MatchType.TOKEN) {
                NameCanonizer.patternTokens(pattern).joinToString("")
            } else {
                NameCanonizer.canonizePattern(pattern)
            }
        }
        if (canonical.isEmpty()) {
            return PatternCheck.Invalid("после нормализации шаблон пуст: в нём нет ни цифр, ни букв")
        }

        val useVariants = target == RuleTarget.NAME_ORG || target == RuleTarget.NAME ||
            target == RuleTarget.NAME_CATEGORY
        val variants = if (useVariants) {
            NameCanonizer.variantsOf(canonical, limit = VARIANT_LIMIT)
        } else {
            emptyList()
        }
        return PatternCheck.Ok(
            canonical = canonical,
            variants = variants,
            literal = null,
            variantsTruncated = variants.size >= VARIANT_LIMIT,
        )
    }

    private fun canonizeNumberPattern(matchType: MatchType, pattern: String): String {
        val digits = pattern.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        if (matchType == MatchType.EXACT) {
            val forms = normalizer.normalize(pattern, "RU")
            return forms.canonicalDigits.ifEmpty { digits }
        }
        val hasPlus = pattern.trimStart().startsWith("+")
        return if (!hasPlus && digits.length > 1 && digits[0] == '8') "7" + digits.substring(1) else digits
    }

    /**
     * Пересборка снимка из Room.
     *
     * Вызывается после каждого изменения правил и настроек, а также после обновления приложения.
     * Ошибка записи не должна ронять вызывающего: снимок останется прежним, а звонки будут
     * решаться по нему — это хуже, чем актуальный снимок, но лучше, чем упавшее приложение.
     */
    public suspend fun rebuildSnapshot(): Boolean {
        val entities = db.rules().enabled()
        val rules = entities.mapNotNull { RuleMapping.toEngine(it) }
        val settings = loadSettings()
        return try {
            snapshots.write(builder.build(rules, settings))
            true
        } catch (t: Throwable) {
            false
        }
    }

    private suspend fun loadSettings(): DecisionSettings {
        val stored = db.settings().all().associate { it.key to it.value }
        return DecisionSettings(
            blockingEnabled = stored[KEY_BLOCKING_ENABLED]?.toBooleanStrictOrNull() ?: true,
            defaultAction = stored[KEY_DEFAULT_ACTION]?.toActionOrNull() ?: CallAction.ALLOW,
            restrictedAction = stored[KEY_RESTRICTED_ACTION]?.toActionOrNull() ?: CallAction.ALLOW,
            unknownAction = stored[KEY_UNKNOWN_ACTION]?.toActionOrNull() ?: CallAction.ALLOW,
            region = stored[KEY_REGION] ?: "RU",
            categoryDictionary = (stored[KEY_CATEGORY_DICT] ?: DEFAULT_CATEGORY_DICT)
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }

    public suspend fun putSetting(key: String, value: String) {
        db.settings().put(SettingEntity(key, value))
        rebuildSnapshot()
    }

    public suspend fun getSetting(key: String): String? = db.settings().get(key)

    public suspend fun allSettings(): Map<String, String> =
        db.settings().all().associate { it.key to it.value }

    private suspend fun nextOrderIndex(
        target: RuleTarget,
        matchType: MatchType,
        action: CallAction,
    ): Int {
        val weight = RuleWeights.weightFor(target, matchType, action)
        val base = RuleWeights.baseFor(weight)
        val last = db.rules().maxOrderIndexInRange(base, base + RuleWeights.WEIGHT_STRIDE - 1)
        return (last ?: base) + RuleDao.ORDER_STEP
    }

    private fun String.toActionOrNull(): CallAction? =
        CallAction.entries.firstOrNull { it.name == this }

    public companion object {
        public const val KEY_BLOCKING_ENABLED: String = "blocking_enabled"
        public const val KEY_DEFAULT_ACTION: String = "default_action"
        public const val KEY_RESTRICTED_ACTION: String = "restricted_action"
        public const val KEY_UNKNOWN_ACTION: String = "unknown_action"
        public const val KEY_REGION: String = "region"
        public const val KEY_CATEGORY_DICT: String = "category_dictionary"

        /**
         * Корни категорий из наблюдённого корпуса подписей (ТЗ §6.3.1). Пополняются по данным
         * режима наблюдения, поэтому хранятся настройкой, а не константой в коде.
         *
         * Корни намеренно длинные: `agen` совпал бы и со словом `Agent` из наименования
         * `Agent Rostelecom`, а для распознавания категории это ошибка.
         */
        public const val DEFAULT_CATEGORY_DICT: String =
            "dostavka,finans,reklam,agenstvo,opros,transport,torgovl,informac,it"

        private const val VARIANT_LIMIT = 64
    }
}
