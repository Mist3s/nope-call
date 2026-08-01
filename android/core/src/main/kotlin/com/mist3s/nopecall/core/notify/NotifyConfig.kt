package com.mist3s.nopecall.core.notify

import android.content.Context

/**
 * Настройки уведомлений (ТЗ §9.6).
 *
 * Отдельно от настроек решения: уведомление ни на что в проходе по правилам не влияет,
 * и пересобирать из-за него снимок незачем.
 *
 * Значения по умолчанию — включено. Уведомление о блокировке для тихого сброса и режима
 * «без звука» — **единственный** способ узнать, что звонок вообще был; выключать его
 * по умолчанию значило бы прятать от пользователя то, что приложение сделало.
 */
public data class NotifyConfig(
    /** Уведомлять о заблокированном звонке. */
    val blocked: Boolean = true,
    /** Уведомлять, если у приложения отозвали роль средства проверки звонков. */
    val roleLost: Boolean = true,
) {
    public fun toMap(): Map<String, String> = mapOf(
        KEY_BLOCKED to blocked.toString(),
        KEY_ROLE_LOST to roleLost.toString(),
    )

    public companion object {
        public const val KEY_BLOCKED: String = "notify_blocked"
        public const val KEY_ROLE_LOST: String = "notify_role_lost"

        public val KEYS: List<String> = listOf(KEY_BLOCKED, KEY_ROLE_LOST)

        public fun fromMap(values: Map<String, String?>): NotifyConfig {
            val defaults = NotifyConfig()
            fun bool(key: String, fallback: Boolean) =
                values[key]?.toBooleanStrictOrNull() ?: fallback
            return NotifyConfig(
                blocked = bool(KEY_BLOCKED, defaults.blocked),
                roleLost = bool(KEY_ROLE_LOST, defaults.roleLost),
            )
        }
    }
}

/**
 * Хранилище настроек уведомлений в Device Protected Storage.
 *
 * Room здесь использовать нельзя по той же причине, что и у режима наблюдения: уведомление
 * о блокировке отправляется сразу после ответа Telecom, в том числе до первой разблокировки
 * экрана, когда база недоступна. Room остаётся источником истины для интерфейса и зеркалит
 * сюда при каждом изменении.
 */
public class NotifyStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    public fun config(): NotifyConfig = NotifyConfig.fromMap(
        NotifyConfig.KEYS.associateWith { prefs.getString(it, null) }
    )

    public fun save(config: NotifyConfig) {
        prefs.edit().apply {
            config.toMap().forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    private companion object {
        const val FILE_NAME = "notifications"
    }
}
