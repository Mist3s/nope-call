package com.mist3s.nopecall.engine

import java.text.Normalizer

/**
 * Канонизация названия звонящего (ТЗ §6.3.2).
 *
 * Задача: чтобы шаблон `реклама` совпал с подписью `Reklama`, `Romashka.Reklama`,
 * `OOO_Romashka_Reklama` и `OOORomashkaReklama`. Для этого **и название, и шаблон** проходят
 * один и тот же конвейер — как `8` → `+7` для номеров.
 *
 * Конвейер, по шагам:
 *  1. NFKC — полноширинные и составные символы к обычным;
 *  2. удаление невидимых (нулевой ширины, мягкий перенос, BOM) — это и приём обхода, и мусор
 *     из систем оператора;
 *  3. деление по **первому** `:` на наименование и категорию;
 *  4. сведение омоглифов в словах со смешанным алфавитом;
 *  5. нижний регистр (локале-независимый: локаль устройства не должна менять сопоставление);
 *  6. разделители в один пробел;
 *  7. транслитерация каждого слова в латиницу.
 *
 * Разбивать на слова разрешено **только по разделителям, но не по смене регистра**:
 * транслитерация даёт `POChTA` и `SHCHerbakov`, и camelCase-разбор превратил бы `POChTA`
 * в `PO` + `Ch` + `TA`.
 */
public object NameCanonizer {

    /** Невидимые символы: убираются, а не заменяются на пробел. */
    private const val INVISIBLE = "​‌‍⁠﻿­"

    /** Пробельные: сводятся к пробелу. */
    private const val SPACES = "     \t\n\r"

    /**
     * Разделители внутри названия. `:` в список НЕ входит: он делит наименование и категорию
     * и обрабатывается раньше.
     */
    private const val SEPARATORS = ".,;_/\\|·—–-+()[]{}\"'«»…*&#№"

    /**
     * Служебные метки оператора: это не наименование организации, а пометка о вызове.
     *
     * Наблюдено `Zvonok bez markirovki`. Опознавать их нужно, иначе в статистике появится
     * «компания „Звонок без маркировки“», а правило по наименованию будет ловить метку.
     * Текст зависит от оператора и пополняется по данным режима наблюдения (ТЗ §6.3.1).
     */
    private val OPERATOR_LABEL_MARKERS: List<String> = listOf(
        "bezmarkirovki",
        "spam",
        "moshennik",
    )

    /**
     * @param categoryDictionary корни категорий для случая, когда категория стоит без `:`
     *   (наблюдено `AYSBERG-ZAPAD Transport`). Пополняется из режима наблюдения, поэтому
     *   передаётся снаружи, а не захардкожен.
     */
    public fun canonize(
        raw: String?,
        categoryDictionary: Set<String> = emptySet(),
    ): NameForms {
        if (raw.isNullOrBlank()) return NameForms.NONE

        val cleaned = clean(raw)
        val whole = canonicalText(raw, cleaned)

        val colon = cleaned.indexOf(':')
        var orgSource: String
        var categorySource: String?

        if (colon >= 0) {
            // Формат `[Наименование]: [Категория]` — 8 подписей из 13 в наблюдённом корпусе.
            orgSource = cleaned.substring(0, colon)
            categorySource = cleaned.substring(colon + 1).ifBlank { null }
        } else {
            orgSource = cleaned
            categorySource = null
            // Двоеточия нет — пробуем узнать категорию по словарю в последних словах.
            if (categoryDictionary.isNotEmpty()) {
                val words = cleaned.trim().split(' ').filter { it.isNotBlank() }
                for (take in 2 downTo 1) {
                    if (words.size <= take) continue
                    val tail = words.takeLast(take).joinToString(" ")
                    if (matchesDictionary(tail, categoryDictionary)) {
                        orgSource = words.dropLast(take).joinToString(" ")
                        categorySource = tail
                        break
                    }
                }
            }
        }

        return NameForms(
            whole = whole,
            org = canonicalText(orgSource.trim(), clean(orgSource)),
            category = categorySource?.let { canonicalText(it.trim(), clean(it)) },
            isOperatorLabel = OPERATOR_LABEL_MARKERS.any { whole.fold.contains(it) },
        )
    }

    /** Канонизирует шаблон правила тем же конвейером — иначе сопоставление не сойдётся. */
    public fun canonizePattern(pattern: String): String = canonicalText(pattern, clean(pattern)).fold

    /** Токены шаблона: для типа «содержит слово». */
    public fun patternTokens(pattern: String): List<String> =
        canonicalText(pattern, clean(pattern)).tokens

    /**
     * Варианты написания канонизированного шаблона (ТЗ §6.3.2, слой 2).
     *
     * Публично, потому что редактор правил обязан показать пользователю список того, что
     * правило будет искать. Непрозрачное нечёткое сравнение здесь недопустимо: ложное
     * срабатывание означает пропущенный звонок от врача.
     */
    public fun variantsOf(
        canonicalPattern: String,
        leet: Boolean = false,
        limit: Int = 64,
    ): List<String> = Translit.variants(canonicalPattern, leet, limit)

    /**
     * Хвост считается категорией только если **каждое** его слово опознано словарём.
     *
     * Проверять склейку хвоста было бы ошибкой: `Agent Rostelecom` начинается с корня `agen`,
     * но это часть наименования, а не категория, — и такая подпись реально наблюдалась.
     * Сомнение трактуется в пользу наименования: не опознали категорию — значит её нет
     * (ТЗ §1.1, то же правило, что для решения по звонку).
     */
    private fun matchesDictionary(tail: String, dictionary: Set<String>): Boolean {
        val words = tail.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        return words.all { word ->
            val fold = Translit.toLatinFold(word)
            fold.isNotEmpty() && dictionary.any { root -> fold.startsWith(root) }
        }
    }

    /** Шаги 1–2: NFKC и удаление невидимых. Делается до деления по `:`. */
    private fun clean(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return buildString(nfkc.length) {
            for (ch in nfkc) {
                when {
                    ch in INVISIBLE -> {} // выбрасываем
                    ch in SPACES -> append(' ')
                    else -> append(ch)
                }
            }
        }
    }

    /** Шаги 4–7. */
    private fun canonicalText(raw: String, cleaned: String): CanonicalText {
        // Разделители в пробелы — до омоглифов, чтобы слова определялись правильно.
        val spaced = buildString(cleaned.length) {
            for (ch in cleaned) append(if (ch in SEPARATORS || ch == ':') ' ' else ch)
        }

        val words = spaced.split(' ').filter { it.isNotBlank() }
        val folded = words.map { Translit.foldHomoglyphs(it) }

        val norm = folded.joinToString(" ") { it.lowercase() }
        val tokens = folded.map { Translit.toLatinFold(it) }.filter { it.isNotEmpty() }

        return CanonicalText(
            raw = raw,
            norm = norm,
            tokens = tokens,
            fold = tokens.joinToString(""),
        )
    }
}
