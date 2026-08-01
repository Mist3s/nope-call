package com.mist3s.nopecall.core.sim

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager

/**
 * Человекочитаемая метка SIM по идентификатору телефонного аккаунта (ТЗ §7.4).
 *
 * В журнале хранится `phoneAccountId` — то, что отдаёт Telecom. На большинстве прошивок это
 * **ICCID**, то есть серийный номер карты вида `89701201869002096644`: в фильтре он бесполезен,
 * потому что не отвечает на вопрос «какая это карта».
 *
 * Имя оператора и номер слота живут в `SubscriptionManager` и требуют `READ_PHONE_STATE`,
 * которое по ТЗ §10 необязательное. Поэтому исходов два, и оба честные: с разрешением —
 * «МТС · SIM 1», без него — «Карта …6644». Придумывать номер слота без данных нельзя:
 * порядок появления в журнале со слотами не связан, и «SIM 1» оказалась бы догадкой.
 */
public interface SimLabels {
    /**
     * Метка для показа. Никогда не пустая: при неизвестном источнике — короткая форма.
     *
     * Признак [SimLabel.known] — **у каждой карты свой**, а не один на список. Разрешение может
     * быть выдано, а конкретная карта всё равно не сопоставиться: `phoneAccountId` из старой
     * записи журнала принадлежит карте, которую уже вынули. Общий флаг в этом случае убирал
     * пояснение с экрана, оставляя короткую форму без объяснения, — то есть снова утверждал
     * больше, чем известно.
     */
    public fun labelFor(phoneAccountId: String): SimLabel

    public companion object {
        /** Ничего не знает: только короткая форма. */
        public val FALLBACK: SimLabels = object : SimLabels {
            override fun labelFor(phoneAccountId: String): SimLabel =
                SimLabel(shortLabel(phoneAccountId), known = false)
        }

        /**
         * Короткая форма: последние четыре знака.
         *
         * Не «SIM 1» и не «первая карта»: и то и другое — утверждение о слоте, которого мы
         * не знаем. Четыре знака различают карты между собой, ничего не выдумывая.
         */
        public fun shortLabel(phoneAccountId: String): String {
            val trimmed = phoneAccountId.trim()
            if (trimmed.isEmpty()) return "Карта без метки"
            if (trimmed.length <= SHORT_TAIL) return "Карта $trimmed"
            return "Карта …${trimmed.takeLast(SHORT_TAIL)}"
        }

        private const val SHORT_TAIL = 4
    }
}

/**
 * Метка карты и признак, настоящая ли она.
 *
 * @param known `false` — показана короткая форма, и интерфейс обязан сказать, чего не хватает.
 */
public data class SimLabel(val text: String, val known: Boolean)

/**
 * Метки из `SubscriptionManager`.
 *
 * Читается **разово, по запросу интерфейса**, и результат не кэшируется дольше вызова: карту
 * можно вынуть и вставить другую, а устаревшая метка врёт молча. Обращение к telephony
 * в горячем пути недопустимо (архитектура §4.6), поэтому здесь его и нет: фильтр журнала
 * открывает пользователь.
 */
public class AndroidSimLabels(private val context: Context) : SimLabels {

    override fun labelFor(phoneAccountId: String): SimLabel {
        val id = phoneAccountId.trim()
        val fallback = SimLabel(SimLabels.shortLabel(id), known = false)
        if (id.isEmpty()) return fallback

        val match = subscriptions().firstOrNull { info ->
            // `phoneAccountId` — не обязательно ICCID: на части прошивок это идентификатор
            // подписки. Сравниваем с обоими, иначе метка молча не найдётся.
            info.iccId == id || info.subscriptionId.toString() == id
        } ?: return fallback

        val name = listOfNotNull(match.displayName, match.carrierName)
            .map { it.toString().trim() }
            .firstOrNull { it.isNotEmpty() }
        val slot = "SIM ${match.simSlotIndex + 1}"
        return SimLabel(if (name == null) slot else "$name · $slot", known = true)
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun subscriptions(): List<android.telephony.SubscriptionInfo> {
        if (!hasPermission()) return emptyList()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        // Всё через runCatching: на части прошивок вызов бросает даже при выданном разрешении,
        // а метка SIM не то, из-за чего журнал имеет право не открыться.
        return runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
    }
}
