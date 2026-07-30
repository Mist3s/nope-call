package com.mist3s.nopecall.core.role

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Состояние настройки приложения (ТЗ §4.1, §4.4).
 *
 * Ключевое различие, которое обязано быть видно в интерфейсе: **роль выдана** и **блокировка
 * работает** — не одно и то же. Без роли сервис не вызывается вообще, поэтому обещать «блокировка
 * активна» нельзя (критерий приёмки §18 п. 2).
 */
public data class SetupState(
    val hasRole: Boolean,
    val hasCallLog: Boolean,
    val hasContacts: Boolean,
    val hasNotifications: Boolean,
    val hasPhoneState: Boolean,
    val blockingEnabled: Boolean,
    val enabledRuleCount: Int,
    /** Когда сервис проверки вызывался в последний раз. */
    val lastScreeningAt: Long?,
) {
    /**
     * Блокировка действительно работает: есть роль, включён выключатель и есть хотя бы одно
     * правило. Без правил блокировать нечего, и говорить «активна» — вводить в заблуждение.
     */
    public val blockingActive: Boolean
        get() = hasRole && blockingEnabled && enabledRuleCount > 0

    /** Что мешает: список причин для интерфейса, по убыванию важности. */
    public val problems: List<SetupProblem>
        get() = buildList {
            if (!hasRole) add(SetupProblem.NO_ROLE)
            if (!blockingEnabled) add(SetupProblem.DISABLED_BY_USER)
            if (enabledRuleCount == 0) add(SetupProblem.NO_RULES)
            if (!hasCallLog) add(SetupProblem.NO_CALL_LOG)
            if (!hasContacts) add(SetupProblem.NO_CONTACTS)
            if (!hasNotifications) add(SetupProblem.NO_NOTIFICATIONS)
        }
}

public enum class SetupProblem {
    /** Без роли сервис не вызывается — блокировка не работает вообще. */
    NO_ROLE,
    DISABLED_BY_USER,
    NO_RULES,

    /** Без него журнал показывает только наши проверки: исход и длительность неизвестны. */
    NO_CALL_LOG,

    /** Без него система обычно не присылает на проверку звонки от сохранённых контактов. */
    NO_CONTACTS,
    NO_NOTIFICATIONS,
}

/**
 * Роль средства проверки звонков.
 *
 * Роль может быть отозвана в любой момент — например, если пользователь назначил другое
 * приложение. Поэтому состояние перечитывается при выводе интерфейса, а не кешируется
 * на время жизни процесса (ТЗ §4.4).
 */
public class RoleController(private val context: Context) {

    public fun hasRole(): Boolean {
        val manager = context.getSystemService(RoleManager::class.java) ?: return false
        return manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /** Интент системного диалога запроса роли. `null`, если роль недоступна на устройстве. */
    public fun requestIntent(): Intent? {
        val manager = context.getSystemService(RoleManager::class.java) ?: return null
        if (!manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        return manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    public fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    public fun state(blockingEnabled: Boolean, enabledRuleCount: Int, lastScreeningAt: Long?): SetupState =
        SetupState(
            hasRole = hasRole(),
            hasCallLog = hasPermission(android.Manifest.permission.READ_CALL_LOG),
            hasContacts = hasPermission(android.Manifest.permission.READ_CONTACTS),
            hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // До Android 13 разрешение не запрашивается: считать его отсутствующим было бы
                // ложной проблемой в интерфейсе.
                true
            },
            hasPhoneState = hasPermission(android.Manifest.permission.READ_PHONE_STATE),
            blockingEnabled = blockingEnabled,
            enabledRuleCount = enabledRuleCount,
            lastScreeningAt = lastScreeningAt,
        )

    public companion object {
        public const val REQUEST_CODE: Int = 4291

        /** Была ли роль реально выдана после диалога. */
        public fun isGranted(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
    }
}
