package com.mist3s.nopecall.core.facts

import com.mist3s.nopecall.core.facts.CallDetailsReader.Companion.PRESENTATION_ALLOWED
import com.mist3s.nopecall.core.facts.CallDetailsReader.Companion.PRESENTATION_PAYPHONE
import com.mist3s.nopecall.core.facts.CallDetailsReader.Companion.PRESENTATION_RESTRICTED
import com.mist3s.nopecall.engine.CallFacts
import com.mist3s.nopecall.engine.DecisionSettings
import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.NameForms
import com.mist3s.nopecall.engine.NameSource
import com.mist3s.nopecall.engine.NumberForms
import com.mist3s.nopecall.engine.NumberPresentation
import com.mist3s.nopecall.engine.PhoneNumberNormalizer

/**
 * Построение [CallFacts] из того, что отдала система (ТЗ §6.1, §6.3, архитектура §4.5).
 *
 * Бюджет — 30 мс, и он соблюдается тем, что здесь **нет** обращений к `ContentProvider`,
 * к сети и к Room: только чистые преобразования и заранее построенные индексы.
 */
public class CallFactsBuilder(
    private val normalizer: PhoneNumberNormalizer,
    private val contacts: ContactMembership = ContactMembership.UNKNOWN,
    private val emergency: EmergencyNumbers = EmergencyNumbers.NONE,
) {
    public fun build(details: CallDetailsReader, settings: DecisionSettings): CallFacts {
        val presentation = mapPresentation(details.handlePresentation)

        // Номер разбирается только когда система его отдала. У скрытого номера обработчика нет,
        // и придумывать его нельзя: сопоставлять будет нечего, и это штатный путь (ТЗ §5.4).
        val number = if (presentation == NumberPresentation.ALLOWED) {
            normalizer.normalize(rawHandle(details), settings.region)
        } else {
            NumberForms.EMPTY.copy(raw = rawHandle(details).orEmpty())
        }

        val name = resolveName(details, settings)

        return CallFacts(
            number = number,
            presentation = presentation,
            name = name.forms,
            nameSource = name.source,
            inContacts = contacts.contains(number.e164 ?: number.canonicalDigits.ifEmpty { null }),
            isEmergency = number.digits.isNotEmpty() && emergency.isEmergency(number.digits),
        )
    }

    /**
     * Название и его источник.
     *
     * Названия может не быть — это штатная ситуация, а не ошибка: тогда правила по названию
     * пропускаются, решение принимается по номеру, а в журнал пишется причина (ТЗ §6.3).
     * Служебные метки оператора вроде `Zvonok bez markirovki` опознаются отдельно, иначе
     * в статистике появилась бы «компания „Звонок без маркировки“» (ТЗ §6.3.1).
     */
    private fun resolveName(details: CallDetailsReader, settings: DecisionSettings): ResolvedName {
        val presentation = details.callerDisplayNamePresentation
        val raw = details.callerDisplayName?.takeIf { it.isNotBlank() }

        if (raw == null || presentation != PRESENTATION_ALLOWED) {
            return ResolvedName(NameForms.NONE, NameSource.NONE)
        }

        val forms = NameCanonizer.canonize(raw, settings.categoryDictionary)
        val source = if (forms.isOperatorLabel) NameSource.CNAP_OPERATOR_LABEL else NameSource.CNAP
        return ResolvedName(forms, source)
    }

    /**
     * Строка обработчика в виде, который понимает нормализатор.
     *
     * SIP-обработчик отдаётся со схемой: нормализатор по ней поймёт, что это не телефонный
     * номер, и не станет выдумывать E.164 (ТЗ §5.4).
     */
    private fun rawHandle(details: CallDetailsReader): String? {
        val value = details.handleValue ?: return null
        val scheme = details.handleScheme
        return if (scheme == null || scheme == SCHEME_TEL) value else "$scheme:$value"
    }

    private fun mapPresentation(value: Int): NumberPresentation = when (value) {
        PRESENTATION_ALLOWED -> NumberPresentation.ALLOWED
        PRESENTATION_RESTRICTED -> NumberPresentation.RESTRICTED
        PRESENTATION_PAYPHONE -> NumberPresentation.PAYPHONE
        // PRESENTATION_UNKNOWN и всё, чего мы не знаем: трактуем как «не определён».
        // Неизвестное значение не должно приводить к блокировке (ТЗ §1.1).
        else -> NumberPresentation.UNKNOWN
    }

    private data class ResolvedName(val forms: NameForms, val source: NameSource)

    private companion object {
        const val SCHEME_TEL = "tel"
    }
}
