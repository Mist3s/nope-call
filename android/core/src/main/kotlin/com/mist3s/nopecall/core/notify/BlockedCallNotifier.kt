package com.mist3s.nopecall.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mist3s.nopecall.core.R

/**
 * Уведомления о заблокированных звонках и о потере роли (ТЗ §9.6, §4.4).
 *
 * Уведомление о блокировке — единственный способ узнать, что звонок вообще был, если он отбит
 * без звука. Поэтому в нём обязательно **кто звонил и какое правило сработало**: «мы что-то
 * заблокировали» бесполезно, а «заблокировано правилом „8495“» позволяет правило поправить.
 *
 * Канал низкой важности: звук у уведомления о **предотвращённом** звонке раздражает сильнее
 * самого звонка.
 */
public class BlockedCallNotifier(private val context: Context) {

    public fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BLOCKED,
                context.getString(R.string.channel_blocked),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_blocked_description)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ROLE,
                context.getString(R.string.channel_role),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_role_description)
            }
        )
    }

    /** @param who номер или подпись — то, что увидел бы пользователь. */
    public fun notifyBlocked(who: String, ruleId: Long?, ruleTitle: String?) {
        if (!canNotify()) return
        val text = if (ruleTitle != null) {
            context.getString(R.string.blocked_by_rule, ruleTitle)
        } else {
            context.getString(R.string.blocked_no_rule)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_BLOCKED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(who.ifEmpty { context.getString(R.string.hidden_number) })
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        // Идентификатор по правилу: повторные звонки от одного источника не плодят уведомления.
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ID_BLOCKED_BASE + (ruleId ?: 0L).toInt(), notification)
        }
    }

    /**
     * Роль отозвана. Показывается потому, что иначе отказ невидим: сервис просто перестаёт
     * вызываться, и пользователь узнаёт об этом от пропущенного спама (ТЗ §4.4).
     */
    public fun notifyRoleLost() {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ROLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.role_lost_title))
            .setContentText(context.getString(R.string.role_lost_text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_ROLE_LOST, notification) }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    public companion object {
        public const val CHANNEL_BLOCKED: String = "blocked_calls"
        public const val CHANNEL_ROLE: String = "role_state"
        private const val ID_BLOCKED_BASE = 1000
        private const val ID_ROLE_LOST = 1
    }
}
