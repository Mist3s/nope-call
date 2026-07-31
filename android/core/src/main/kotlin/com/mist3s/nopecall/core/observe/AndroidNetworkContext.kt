package com.mist3s.nopecall.core.observe

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Контекст сети через `TelephonyManager` (ТЗ §7.7.1).
 *
 * Собирается **после** ответа системе: наличие операторской подписи зависит от сети и от VoLTE
 * (§6.3.1), но узнавать это до решения бессмысленно — решение от сети не зависит, а вызовы
 * `TelephonyManager` на холодном старте стоят миллисекунды.
 *
 * Всё через `runCatching`: `getDataNetworkType` и признак IMS требуют `READ_PHONE_STATE`,
 * которое необязательно, а часть прошивок бросает и при наличии разрешения. Пустой контекст —
 * нормальный результат, а не ошибка.
 */
internal class AndroidNetworkContext(private val context: Context) {

    fun read(): NetworkContext {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
            ?: return NetworkContext.UNKNOWN

        return NetworkContext(
            networkType = runCatching { name(tm.dataNetworkType) }.getOrNull(),
            volte = runCatching { volte(tm) }.getOrNull(),
            operatorName = runCatching { tm.simOperatorName?.takeIf { it.isNotBlank() } }.getOrNull(),
            roaming = runCatching { tm.isNetworkRoaming }.getOrNull(),
            simSlot = null,
        )
    }

    /**
     * Признак VoLTE. Публичного API «есть ли VoLTE прямо сейчас» нет, поэтому берётся
     * доступное приближение: сеть LTE/NR и включённая передача голоса по IMS.
     *
     * Приближение обозначено как приближение сознательно: строить на нём фичи нельзя,
     * а для ответа «почему подписи не было» его достаточно.
     */
    private fun volte(tm: TelephonyManager): Boolean? {
        val type = runCatching { tm.dataNetworkType }.getOrNull() ?: return null
        val modern = type == TelephonyManager.NETWORK_TYPE_LTE ||
            type == TelephonyManager.NETWORK_TYPE_NR
        if (!modern) return false
        // Голос по IMS: без него на LTE звонок уходит в 2G/3G через CSFB, и подписи там нет.
        return runCatching {
            tm.javaClass.getMethod("isVolteAvailable").invoke(tm) as? Boolean
        }.getOrNull() ?: modern
    }

    private fun name(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS,
        -> "3G"

        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM,
        -> "2G"

        TelephonyManager.NETWORK_TYPE_IWLAN -> "WIFI_CALLING"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "OTHER_$type"
    }
}
