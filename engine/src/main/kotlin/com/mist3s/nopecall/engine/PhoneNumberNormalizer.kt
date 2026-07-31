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
 *
 * Обратная сторона того же требования (ТЗ §1.1): переписывание `8` → `7` применимо **только**
 * к российской записи. У явно международной записи с чужим кодом страны ведущая `8` — начало
 * кода страны (`81` Япония, `84` Вьетнам, `86` Китай), и российская схема к ней неприменима.
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
            val foreign = foreignInternationalDigits(withoutExtension, digits)
            when {
                foreign == null -> ruForms(raw, digits)?.let { return it }
                // Резерв знает чужие планы нумерации, поэтому при его наличии слово ему: он
                // выделит настоящую национальную часть, а не оставит цифры как есть.
                fallback == null -> foreignForms(raw, digits, foreign)?.let { return it }
            }
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
     * Цифры международной записи, если запись **явно** международная и код страны **не**
     * российский; иначе `null` — номер разбирается по российской схеме.
     *
     * Признаком международной записи считаются только `+` перед первой цифрой и префикс выхода
     * на международную линию `00`. Больше ничего признаком быть не может: ведущая `8` в РФ —
     * магистральный префикс, и трактовать её как начало кода страны означало бы гадать, а гадать
     * запрещено (ТЗ §1.1). Отсюда и асимметрия: `89991234567` — российский междугородний,
     * а `+81312345678` — японский, хотя цифры начинаются одинаково.
     */
    private fun foreignInternationalDigits(body: String, digits: String): String? {
        // Отбрасываем всё до первой цифры или `+`: так одинаково читаются `+7…`, `tel:+7…`
        // и ` (495)…`, и признак не теряется из-за схемы или скобок.
        val marked = body.dropWhile { !it.isDigit() && it != '+' }
        val hasPlus = marked.startsWith('+')
        if (!hasPlus && !digits.startsWith(INTERNATIONAL_PREFIX)) return null

        // `00` — не часть номера, а способ набора, поэтому в код страны он не входит.
        // Благодаря этому `0081312345678` и `+81312345678` сводятся к одному виду.
        val countryDigits = if (hasPlus) digits else digits.removePrefix(INTERNATIONAL_PREFIX)

        // Код страны 7 — российский номер, просто записанный международно. Его разбирает
        // российская схема, иначе `+79991234567` перестал бы совпадать с `89991234567`.
        return countryDigits.takeIf { !it.startsWith(RU_COUNTRY_CODE) }
    }

    /**
     * Формы международного номера чужой страны: цифры остаются как есть.
     *
     * `national` здесь принципиально `null`: национальной части чужого плана нумерации быстрый
     * путь не знает, а если её выдумать, она попадёт в [NumberForms.candidates] и правило
     * пользователя сработает по обрезку чужого номера. По той же причине не строится и
     * российская форма — из-за неё японский `+81312345678` превращался в `71312345678`
     * и попадал под правило «начинается с 7» (ТЗ §1.1).
     *
     * `e164` воспроизводит саму запись, а не догадку: международный признак в ней был явный,
     * значит цифры после него — это уже код страны с национальным номером.
     *
     * Формы строятся только для длин, которые иначе забрал бы российский разбор. Прочие длины
     * быстрый путь и раньше не разбирал — их отдаёт резерв, и менять это поведение правка
     * не должна, иначе она переопределит более знающую реализацию.
     */
    private fun foreignForms(raw: String, digits: String, countryDigits: String): NumberForms? {
        if (countryDigits.length != RU_LENGTH_WITH_CODE && countryDigits.length != RU_LENGTH_NATIONAL) {
            return null
        }
        return NumberForms(
            raw = raw,
            digits = digits,
            e164 = "+$countryDigits",
            national = null,
            canonicalDigits = countryDigits,
            isShort = false,
        )
    }

    /**
     * Российские формы. Все три записи одного номера сводятся к одному виду:
     *   `+79991234567` / `89991234567` / `79991234567` / `9991234567` -> canonicalDigits `79991234567`.
     */
    private fun ruForms(raw: String, digits: String): NumberForms? {
        val national: String = when {
            // 8XXXXXXXXXX — магистральный префикс. Срезать его можно только потому, что запись
            // не международная: проверка сделана вызывающей стороной.
            digits.length == RU_LENGTH_WITH_CODE && digits[0] == '8' -> digits.substring(1)
            // 7XXXXXXXXXX — код страны
            digits.length == RU_LENGTH_WITH_CODE && digits[0] == '7' -> digits.substring(1)
            // XXXXXXXXXX — без кода страны, регион берётся по умолчанию
            digits.length == RU_LENGTH_NATIONAL -> digits
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

        /** Префикс выхода на международную линию: явный признак того, что дальше код страны. */
        const val INTERNATIONAL_PREFIX = "00"

        /** Длины, которые забирает российский разбор: с кодом страны или магистральной `8`… */
        const val RU_LENGTH_WITH_CODE = 11

        /** …и без них. */
        const val RU_LENGTH_NATIONAL = 10

        /** До шести цифр — короткий/служебный номер. */
        const val SHORT_MAX_LENGTH = 6
    }
}
