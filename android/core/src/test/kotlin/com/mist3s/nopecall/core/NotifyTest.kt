package com.mist3s.nopecall.core

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.notify.BlockedCallNotifier
import com.mist3s.nopecall.core.notify.NotifyConfig
import com.mist3s.nopecall.core.notify.NotifyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Настройки уведомлений (ТЗ §9.6).
 *
 * Уведомление о блокировке — единственный способ узнать, что звонок был, если он отбит тихо.
 * Поэтому по умолчанию оно включено, а выключение обязано выключать по-настоящему: настройка,
 * которая ничего не меняет, хуже отсутствующей.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Разрешение на уведомления: без него проверялась бы не настройка, а его отсутствие.
        Shadows.shadowOf(context.applicationContext as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun manager() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `по умолчанию уведомления включены`() {
        val config = NotifyConfig.fromMap(emptyMap())
        assertTrue(config.blocked, "иначе тихий сброс станет невидимым")
        assertTrue(config.roleLost, "иначе отказ блокировки останется незамеченным")
    }

    @Test
    fun `настройка переживает запись и чтение`() {
        val store = NotifyStore(context)
        store.save(NotifyConfig(blocked = false, roleLost = true))

        val read = NotifyStore(context).config()
        assertFalse(read.blocked)
        assertTrue(read.roleLost)
    }

    @Test
    fun `выключенное уведомление о блокировке не отправляется`() {
        val store = NotifyStore(context)
        store.save(NotifyConfig(blocked = false))
        val notifier = BlockedCallNotifier(context, store)
        notifier.ensureChannels()

        notifier.notifyBlocked(who = "+74951234567", ruleId = 1, ruleTitle = "Москва")

        assertEquals(0, Shadows.shadowOf(manager()).size(), "уведомления быть не должно")
    }

    @Test
    fun `включённое уведомление отправляется`() {
        val store = NotifyStore(context)
        store.save(NotifyConfig(blocked = true))
        val notifier = BlockedCallNotifier(context, store)
        notifier.ensureChannels()

        notifier.notifyBlocked(who = "+74951234567", ruleId = 1, ruleTitle = "Москва")

        assertEquals(1, Shadows.shadowOf(manager()).size())
    }

    @Test
    fun `выключенное предупреждение о роли не отправляется`() {
        val store = NotifyStore(context)
        store.save(NotifyConfig(roleLost = false))
        val notifier = BlockedCallNotifier(context, store)
        notifier.ensureChannels()

        notifier.notifyRoleLost()

        assertEquals(0, Shadows.shadowOf(manager()).size())
    }

    @Test
    fun `канал блокировки беззвучный`() {
        // Звук у уведомления о предотвращённом звонке раздражает сильнее самого звонка.
        // Проверяется здесь, потому что после создания канала важность уже не изменить.
        BlockedCallNotifier(context).ensureChannels()

        val channel = manager()?.getNotificationChannel(BlockedCallNotifier.CHANNEL_BLOCKED)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel?.importance)
    }
}
