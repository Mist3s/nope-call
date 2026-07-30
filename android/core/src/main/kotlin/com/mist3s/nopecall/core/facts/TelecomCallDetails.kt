package com.mist3s.nopecall.core.facts

import android.os.Build
import android.telecom.Call

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
}
