package com.mist3s.nopecall.core.observe

/**
 * Один ключ `extras` в виде, пригодном для лога (ТЗ §7.7.1).
 *
 * Тип хранится строкой рядом со значением: именно в `extras` вендорские и IMS-реализации
 * могут прятать операторскую подпись, и без типа непонятно, что это — строка, `Bundle`
 * или массив байт.
 */
public data class ExtraEntry(
    val key: String,
    val type: String,
    val value: String?,
)

/** Контекст сети на момент звонка (ТЗ §7.7.1). От него зависит наличие подписи (§6.3.1). */
public data class NetworkContext(
    val networkType: String? = null,
    /** `null` — определить не удалось. Отличать от «нет VoLTE» обязательно. */
    val volte: Boolean? = null,
    val operatorName: String? = null,
    val roaming: Boolean? = null,
    val simSlot: Int? = null,
) {
    public companion object {
        public val UNKNOWN: NetworkContext = NetworkContext()
    }
}

/**
 * Контекст устройства (ТЗ §7.7.1).
 *
 * `installId` — случайный, не IMEI и не рекламный идентификатор. Нужен только чтобы логи
 * с разных телефонов различались и сводились вместе.
 */
public data class DeviceContext(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildFingerprint: String,
    val appVersion: String,
    val installId: String,
)

/**
 * Событие потока A: что реально пришло на проверку (ТЗ §7.7.1).
 *
 * Собирается **после** ответа системе. Три вещи здесь принципиальны:
 *  * `displayNameRaw` — как есть, без канонизации: предмет исследования именно сырой вид;
 *  * производные поля канонизации рядом — чтобы видеть, что она сделала с реальными данными;
 *  * полный дамп `extras` — вендор может положить подпись куда угодно, и без дампа это не найти.
 */
public data class CallObservation(
    val at: Long,
    val handleScheme: String?,
    val handleValue: String?,
    val handlePresentation: Int,
    val displayNameRaw: String?,
    val displayNamePresentation: Int,
    val verificationStatus: Int?,
    val creationTimeMillis: Long,
    val callDirection: Int? = null,
    val connectTimeMillis: Long? = null,
    val accountHandle: String? = null,
    val extras: List<ExtraEntry> = emptyList(),
    val intentExtras: List<ExtraEntry> = emptyList(),
    /**
     * Сырой `Call.Details` и поля, которые построение фактов не использует.
     *
     * Без него по логу нельзя отличить «система не дала названия» от «дала, но мы его
     * не читаем»: разобранные поля показывают только наш вывод, а не исходные данные.
     */
    val raw: List<ExtraEntry> = emptyList(),
    /** Производные поля движка: `digits`, `e164`, `name_norm`, `name_tokens`, `name_fold`. */
    val digits: String? = null,
    val e164: String? = null,
    val nameNorm: String? = null,
    val nameTokens: String? = null,
    val nameFold: String? = null,
    val orgFold: String? = null,
    val categoryFold: String? = null,
    val nameSource: String? = null,
    val inContacts: Boolean? = null,
    val action: String,
    val reason: String,
    val degradations: Int,
    val matchedRuleId: Long? = null,
    val checkedRuleIds: List<Long> = emptyList(),
    val checkedTruncated: Boolean = false,
    val latencyMs: Int,
    val budgetMs: Int,
    val coldStart: Boolean,
    val directBoot: Boolean,
    val watchdogFired: Boolean,
    val network: NetworkContext = NetworkContext.UNKNOWN,
    val device: DeviceContext? = null,
) {
    /**
     * Строка JSONL. `at` — первым ключом, на это опирается выгрузка за период (§7.7.3).
     *
     * Пишется **всегда полностью**, без маскирования: на устройстве лог нужен целиком, иначе
     * разобрать жалобу по нему невозможно. Обезличивается только выгрузка (ТЗ §7.7.4).
     */
    internal fun toJsonLine(): String = Json.line {
        put("at", at)
        put("scheme", handleScheme)
        put("number", handleValue)
        put("number_presentation", handlePresentation)
        put("display_name", displayNameRaw)
        put("display_name_presentation", displayNamePresentation)
        put("verification", verificationStatus)
        put("created_at", creationTimeMillis)
        put("direction", callDirection)
        put("connected_at", connectTimeMillis)
        put("account", accountHandle)

        putObject("canon") {
            put("digits", digits)
            put("e164", e164)
            put("name_norm", nameNorm)
            put("name_tokens", nameTokens)
            put("name_fold", nameFold)
            put("org_fold", orgFold)
            put("category_fold", categoryFold)
            put("name_source", nameSource)
            put("in_contacts", inContacts)
        }

        putObject("decision") {
            put("action", action)
            put("reason", reason)
            put("degradations", degradations)
            put("rule_id", matchedRuleId)
            putArray("checked_rules", checkedRuleIds.map { it.toString() })
            // Усечение видно, а не молчит: иначе «проверено 200 правил» читалось бы
            // как «правил всего 200».
            put("checked_truncated", if (checkedTruncated) true else null)
        }

        putObject("timing") {
            put("latency_ms", latencyMs)
            put("budget_ms", budgetMs)
            put("cold_start", coldStart)
            put("direct_boot", directBoot)
            put("watchdog", watchdogFired)
        }

        putObject("network") {
            put("type", network.networkType)
            put("volte", network.volte)
            put("operator", network.operatorName)
            put("roaming", network.roaming)
            put("sim_slot", network.simSlot)
        }

        device?.let { d ->
            putObject("device") {
                put("manufacturer", d.manufacturer)
                put("model", d.model)
                put("android", d.androidRelease)
                put("sdk", d.sdkInt)
                put("fingerprint", d.buildFingerprint)
                put("app", d.appVersion)
                put("install_id", d.installId)
            }
        }

        putObjects("extras", extras.map { it.toJson() })
        putObjects("intent_extras", intentExtras.map { it.toJson() })
        putObjects("raw", raw.map { it.toJson() })
    }

    private fun ExtraEntry.toJson(): Json = Json().obj {
        put("key", key)
        put("type", type)
        put("value", value)
    }
}

/**
 * Режим маскирования при выгрузке (ТЗ §7.7.4).
 *
 * Обезличенный — по умолчанию. Разделение по источнику названия здесь не формальность:
 * подпись юрлица (`PAO SOVKOMBANK`) — не персональные данные и именно она предмет
 * исследования, а имя из телефонной книги — персональные данные третьего лица.
 */
public enum class MaskMode {
    FULL,
    MASKED;

    /**
     * Маскирует длинные последовательности цифр: `74951234567` → `7495***67` (ТЗ §7.7.4).
     *
     * По последовательностям цифр, а не по списку известных ключей: состав `extras` заранее
     * неизвестен — ради него режим наблюдения и существует, — и «неизвестный ключ» здесь
     * означает «может содержать номер».
     */
    internal fun extra(value: String?): String? {
        if (this == FULL || value.isNullOrEmpty()) return value
        return DIGIT_RUN.replace(value) { match ->
            val digits = match.value
            "${digits.take(HEAD)}***${digits.takeLast(TAIL)}"
        }
    }

    private companion object {
        const val HEAD = 4
        const val TAIL = 2

        /**
         * Семь цифр — граница, ниже которой маскировать нечего: короче встречаются только
         * служебные значения и короткие номера, у которых от маски ничего не остаётся.
         */
        val DIGIT_RUN = Regex("""\d{7,}""")
    }
}
