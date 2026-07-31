package com.mist3s.nopecall.core.observe

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки режима наблюдения (ТЗ §7.7.2).
 *
 * Все параметры — настройки, а не константы: столкнувшись с новым непонятным поведением, надо
 * иметь возможность попросить включить подробный лог и увеличить срок, **не выпуская новую
 * сборку**. Ради этого режим и существует.
 */
public data class ObservationConfig(
    val enabled: Boolean = DEFAULT_ENABLED,
    val techEnabled: Boolean = DEFAULT_TECH_ENABLED,
    val techVerbose: Boolean = false,
    val callsRetentionDays: Int = DEFAULT_CALLS_DAYS,
    val callsMaxMb: Int = DEFAULT_CALLS_MB,
    val techRetentionDays: Int = DEFAULT_TECH_DAYS,
    val techMaxMb: Int = DEFAULT_TECH_MB,
    /** Режим выгрузки по умолчанию. Обезличенный — потому что по умолчанию (ТЗ §7.7.4). */
    val maskByDefault: Boolean = true,
) {
    internal val callsLimits: SegmentStore.Limits
        get() = SegmentStore.Limits(
            maxAgeDays = callsRetentionDays,
            maxBytes = callsMaxMb.toLong() * 1024 * 1024,
            // Поток A режется по суткам: сегмент за сутки в реальных объёмах — единицы мегабайт,
            // и дробить его дополнительно незачем.
            maxSegmentBytes = Long.MAX_VALUE,
        )

    internal val techLimits: SegmentStore.Limits
        get() = SegmentStore.Limits(
            maxAgeDays = techRetentionDays,
            maxBytes = techMaxMb.toLong() * 1024 * 1024,
            maxSegmentBytes = TECH_SEGMENT_BYTES,
        )

    public fun toMap(): Map<String, String> = mapOf(
        KEY_ENABLED to enabled.toString(),
        KEY_TECH_ENABLED to techEnabled.toString(),
        KEY_TECH_VERBOSE to techVerbose.toString(),
        KEY_CALLS_DAYS to callsRetentionDays.toString(),
        KEY_CALLS_MB to callsMaxMb.toString(),
        KEY_TECH_DAYS to techRetentionDays.toString(),
        KEY_TECH_MB to techMaxMb.toString(),
        KEY_MASK_DEFAULT to maskByDefault.toString(),
    )

    public companion object {
        /**
         * Режим включён по умолчанию — это осознанное решение, а не недосмотр (ТЗ §7.7.4).
         *
         * Почти все неизвестные проекта (доступна ли подпись оператора в момент проверки, её
         * формат, куда вендор кладёт данные, реальный дедлайн) закрываются только сбором
         * с реальных телефонов. Приложение первой версии само является инструментом сбора,
         * и режим, который надо не забыть включить, данных к моменту проблемы не имеет.
         */
        public const val DEFAULT_ENABLED: Boolean = true
        public const val DEFAULT_TECH_ENABLED: Boolean = true

        /** §7.7.2: поток A — 90 суток и 100 МБ, поток B — 14 суток и 200 МБ. */
        public const val DEFAULT_CALLS_DAYS: Int = 90
        public const val DEFAULT_CALLS_MB: Int = 100
        public const val DEFAULT_TECH_DAYS: Int = 14
        public const val DEFAULT_TECH_MB: Int = 200

        public const val KEY_ENABLED: String = "observe_enabled"
        public const val KEY_TECH_ENABLED: String = "observe_tech_enabled"
        public const val KEY_TECH_VERBOSE: String = "observe_tech_verbose"
        public const val KEY_CALLS_DAYS: String = "observe_calls_days"
        public const val KEY_CALLS_MB: String = "observe_calls_mb"
        public const val KEY_TECH_DAYS: String = "observe_tech_days"
        public const val KEY_TECH_MB: String = "observe_tech_mb"
        public const val KEY_MASK_DEFAULT: String = "observe_mask_default"

        /** §7.7.2: сегменты потока B по 8 МБ. */
        internal const val TECH_SEGMENT_BYTES: Long = 8L * 1024 * 1024

        public val KEYS: List<String> = listOf(
            KEY_ENABLED, KEY_TECH_ENABLED, KEY_TECH_VERBOSE,
            KEY_CALLS_DAYS, KEY_CALLS_MB, KEY_TECH_DAYS, KEY_TECH_MB, KEY_MASK_DEFAULT,
        )

        public fun fromMap(values: Map<String, String?>): ObservationConfig {
            val defaults = ObservationConfig()
            fun bool(key: String, fallback: Boolean) =
                values[key]?.toBooleanStrictOrNull() ?: fallback

            fun int(key: String, fallback: Int) = values[key]?.toIntOrNull() ?: fallback
            return ObservationConfig(
                enabled = bool(KEY_ENABLED, defaults.enabled),
                techEnabled = bool(KEY_TECH_ENABLED, defaults.techEnabled),
                techVerbose = bool(KEY_TECH_VERBOSE, defaults.techVerbose),
                callsRetentionDays = int(KEY_CALLS_DAYS, defaults.callsRetentionDays),
                callsMaxMb = int(KEY_CALLS_MB, defaults.callsMaxMb),
                techRetentionDays = int(KEY_TECH_DAYS, defaults.techRetentionDays),
                techMaxMb = int(KEY_TECH_MB, defaults.techMaxMb),
                maskByDefault = bool(KEY_MASK_DEFAULT, defaults.maskByDefault),
            )
        }
    }
}

/**
 * Хранилище настроек режима в Device Protected Storage.
 *
 * Room здесь использовать нельзя: писатель работает в том числе до первой разблокировки экрана,
 * а до неё база недоступна. Поэтому настройки живут в DE-хранилище, а Room остаётся источником
 * истины для интерфейса и зеркалит сюда при каждом изменении.
 *
 * `installId` тоже здесь: он должен быть один и тот же для всех логов устройства, в том числе
 * записанных до разблокировки.
 */
public class ObservationStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    public fun config(): ObservationConfig = ObservationConfig.fromMap(
        ObservationConfig.KEYS.associateWith { prefs.getString(it, null) }
    )

    /** Зеркалит настройки из Room. Вызывается при каждом изменении настройки в интерфейсе. */
    public fun save(config: ObservationConfig) {
        prefs.edit().apply {
            config.toMap().forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    /**
     * Случайный идентификатор установки. Не IMEI и не рекламный идентификатор (ТЗ §7.7.1):
     * нужен только чтобы логи с разных телефонов различались и сводились вместе.
     */
    public fun installId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val generated = java.util.UUID.randomUUID().toString().take(INSTALL_ID_LENGTH)
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }

    private companion object {
        const val FILE_NAME = "observation"
        const val KEY_INSTALL_ID = "install_id"
        const val INSTALL_ID_LENGTH = 8
    }
}
