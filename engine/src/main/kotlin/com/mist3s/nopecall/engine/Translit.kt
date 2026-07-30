package com.mist3s.nopecall.engine

/**
 * Транслитерация кириллицы в латиницу и сведение омоглифов (ТЗ §6.3.2).
 *
 * Направление именно в латиницу, потому что операторские подписи приходят латиницей:
 * `POChTA Ros.`, `BANK RUSSKIY STANDART`, `OOO Poleznyy Zvonok`. Шаблон правила проходит
 * тот же конвейер, поэтому пользователь может написать «почта» и поймать `POChTA`.
 */
internal object Translit {

    /**
     * Канонический вариант транслитерации. Одна буква — одна замена, без вариантов:
     * варианты добавляются отдельным слоем (см. [variants]) и только по просьбе пользователя.
     */
    private val TABLE: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
        'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
        'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya",
        // украинские и белорусские буквы встречаются в наименованиях
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g", 'ў' to "u",
    )

    /**
     * Кириллические буквы, визуально совпадающие с латинскими.
     *
     * Нужны для случая, когда латинский текст «нарисован» кириллицей: `Rеklamа` с кириллическими
     * `е` и `а`. Такое встречается и случайно (копирование из систем оператора), и как приём
     * обхода фильтров. Применяется НЕ всегда — см. [foldHomoglyphs].
     */
    private val HOMOGLYPHS: Map<Char, Char> = mapOf(
        'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
        'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X',
        'І' to 'I', 'Ј' to 'J', 'Ѕ' to 'S',
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y',
        'х' to 'x', 'і' to 'i', 'ј' to 'j', 'ѕ' to 's',
    )

    private fun Char.isCyrillic(): Boolean = this in 'Ѐ'..'ӿ'

    private fun Char.isLatinLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /**
     * Сводит кириллические омоглифы к латинице, но **только внутри слова со смешанным
     * алфавитом, где латиница в большинстве**.
     *
     * Условие не косметическое. Слепое сведение сломало бы честную кириллицу: `СБЕР` целиком
     * кириллический, и его надо транслитерировать как `sber`, а не превращать в `CBEP`.
     * Слово со смешанным алфавитом — другое дело: это либо опечатка раскладки, либо обход
     * фильтра, и в обоих случаях кириллические буквы там означают латинские.
     *
     * Правило детерминировано и проверяется векторами — это не эвристика в смысле ТЗ §1.1.
     */
    fun foldHomoglyphs(token: String): String {
        var latin = 0
        var cyrillic = 0
        for (ch in token) {
            when {
                ch.isLatinLetter() -> latin++
                ch.isCyrillic() -> cyrillic++
            }
        }
        if (latin == 0 || cyrillic == 0 || latin <= cyrillic) return token
        return buildString(token.length) {
            for (ch in token) append(HOMOGLYPHS[ch] ?: ch)
        }
    }

    /** Транслитерирует строку в латиницу, оставляя только `[a-z0-9]`. */
    fun toLatinFold(text: String): String = buildString(text.length + 4) {
        for (ch in text.lowercase()) {
            val mapped = TABLE[ch]
            when {
                mapped != null -> append(mapped)
                ch in 'a'..'z' || ch in '0'..'9' -> append(ch)
                // всё остальное — разделители и знаки, в fold им места нет
            }
        }
    }

    /**
     * Классы взаимозаменяемых сочетаний: у одного и того же слова у разных операторов разный
     * транслит. Наблюдено в корпусе подписей: `OOO Poleznyy Zvonok` и `OOO Polezniy zvonok` —
     * одно юрлицо, два написания (`ый` → `yy` / `iy`).
     *
     * Порядок важен: длинные сочетания идут первыми, иначе `shch` разберётся как `sh` + `ch`.
     */
    private val CLASSES: List<List<String>> = listOf(
        listOf("shch", "sch", "sh"),
        listOf("zh", "j"),
        listOf("kh", "h", "x"),
        listOf("ts", "c", "tc"),
        listOf("ch", "tch"),
        // `ый` — самый частый источник расхождений. Канонический транслит даёт `yi`
        // (ы→y, й→i), а в наблюдённых подписях одного и того же юрлица встречались `yy`
        // (`Poleznyy`) и `iy` (`Polezniy`). Без `yi` в классе канонический вид не сводится
        // ни к одному из наблюдённых, и правило по наименованию не срабатывает вообще.
        listOf("yy", "iy", "yi", "y", "i"),
        listOf("yu", "ju", "iu"),
        listOf("ya", "ja", "ia"),
        listOf("ye", "je", "eh", "e"),
    )

    /** Замены цифрами и символами: `R3KLAMA`, `0PROS`. Отдельный, более агрессивный слой. */
    private val LEET: Map<Char, List<Char>> = mapOf(
        'o' to listOf('0'), 'i' to listOf('1'), 'l' to listOf('1'),
        'e' to listOf('3'), 'a' to listOf('4'), 's' to listOf('5'),
    )

    /**
     * Раскрывает шаблон в набор вариантов написания.
     *
     * Раскрывается **шаблон, а не входные данные**: набор считается один раз при сохранении
     * правила и попадает в снимок, поэтому стоимость в момент звонка постоянна. Пользователю
     * список показывается в редакторе — непрозрачное нечёткое сравнение здесь недопустимо,
     * ложное срабатывание означает пропущенный звонок от врача (ТЗ §6.3.2).
     *
     * @param limit жёсткий предел. При превышении лишние отбрасываются, и вызывающий обязан
     *   предупредить пользователя.
     */
    fun variants(pattern: String, leet: Boolean = false, limit: Int = 64): List<String> {
        if (pattern.isEmpty()) return listOf(pattern)
        var result = mutableListOf(pattern)

        for (klass in CLASSES) {
            val next = LinkedHashSet<String>()
            for (candidate in result) {
                next += candidate
                for (from in klass) {
                    if (!candidate.contains(from)) continue
                    for (to in klass) {
                        if (to == from) continue
                        next += candidate.replace(from, to)
                    }
                }
            }
            result = next.take(limit).toMutableList()
            if (next.size > limit) break
        }

        if (leet) {
            val next = LinkedHashSet<String>(result)
            for (candidate in result) {
                for ((letter, digits) in LEET) {
                    if (!candidate.contains(letter)) continue
                    for (digit in digits) next += candidate.replace(letter, digit)
                }
            }
            result = next.take(limit).toMutableList()
        }

        return result.distinct()
    }
}
