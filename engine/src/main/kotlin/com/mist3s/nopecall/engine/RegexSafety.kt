package com.mist3s.nopecall.engine

/** Бюджет исчерпан во время обхода строки регулярным выражением. */
public class RegexBudgetExceeded : RuntimeException("бюджет regex исчерпан") {
    // Стек не нужен: исключение управляющее, бросается в горячем пути и ловится рядом.
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Прерываемая обёртка над входной строкой — единственный работающий способ остановить
 * катастрофический backtracking (архитектура §6.5).
 *
 * В `java.util.regex` нет таймаута, поэтому прерывается сам обход символов.
 *
 * **Ограничение контракта, зафиксированное тестом.** Обёртка пригодна только для булева
 * сопоставления (`find`, `matches`). `Matcher.group()` обходит символы уже ПОСЛЕ успешного
 * совпадения, и при исчерпанном бюджете бросит [RegexBudgetExceeded] на найденном совпадении.
 * Потребители, которым нужно показать «что именно совпало» — проверка шаблона в редакторе
 * и тестовый прогон в диагностике, — обязаны извлекать группы из исходной строки.
 */
public class DeadlineCharSequence private constructor(
    private val src: CharSequence,
    private val state: State,
) : CharSequence {

    /** Состояние одной проверки. Разделяется с подпоследовательностями. */
    public class State(
        internal val deadlineNanos: Long,
        internal val maxReads: Int,
        internal val clock: () -> Long,
    ) {
        internal var reads: Int = 0
    }

    override val length: Int get() = src.length

    override fun get(index: Int): Char {
        // Часы опрашиваются раз в 1024 обращения. System.nanoTime() ~25 нс, и на пределе
        // 200 000 обращений это 4–5 мс чистых накладных внутри 10-миллисекундного бюджета.
        if (++state.reads > state.maxReads ||
            (state.reads and CLOCK_MASK == 0 && state.clock() > state.deadlineNanos)
        ) {
            throw RegexBudgetExceeded()
        }
        return src[index]
    }

    /**
     * Обёртка, а не делегирование. Если вернуть исходную подпоследовательность, `Pattern`
     * уйдёт от бюджета через неё, и защита перестанет работать.
     */
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(src.subSequence(startIndex, endIndex), state)

    override fun toString(): String = src.toString()

    public companion object {
        private const val CLOCK_MASK = 0x3FF
        public const val DEFAULT_MAX_READS: Int = 200_000

        /** Создаётся заново на каждое правило: счётчик — состояние одной проверки. */
        public fun forRule(
            input: CharSequence,
            budgetNanos: Long,
            maxReads: Int = DEFAULT_MAX_READS,
            clock: () -> Long = System::nanoTime,
        ): DeadlineCharSequence =
            DeadlineCharSequence(input, State(clock() + budgetNanos, maxReads, clock))
    }
}

/**
 * Извлечение обязательного литерала из регулярного выражения — префильтр (архитектура §6.4).
 *
 * Ошибка здесь безопасна в одну сторону: если литерал извлечён неверно, правило будет
 * пропущено, то есть звонок пройдёт. Это направление согласовано с ТЗ §1.1, поэтому разбор
 * сознательно консервативен — при любой неоднозначности возвращается `null`, и правило
 * проверяется полным матчингом.
 */
internal object RegexLiteral {

    /** Минимальная длина: на коротких литералах префильтр не экономит. */
    private const val MIN_LENGTH = 3

    /** Экранированные последовательности, означающие класс символов, а не литерал. */
    private const val CLASS_SHORTHANDS = "dDwWsSbBAZzGQE"

    fun extract(pattern: String): String? {
        // Альтернация означает, что обязательного литерала может не быть вообще.
        if (containsTopLevelAlternation(pattern)) return null

        var caseInsensitive = false
        val best = StringBuilder()
        val current = StringBuilder()
        var i = 0

        fun flush() {
            if (current.length > best.length) {
                best.setLength(0)
                best.append(current)
            }
            current.setLength(0)
        }

        while (i < pattern.length) {
            when (val ch = pattern[i]) {
                '\\' -> {
                    if (i + 1 >= pattern.length) return null
                    val next = pattern[i + 1]
                    if (next in CLASS_SHORTHANDS) {
                        flush()
                    } else {
                        // Экранированный знак — обычный символ: \+ \. \? и т. п.
                        current.append(next)
                    }
                    i += 2
                    continue
                }

                '(' -> {
                    // Встроенные флаги вида (?i) группой не являются и литерал не ломают.
                    val flags = inlineFlags(pattern, i)
                    if (flags == null) return null // настоящая группа — не разбираем
                    if (flags.second.contains('i')) caseInsensitive = true
                    flush()
                    i = flags.first
                    continue
                }

                '[' -> {
                    val close = findClassEnd(pattern, i) ?: return null
                    flush()
                    i = close + 1
                    continue
                }

                '^', '$' -> {
                    flush()
                    i++
                    continue
                }

                '.' -> {
                    flush()
                    i++
                    continue
                }

                '?', '*' -> {
                    // Предыдущий символ необязателен — выбрасываем его из литерала.
                    if (current.isNotEmpty()) current.setLength(current.length - 1)
                    flush()
                    i++
                    continue
                }

                '+' -> {
                    // `a+` — как минимум одна `a`, литерал сохраняется, но прерывается.
                    flush()
                    i++
                    continue
                }

                '{' -> {
                    val close = pattern.indexOf('}', i)
                    if (close < 0) return null
                    val min = pattern.substring(i + 1, close).substringBefore(',').trim()
                    if (min == "0" && current.isNotEmpty()) current.setLength(current.length - 1)
                    flush()
                    i = close + 1
                    continue
                }

                ')', '|' -> return null

                else -> {
                    current.append(ch)
                    i++
                }
            }
        }
        flush()

        val literal = best.toString()
        if (literal.length < MIN_LENGTH) return null
        return if (caseInsensitive) literal.lowercase() else literal
    }

    private fun containsTopLevelAlternation(pattern: String): Boolean {
        var depth = 0
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++
                '(' -> depth++
                ')' -> depth--
                '|' -> if (depth == 0) return true
                '[' -> {
                    val close = findClassEnd(pattern, i) ?: return true
                    i = close
                }
            }
            i++
        }
        return false
    }

    /** Возвращает (индекс за закрывающей скобкой, флаги) для `(?flags)`, иначе `null`. */
    private fun inlineFlags(pattern: String, start: Int): Pair<Int, String>? {
        if (start + 1 >= pattern.length || pattern[start + 1] != '?') return null
        val close = pattern.indexOf(')', start)
        if (close < 0) return null
        val body = pattern.substring(start + 2, close)
        if (body.isEmpty() || !body.all { it in "idmsuxU-" }) return null
        return (close + 1) to body
    }

    private fun findClassEnd(pattern: String, start: Int): Int? {
        var i = start + 1
        if (i < pattern.length && pattern[i] == '^') i++
        if (i < pattern.length && pattern[i] == ']') i++ // `]` сразу после `[` — обычный символ
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> i++
                ']' -> return i
            }
            i++
        }
        return null
    }
}

/**
 * Проверка регулярного выражения при сохранении правила (ТЗ §6.5).
 *
 * Катастрофический шаблон не должен попасть в базу: если он там окажется, каждый звонок будет
 * упираться в бюджет, а пользователь увидит только «правило не сработало».
 */
public object RegexValidator {

    /** Предел длины шаблона (ТЗ §6.5). */
    public const val MAX_PATTERN_LENGTH: Int = 200

    /** Бюджет на прогон одного «злого» входа. */
    private const val PROBE_BUDGET_NANOS = 10_000_000L // 10 мс

    /**
     * Входы, на которых катастрофический backtracking проявляется. Длинные однородные строки —
     * худший случай для шаблонов вида `(a+)+b`.
     */
    private val EVIL_INPUTS: List<String> = listOf(
        "a".repeat(2_000),
        "0".repeat(2_000),
        "7" + "9".repeat(1_999),
        ("ab").repeat(1_000),
        "+7" + "0".repeat(1_998),
        "",
    )

    public fun validate(pattern: String): PatternCheck {
        if (pattern.isEmpty()) return PatternCheck.Invalid("пустое выражение")
        if (pattern.length > MAX_PATTERN_LENGTH) {
            return PatternCheck.Invalid("длиннее $MAX_PATTERN_LENGTH символов")
        }

        val regex = runCatching { Regex(pattern) }.getOrElse {
            return PatternCheck.Invalid(it.message ?: "выражение не компилируется")
        }

        for (input in EVIL_INPUTS) {
            val guarded = DeadlineCharSequence.forRule(input, PROBE_BUDGET_NANOS)
            try {
                regex.containsMatchIn(guarded)
            } catch (_: RegexBudgetExceeded) {
                return PatternCheck.TooExpensive(
                    "выражение не укладывается в 10 мс на строке длиной ${input.length}"
                )
            }
        }

        return PatternCheck.Ok(
            canonical = pattern,
            variants = emptyList(),
            literal = RegexLiteral.extract(pattern),
        )
    }
}
