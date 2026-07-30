package com.mist3s.nopecall.core

import com.mist3s.nopecall.core.facts.CallDetailsReader
import com.mist3s.nopecall.core.facts.CallDetailsReader.Companion.PRESENTATION_ALLOWED

/**
 * Подстановка вместо `Call.Details` — ровно то, ради чего введён шов [CallDetailsReader]
 * (архитектура §12.1).
 *
 * У `Call.Details` нет публичного конструктора, поэтому без этого класса построение фактов
 * можно было бы проверять только инструментальными тестами на устройстве, то есть почти никогда.
 */
internal data class FakeCallDetails(
    override val handleScheme: String? = "tel",
    override val handleValue: String? = "+79991234567",
    override val handlePresentation: Int = PRESENTATION_ALLOWED,
    override val callerDisplayName: String? = null,
    override val callerDisplayNamePresentation: Int = PRESENTATION_ALLOWED,
    override val verificationStatus: Int? = null,
    override val creationTimeMillis: Long = 0L,
) : CallDetailsReader
