package com.mist3s.nopecall.updater

/**
 * Ошибка формата JSON (ТЗ §15.5).
 *
 * Сообщение по-русски и пригодно для показа на экране «О приложении»: единственный способ
 * узнать, что релиз опубликован с битым `latest.json`, — прочитать текст ошибки, а не
 * «неизвестный сбой».
 */
internal class JsonFormatException(message: String) : Exception(message)

/**
 * Минимальный разбор JSON без внешних библиотек (ТЗ §15.5).
 *
 * Своё, а не `org.json` из Android SDK, по причине проверяемости. Unit-тесты модуля идут
 * на голой JVM, где вместо реального `android.jar` подставляется «mockable» вариант: методы
 * `org.json` там не бросают ошибку, а **молча возвращают значения по умолчанию**. Разбор
 * «работал» бы в тестах, отдавая null-поля, и битый `latest.json` выглядел бы как валидный —
 * то есть тесты на битый JSON, которых требует задание, ничего бы не проверяли. Robolectric
 * ради одного разбора JSON — лишняя зависимость в модуле, которому и одной много.
 *
 * Разбор строгий: лишний мусор после значения, обрезанный ввод и дубликат ключа — отказ.
 * Отказ здесь безопасен: не удалось разобрать манифест — обновление просто не предлагается
 * (принцип ТЗ §1.1 в применении к установке: действуем только при уверенности).
 */
internal object MiniJson {

    /**
     * Ограничение вложенности. `latest.json` и ответ GitHub Releases вложены на 3–4 уровня,
     * так что предел щедрый. Он не про наши файлы: подменённый ответ из тысячи открытых
     * скобок иначе уронил бы процесс переполнением стека ещё до любых проверок.
     */
    private const val MAX_DEPTH = 32

    /** @throws JsonFormatException если ввод не JSON, обрезан или содержит дубликат ключа */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value(depth = 0)
        reader.skipWhitespace()
        if (!reader.atEnd()) {
            throw JsonFormatException("после JSON остались лишние данные на позиции ${reader.position()}")
        }
        return value
    }

    private class Reader(private val src: String) {

        private var i = 0

        fun position(): Int = i

        fun atEnd(): Boolean = i >= src.length

        fun skipWhitespace() {
            // Пробельные символы строго по RFC 8259: пробел, таб, CR, LF. Всё прочее — данные.
            while (i < src.length && (src[i] == ' ' || src[i] == '\t' || src[i] == '\r' || src[i] == '\n')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonFormatException("слишком глубокая вложенность JSON")
            skipWhitespace()
            if (atEnd()) throw JsonFormatException("неожиданный конец JSON")
            return when (val c = src[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else ->
                    if (c == '-' || c in '0'..'9') number()
                    else throw JsonFormatException("неожиданный символ '$c' на позиции $i")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonFormatException("ожидалось имя ключа на позиции $i")
                val key = string()
                skipWhitespace()
                expect(':')
                val v = value(depth + 1)
                // Дубликат — отказ, а не «побеждает последний». Манифест с двумя `sha256`
                // читался бы нами и человеком по-разному, а это ровно тот случай, когда
                // расхождение означает подмену, а не опечатку.
                if (result.containsKey(key)) throw JsonFormatException("ключ \"$key\" встречается дважды")
                result[key] = v
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return result
                    else -> throw JsonFormatException("ожидалось ',' или '}' вместо '$c' на позиции ${i - 1}")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return result
            }
            while (true) {
                result += value(depth + 1)
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return result
                    else -> throw JsonFormatException("ожидалось ',' или ']' вместо '$c' на позиции ${i - 1}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(escaped())
                    // Управляющие символы в строке запрещены стандартом. Проверка не
                    // формальность: обрезанный по середине строки файл иначе выглядел бы
                    // как строка с переводом строки внутри.
                    c < ' ' -> throw JsonFormatException("управляющий символ внутри строки на позиции ${i - 1}")
                    else -> sb.append(c)
                }
            }
        }

        private fun escaped(): Char = when (val c = next()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> unicode()
            else -> throw JsonFormatException("неизвестная escape-последовательность '\\$c' на позиции ${i - 1}")
        }

        private fun unicode(): Char {
            if (i + 4 > src.length) throw JsonFormatException("обрезанная последовательность \\u на позиции $i")
            val hex = src.substring(i, i + 4)
            val code = hex.toIntOrNull(16)
                ?: throw JsonFormatException("неверная последовательность \\u$hex на позиции $i")
            i += 4
            return code.toChar()
        }

        /**
         * Числа. Целое отдаётся как [Long], дробное — как [Double].
         *
         * Целое отдельным типом нужно для `build` и `size`: через `Double` значения около 2^53
         * потеряли бы точность, а `size` сравнивается с фактически скачанным числом байт.
         */
        private fun number(): Any {
            val start = i
            if (peek() == '-') i++
            while (!atEnd() && src[i] in '0'..'9') i++
            var fractional = false
            if (peek() == '.') {
                fractional = true
                i++
                while (!atEnd() && src[i] in '0'..'9') i++
            }
            if (peek() == 'e' || peek() == 'E') {
                fractional = true
                i++
                if (peek() == '+' || peek() == '-') i++
                while (!atEnd() && src[i] in '0'..'9') i++
            }
            val token = src.substring(start, i)
            val parsed = if (fractional) token.toDoubleOrNull() else token.toLongOrNull()
            return parsed ?: throw JsonFormatException("неверное число \"$token\" на позиции $start")
        }

        private fun literal(text: String, value: Any?): Any? {
            if (!src.startsWith(text, i)) {
                throw JsonFormatException("ожидалось \"$text\" на позиции $i")
            }
            i += text.length
            return value
        }

        private fun peek(): Char? = if (atEnd()) null else src[i]

        private fun next(): Char {
            if (atEnd()) throw JsonFormatException("неожиданный конец JSON")
            return src[i++]
        }

        private fun expect(c: Char) {
            val actual = next()
            if (actual != c) throw JsonFormatException("ожидалось '$c' вместо '$actual' на позиции ${i - 1}")
        }
    }
}

// --- типизированный доступ к разобранному JSON ------------------------------------------------
//
// Отдельные функции, а не рефлексия и не общий маппер: полей мало, а сообщение об ошибке должно
// называть **конкретный** ключ. «Ожидался объект» без имени поля бесполезно тому, кто чинит релиз.

internal fun Any?.asJsonObject(what: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<String, Any?>
        ?: throw JsonFormatException("$what: ожидался объект JSON")
}

internal fun Any?.asJsonArray(what: String): List<Any?> =
    this as? List<Any?> ?: throw JsonFormatException("$what: ожидался массив JSON")

internal fun Map<String, Any?>.jsonString(key: String): String =
    jsonStringOrNull(key) ?: throw JsonFormatException("поле \"$key\": ожидалась непустая строка")

internal fun Map<String, Any?>.jsonStringOrNull(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

internal fun Map<String, Any?>.jsonLong(key: String): Long =
    this[key] as? Long ?: throw JsonFormatException("поле \"$key\": ожидалось целое число")

internal fun Map<String, Any?>.jsonInt(key: String): Int {
    val value = jsonLong(key)
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        throw JsonFormatException("поле \"$key\": число $value вне допустимого диапазона")
    }
    return value.toInt()
}

/**
 * Логическое поле с значением по умолчанию.
 *
 * По умолчанию, а не обязательное: отсутствие `prerelease` в манифесте разумно читать как
 * «обычный релиз». Обратное решение (отказ) сделало бы совместимость с будущими манифестами
 * хрупкой без всякой пользы для безопасности — про `prerelease` не лгут, а `sha256` и отпечаток
 * проверяются отдельно и обязательны.
 */
internal fun Map<String, Any?>.jsonBoolean(key: String, default: Boolean): Boolean {
    val value = this[key] ?: return default
    return value as? Boolean ?: throw JsonFormatException("поле \"$key\": ожидалось true или false")
}
