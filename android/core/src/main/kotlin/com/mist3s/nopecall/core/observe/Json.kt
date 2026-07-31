package com.mist3s.nopecall.core.observe

/**
 * Минимальный писатель JSON (ТЗ §7.7.1).
 *
 * Своё, а не библиотека и не `org.json`, по двум причинам. Первая: нужен **строгий порядок
 * ключей** — `at` обязан быть первым, потому что выгрузка за период режет сегменты построчно
 * и достаёт метку времени из начала строки, не разбирая JSON целиком (§7.7.3). Вторая: писатель
 * работает сразу после ответа системе, и лишние аллокации здесь ни к чему.
 *
 * Читателя нет и не нужно: логи читает человек и внешние инструменты, приложение их только пишет.
 */
internal class Json {

    private val sb = StringBuilder(512)
    private var needsComma = false

    fun obj(block: Json.() -> Unit): Json {
        sb.append('{')
        val outerComma = needsComma
        needsComma = false
        block()
        sb.append('}')
        needsComma = outerComma
        return this
    }

    fun put(key: String, value: String?): Json {
        if (value == null) return this
        key(key)
        string(value)
        return this
    }

    fun put(key: String, value: Long?): Json {
        if (value == null) return this
        key(key)
        sb.append(value)
        return this
    }

    fun put(key: String, value: Int?): Json {
        if (value == null) return this
        key(key)
        sb.append(value)
        return this
    }

    fun put(key: String, value: Boolean?): Json {
        if (value == null) return this
        key(key)
        sb.append(if (value) "true" else "false")
        return this
    }

    /** Вложенный объект. Пустой не пишется: в логе он только мешает читать. */
    fun putObject(key: String, block: Json.() -> Unit): Json {
        val mark = sb.length
        key(key)
        val before = sb.length
        obj(block)
        // Ровно `{}` — значит внутри не оказалось ни одного значения. Убираем и ключ.
        if (sb.length - before == 2) {
            sb.setLength(mark)
            needsComma = mark > 0 && sb.isNotEmpty() && sb.last() != '{'
        }
        return this
    }

    fun putArray(key: String, values: List<String>): Json {
        if (values.isEmpty()) return this
        key(key)
        sb.append('[')
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            string(v)
        }
        sb.append(']')
        return this
    }

    /** Массив объектов: используется для дампа `extras`, где у каждого ключа свой тип. */
    fun putObjects(key: String, values: List<Json>): Json {
        if (values.isEmpty()) return this
        key(key)
        sb.append('[')
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            sb.append(v.sb)
        }
        sb.append(']')
        return this
    }

    private fun key(name: String) {
        if (needsComma) sb.append(',')
        string(name)
        sb.append(':')
        needsComma = true
    }

    /**
     * Экранирование по RFC 8259. Управляющие символы обязаны уходить в `\u….`
     *
     * Это не перестраховка: в операторской подписи встречались невидимые символы, а в дампе
     * `extras` может оказаться что угодно, включая двоичный мусор от вендорской реализации.
     * Одна неэкранированная строка ломает весь сегмент для любого читателя JSONL.
     */
    private fun string(value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                // U+2028/U+2029 в JSON допустимы, но ломают разбор строки как JavaScript —
                // а смотреть логи почти наверняка будут именно такими инструментами.
                else -> if (ch < ' ' || ch == '\u2028' || ch == '\u2029') {
                    sb.append("\\u").append(HEX[(ch.code shr 12) and 0xF])
                        .append(HEX[(ch.code shr 8) and 0xF])
                        .append(HEX[(ch.code shr 4) and 0xF])
                        .append(HEX[ch.code and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    override fun toString(): String = sb.toString()

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        /** Одна строка JSONL. */
        fun line(block: Json.() -> Unit): String = Json().obj(block).toString()

        /**
         * Метка времени из строки JSONL без разбора всего объекта.
         *
         * Держится на том, что writer всегда кладёт `at` первым ключом. Так и задумано:
         * выгрузка за период режет сегменты построчно и потоково, и разбирать ради этого
         * каждый объект — значит превратить выгрузку 500 МБ в неприемлемо долгую операцию.
         *
         * @return `null`, если строка не наша или обрезана.
         */
        fun timestampOf(line: String): Long? {
            if (!line.startsWith(PREFIX)) return null
            val end = line.indexOf(',', PREFIX.length)
            val slice = if (end < 0) line.substring(PREFIX.length) else line.substring(PREFIX.length, end)
            return slice.toLongOrNull()
        }

        private const val PREFIX = "{\"at\":"
    }
}
