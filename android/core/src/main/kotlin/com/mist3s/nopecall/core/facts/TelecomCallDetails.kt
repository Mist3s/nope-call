package com.mist3s.nopecall.core.facts

import android.os.Build
import android.os.Bundle
import android.telecom.Call
import com.mist3s.nopecall.core.observe.ExtraEntry

/**
 * Единственное место, где живёт `Call.Details`.
 *
 * Обёртка намеренно тонкая — ровно перевод полей, без логики. Вся содержательная часть
 * построения фактов лежит в [CallFactsBuilder] и потому тестируется на голой JVM
 * (архитектура §12.1).
 */
internal class TelecomCallDetails(private val details: Call.Details) : CallDetailsReader {

    override val handleScheme: String?
        get() = details.handle?.scheme

    override val handleValue: String?
        get() = details.handle?.schemeSpecificPart

    override val handlePresentation: Int
        get() = details.handlePresentation

    override val callerDisplayName: String?
        get() = details.callerDisplayName

    override val callerDisplayNamePresentation: Int
        get() = details.callerDisplayNamePresentation

    override val verificationStatus: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Поле появилось в Android 11, а minSdk у нас 29 — на Android 10 его просто нет,
            // и это отдельное значение, а не «не проверен» (ТЗ §6.4).
            details.callerNumberVerificationStatus
        } else {
            null
        }

    override val creationTimeMillis: Long
        get() = details.creationTimeMillis

    override val callDirection: Int?
        get() = runCatching { details.callDirection }.getOrNull()

    override val connectTimeMillis: Long?
        get() = runCatching { details.connectTimeMillis }.getOrNull()

    override val accountHandle: String?
        get() = runCatching { details.accountHandle?.id }.getOrNull()

    /**
     * Всё, что система рассказывает о звонке, — включая то, чем построение фактов не пользуется.
     *
     * Каждое значение через `runCatching` по отдельности: одно недоступное поле не должно
     * лишать лога остальных. `toString()` самих деталей идёт целиком — там видно и те поля,
     * о которых мы не знаем, что их надо спрашивать.
     */
    override fun rawDump(): List<ExtraEntry> = buildList {
        fun add(key: String, value: Any?) {
            if (value != null) add(ExtraEntry(key, value.javaClass.simpleName, value.toString()))
        }
        add("details.toString", runCatching { details.toString() }.getOrNull())
        add("callProperties", runCatching { details.callProperties }.getOrNull())
        add("callCapabilities", runCatching { details.callCapabilities }.getOrNull())
        add("videoState", runCatching { details.videoState }.getOrNull())
        add("gatewayInfo", runCatching { details.gatewayInfo }.getOrNull())
        add("statusHints.label", runCatching { details.statusHints?.label }.getOrNull())
        add("callerDisplayName", runCatching { details.callerDisplayName }.getOrNull())
        add("handle", runCatching { details.handle }.getOrNull())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add("contactDisplayName", runCatching { details.contactDisplayName }.getOrNull())
        }
        // Подсказки статуса — ещё один `Bundle`, куда вендор может положить что угодно.
        addAll(dump(runCatching { details.statusHints?.extras }.getOrNull()).map {
            ExtraEntry("statusHints.${it.key}", it.type, it.value)
        })
    }

    override fun extrasDump(): List<ExtraEntry> = dump(runCatching { details.extras }.getOrNull())

    override fun intentExtrasDump(): List<ExtraEntry> =
        dump(runCatching { details.intentExtras }.getOrNull())

    private companion object {
        /**
         * Дамп `Bundle` с `try/catch` **на каждом ключе** (ТЗ §7.7.1).
         *
         * Именно поэтому дамп живёт здесь, а не в общем коде: в `extras` вендорская или
         * IMS-реализация может положить что угодно — объект чужого класса, чей `toString`
         * бросает, или значение, которое не разворачивается без нужного `ClassLoader`.
         * Один такой ключ не должен стоить нам всей записи о звонке.
         */
        fun dump(bundle: Bundle?): List<ExtraEntry> {
            if (bundle == null) return emptyList()
            val keys = try {
                bundle.keySet().toList()
            } catch (_: Throwable) {
                return emptyList()
            }
            return keys.take(MAX_KEYS).map { key ->
                try {
                    @Suppress("DEPRECATION") // типизированного доступа тут быть не может:
                    // тип значения заранее неизвестен, он и есть предмет наблюдения.
                    val value = bundle.get(key)
                    ExtraEntry(
                        key = key,
                        type = value?.javaClass?.simpleName ?: "null",
                        value = describe(value),
                    )
                } catch (t: Throwable) {
                    ExtraEntry(key = key, type = "error", value = t.javaClass.simpleName)
                }
            }
        }

        /** Вложенный `Bundle` разворачивается на один уровень: глубже начинается мусор. */
        fun describe(value: Any?): String? = when (value) {
            null -> null
            is Bundle -> dump(value).joinToString(", ") { "${it.key}=${it.value}" }
            is ByteArray -> value.joinToString("") { "%02x".format(it) }.take(MAX_VALUE_LENGTH)
            is Array<*> -> value.joinToString(", ") { it?.toString().orEmpty() }.take(MAX_VALUE_LENGTH)
            else -> runCatching { value.toString() }.getOrDefault("<toString упал>")
                .take(MAX_VALUE_LENGTH)
        }

        const val MAX_KEYS = 64
        const val MAX_VALUE_LENGTH = 512
    }
}
