package com.mist3s.nopecall.engine

/**
 * Нормализация номера — **интерфейс** в движке, реализация в адаптере (архитектура §6.3).
 *
 * Три причины, по которым `libphonenumber` не может быть зависимостью движка: стоимость первой
 * загрузки метаданных региона не укладывается в бюджет `buildCallFacts` и должна прогреваться
 * вне звонка; приемлемый по размеру Android-порт требует `Context`; при переходе на KMP
 * библиотека всё равно требует замены.
 */
public interface PhoneNumberNormalizer {
    /**
     * @param raw строка обработчика как пришла от системы
     * @param region регион по умолчанию (`RU`) — для номеров без кода страны
     */
    public fun normalize(raw: String?, region: String): NumberForms
}

/**
 * Быстрый путь без библиотеки: цифры, магистральная `8` → `7`, длина 11 (архитектура §6.3).
 *
 * Покрывает подавляющее большинство реальных входов в РФ и не требует ни метаданных, ни
 * `Context`, поэтому годится и как реализация по умолчанию, и как основа для тестов движка.
 * Нестандартный вход (другая страна, добавочные сверх ожидаемых форм) отдаётся [fallback],
 * если он задан; иначе номер остаётся с тем, что удалось разобрать.
 *
 * Ключевое требование, ради которого всё это существует (ТЗ §6.2.1): шаблон `8495` обязан
 * поймать `+74951234567`, а шаблон `+7495` — поймать `84951234567`.
 */
public class RuFastPathNormalizer(
    private val fallback: PhoneNumberNormalizer? = null,
) : PhoneNumberNormalizer {

    override fun normalize(raw: String?, region: String): NumberForms {
        if (raw.isNullOrBlank()) return NumberForms.EMPTY

        val scheme = raw.substringBefore(':', missingDelimiterValue = "tel").let {
            if (it == raw) "tel" else it.lowercase()
        }
        val body = if (scheme == "tel") raw else raw.substringAfter(':')

        // Добавочные (`,` `;` `#` `*` после номера) в сопоставлении не участвуют (ТЗ §6.1).
        val withoutExtension = body.takeWhile { it != ',' && it != ';' }
        val digits = withoutExtension.filter { it.isDigit() }

        if (scheme != "tel") {
            return NumberForms(
                raw = raw,
                digits = digits,
                e164 = null,
                national = null,
                canonicalDigits = digits,
                isShort = false,
                scheme = scheme,
            )
        }

        if (digits.isEmpty()) return NumberForms.EMPTY.copy(raw = raw)

        // Короткие и служебные номера (900, 112) в E.164 не переводятся.
        if (digits.length <= SHORT_MAX_LENGTH) {
            return NumberForms(
                raw = raw,
                digits = digits,
                e164 = null,
                national = null,
                canonicalDigits = digits,
                isShort = true,
            )
        }

        if (region == RU) {
            ruForms(raw, digits)?.let { return it }
        }

        fallback?.let { return it.normalize(raw, region) }

        return NumberForms(
            raw = raw,
            digits = digits,
            e164 = null,
            national = null,
            canonicalDigits = digits,
            isShort = false,
        )
    }

    /**
     * Российские формы. Все три записи одного номера сводятся к одному виду:
     *   `+79991234567` / `89991234567` / `79991234567` / `9991234567` -> canonicalDigits `79991234567`.
     */
    private fun ruForms(raw: String, digits: String): NumberForms? {
        val national: String = when {
            // 8XXXXXXXXXX — магистральный префикс
            digits.length == 11 && digits[0] == '8' -> digits.substring(1)
            // 7XXXXXXXXXX — код страны
            digits.length == 11 && digits[0] == '7' -> digits.substring(1)
            // XXXXXXXXXX — без кода страны, регион берётся по умолчанию
            digits.length == 10 -> digits
            else -> return null
        }
        return NumberForms(
            raw = raw,
            digits = digits,
            e164 = "+$RU_COUNTRY_CODE$national",
            national = national,
            canonicalDigits = "$RU_COUNTRY_CODE$national",
            isShort = false,
        )
    }

    private companion object {
        const val RU = "RU"
        const val RU_COUNTRY_CODE = "7"

        /** До шести цифр — короткий/служебный номер. */
        const val SHORT_MAX_LENGTH = 6
    }
}
