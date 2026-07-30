package com.mist3s.nopecall.engine

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Снимок повреждён, обрезан или собран несовместимой версией. */
public class SnapshotFormatException(message: String) : RuntimeException(message)

/**
 * Заголовок снимка: то, что читается и проверяется **всегда**, до разбора секций.
 *
 * Десятки байт — поэтому проверка дёшева и ловит главные беды: обрезанный файл, чужой формат,
 * несовпадение версии канонизации. Полная проверка сумм секций делается при сборке и в фоне,
 * но не в горячем пути (архитектура §5.2).
 */
public data class SnapshotHeader(
    val formatVersion: Int,
    val canonVersion: Int,
    val ruleCount: Int,
    val sections: List<Section>,
) {
    public data class Section(val id: Int, val offset: Int, val length: Int, val checksum: Int)
}

/**
 * Двоичный формат снимка правил (архитектура §5.2, ТЗ §8.2).
 *
 * **Почему формат свой, а не CBOR, как предполагала архитектура.** `:engine` не имеет права
 * на зависимости: это техническая гарантия требований «проверка звонка не делает сетевых
 * запросов» и переносимости движка. Любая библиотека сериализации — зависимость, а ослаблять
 * границу модуля ради удобства нельзя. Формат здесь простой и фиксированный, поэтому написать
 * его руками дешевле, чем менять правило.
 *
 * Движок работает с байтами и не знает про файлы: атомарная запись, `fsync` и mmap — забота
 * адаптера. Чтение принимает [ByteBuffer], чтобы адаптер мог передать отображённый в память файл.
 */
public object SnapshotCodec {

    private const val MAGIC = 0x4E43_5331 // "NCS1"
    public const val FORMAT_VERSION: Int = 1

    /** Читатель поддерживает текущую версию и предыдущую (архитектура §5.2). */
    public const val MIN_SUPPORTED_FORMAT_VERSION: Int = 1

    private const val SECTION_SETTINGS = 1
    private const val SECTION_RULES = 2

    // --- запись ---------------------------------------------------------------------------

    public fun encode(snapshot: RuleSnapshot): ByteArray {
        val settings = encodeSettings(snapshot.settings)
        val rules = encodeRules(allRulesInOrder(snapshot))

        val sectionCount = 2
        val headerSize = HEADER_FIXED_SIZE + sectionCount * SECTION_ENTRY_SIZE
        val total = headerSize + settings.size + rules.size

        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        out.putInt(MAGIC)
        out.putShort(FORMAT_VERSION.toShort())
        out.putShort(snapshot.canonVersion.toShort())
        out.putInt(snapshot.ruleCount)
        out.putShort(sectionCount.toShort())

        var offset = headerSize
        for ((id, bytes) in listOf(SECTION_SETTINGS to settings, SECTION_RULES to rules)) {
            out.put(id.toByte())
            out.putInt(offset)
            out.putInt(bytes.size)
            out.putInt(Crc32.of(bytes))
            offset += bytes.size
        }
        out.put(settings)
        out.put(rules)
        return out.array()
    }

    /**
     * Все правила в порядке `orderIndex`, без дублей.
     *
     * Точное правило может лежать в индексе под несколькими ключами (варианты написания
     * номера), поэтому дубли отсеиваются по идентификатору.
     */
    private fun allRulesInOrder(snapshot: RuleSnapshot): List<CompiledRule> =
        (snapshot.patternRules + snapshot.exactNumberIndex.values.flatten())
            .distinctBy { it.id }
            .sortedBy { it.orderIndex }

    // --- чтение ---------------------------------------------------------------------------

    /** Дешёвая проверка: магия, версии, границы секций. Секции не разбираются. */
    public fun readHeader(buffer: ByteBuffer): SnapshotHeader {
        val b = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
        try {
            if (b.remaining() < HEADER_FIXED_SIZE) throw SnapshotFormatException("файл короче заголовка")
            if (b.int != MAGIC) throw SnapshotFormatException("не снимок правил: не совпала магия")

            val formatVersion = b.short.toInt() and 0xFFFF
            if (formatVersion !in MIN_SUPPORTED_FORMAT_VERSION..FORMAT_VERSION) {
                throw SnapshotFormatException(
                    "версия формата $formatVersion не поддерживается " +
                        "(читаем $MIN_SUPPORTED_FORMAT_VERSION..$FORMAT_VERSION)"
                )
            }
            val canonVersion = b.short.toInt() and 0xFFFF
            val ruleCount = b.int
            val sectionCount = b.short.toInt() and 0xFFFF
            if (sectionCount !in 1..MAX_SECTIONS) {
                throw SnapshotFormatException("подозрительное число секций: $sectionCount")
            }

            val limit = buffer.limit()
            val sections = ArrayList<SnapshotHeader.Section>(sectionCount)
            repeat(sectionCount) {
                val id = b.get().toInt() and 0xFF
                val offset = b.int
                val length = b.int
                val checksum = b.int
                if (offset < 0 || length < 0 || offset + length > limit) {
                    throw SnapshotFormatException("секция $id выходит за границы файла")
                }
                sections += SnapshotHeader.Section(id, offset, length, checksum)
            }
            return SnapshotHeader(formatVersion, canonVersion, ruleCount, sections)
        } catch (e: BufferUnderflowException) {
            throw SnapshotFormatException("файл обрезан: ${e.message}")
        }
    }

    /**
     * @param verifyChecksums полная проверка сумм секций. В горячем пути — `false`: она требует
     *   прочитать и прохешировать весь объём, а заголовок уже поймал обрезанный файл.
     *   При сборке и в фоновой проверке — `true`.
     */
    public fun decode(buffer: ByteBuffer, verifyChecksums: Boolean = false): RuleSnapshot {
        val header = readHeader(buffer)
        val base = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)

        fun section(id: Int): ByteBuffer {
            val s = header.sections.firstOrNull { it.id == id }
                ?: throw SnapshotFormatException("нет обязательной секции $id")
            val bytes = ByteArray(s.length)
            base.position(s.offset)
            base.get(bytes)
            if (verifyChecksums && Crc32.of(bytes) != s.checksum) {
                throw SnapshotFormatException("секция $id повреждена: сумма не совпала")
            }
            return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        }

        try {
            val settings = decodeSettings(section(SECTION_SETTINGS))
            val rules = decodeRules(section(SECTION_RULES))
            return partition(rules, settings, header.canonVersion)
        } catch (e: BufferUnderflowException) {
            throw SnapshotFormatException("секция обрезана: ${e.message}")
        }
    }

    public fun decode(bytes: ByteArray, verifyChecksums: Boolean = false): RuleSnapshot =
        decode(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN), verifyChecksums)

    /**
     * Раскладка правил на индекс точных и упорядоченный список — та же, что при сборке.
     *
     * Логика одна на оба пути специально: если снимок после записи и чтения раскладывался бы
     * иначе, решения до и после перезапуска процесса могли бы разойтись.
     */
    private fun partition(
        rules: List<CompiledRule>,
        settings: DecisionSettings,
        canonVersion: Int,
    ): RuleSnapshot {
        val exact = LinkedHashMap<String, MutableList<CompiledRule>>()
        val patterns = ArrayList<CompiledRule>(rules.size)
        for (rule in rules.sortedBy { it.orderIndex }) {
            if (rule.target == RuleTarget.NUMBER && rule.matchType == MatchType.EXACT) {
                for (key in rule.allPatterns) {
                    if (key.isEmpty()) continue
                    exact.getOrPut(key) { mutableListOf() }.add(rule)
                }
            } else {
                patterns.add(rule)
            }
        }
        exact.values.forEach { it.sortBy { r -> r.orderIndex } }
        return RuleSnapshot(settings, patterns, exact, canonVersion)
    }

    // --- секции ---------------------------------------------------------------------------

    private fun encodeSettings(s: DecisionSettings): ByteArray {
        val out = Writer()
        out.byte(if (s.blockingEnabled) 1 else 0)
        out.byte(s.defaultAction.ordinal)
        out.byte(s.restrictedAction.ordinal)
        out.byte(s.unknownAction.ordinal)
        out.string(s.region)
        out.strings(s.categoryDictionary.toList())
        out.strings(s.emergencyNumbers.toList())
        return out.toByteArray()
    }

    private fun decodeSettings(b: ByteBuffer): DecisionSettings = DecisionSettings(
        blockingEnabled = b.get().toInt() != 0,
        defaultAction = CallAction.entries[b.get().toInt()],
        restrictedAction = CallAction.entries[b.get().toInt()],
        unknownAction = CallAction.entries[b.get().toInt()],
        region = b.string(),
        categoryDictionary = b.strings().toSet(),
        emergencyNumbers = b.strings().toSet(),
    )

    private fun encodeRules(rules: List<CompiledRule>): ByteArray {
        val out = Writer()
        out.int(rules.size)
        for (r in rules) {
            out.long(r.id)
            out.int(r.orderIndex)
            out.byte(r.target.ordinal)
            out.byte(r.matchType.ordinal)
            out.byte(r.action.ordinal)
            out.byte(r.regexField.ordinal)
            out.string(r.title)
            out.string(r.canonical)
            out.string(r.regexSourceOrEmpty())
            out.string(r.regexLiteral ?: "")
            out.strings(r.variants)
        }
        return out.toByteArray()
    }

    private fun decodeRules(b: ByteBuffer): List<CompiledRule> {
        val count = b.int
        if (count < 0 || count > MAX_RULES) {
            throw SnapshotFormatException("подозрительное число правил: $count")
        }
        return List(count) {
            val id = b.long
            val orderIndex = b.int
            val target = RuleTarget.entries[b.get().toInt()]
            val matchType = MatchType.entries[b.get().toInt()]
            val action = CallAction.entries[b.get().toInt()]
            val regexField = RegexField.entries[b.get().toInt()]
            val title = b.string()
            val canonical = b.string()
            val regexSource = b.string().ifEmpty { null }
            val literal = b.string().ifEmpty { null }
            val variants = b.strings()
            CompiledRule(
                id = id,
                title = title,
                target = target,
                matchType = matchType,
                action = action,
                orderIndex = orderIndex,
                regexField = regexField,
                canonical = canonical,
                variants = variants,
                regexSource = regexSource,
                regexLiteral = literal,
            )
        }
    }

    // --- примитивы ------------------------------------------------------------------------

    private const val HEADER_FIXED_SIZE = 4 + 2 + 2 + 4 + 2
    private const val SECTION_ENTRY_SIZE = 1 + 4 + 4 + 4
    private const val MAX_SECTIONS = 16

    /** Защита от «числа правил» из повреждённого файла: аллокация по нему была бы дырой. */
    private const val MAX_RULES = 1_000_000

    private class Writer {
        private var buf = ByteArray(1024)
        private var size = 0

        private fun ensure(extra: Int) {
            if (size + extra <= buf.size) return
            var cap = buf.size
            while (cap < size + extra) cap *= 2
            buf = buf.copyOf(cap)
        }

        fun byte(v: Int) {
            ensure(1)
            buf[size++] = v.toByte()
        }

        fun int(v: Int) {
            ensure(4)
            buf[size++] = (v ushr 24).toByte()
            buf[size++] = (v ushr 16).toByte()
            buf[size++] = (v ushr 8).toByte()
            buf[size++] = v.toByte()
        }

        fun long(v: Long) {
            int((v ushr 32).toInt())
            int(v.toInt())
        }

        fun string(s: String) {
            val bytes = s.encodeToByteArray()
            if (bytes.size > 0xFFFF) throw SnapshotFormatException("строка длиннее 65535 байт")
            ensure(2 + bytes.size)
            buf[size++] = (bytes.size ushr 8).toByte()
            buf[size++] = bytes.size.toByte()
            bytes.copyInto(buf, size)
            size += bytes.size
        }

        fun strings(list: List<String>) {
            if (list.size > 0xFFFF) throw SnapshotFormatException("список длиннее 65535 элементов")
            ensure(2)
            buf[size++] = (list.size ushr 8).toByte()
            buf[size++] = list.size.toByte()
            list.forEach { string(it) }
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    private fun ByteBuffer.string(): String {
        val length = short.toInt() and 0xFFFF
        if (length > remaining()) throw SnapshotFormatException("строка выходит за границу секции")
        val bytes = ByteArray(length)
        get(bytes)
        return bytes.decodeToString()
    }

    private fun ByteBuffer.strings(): List<String> {
        val count = short.toInt() and 0xFFFF
        return List(count) { string() }
    }

    private fun CompiledRule.regexSourceOrEmpty(): String =
        if (matchType == MatchType.REGEX) canonical else ""
}

/**
 * CRC32 своей реализацией.
 *
 * `java.util.zip.CRC32` дал бы то же самое, но привязал бы движок к JVM ещё в одном месте.
 * Таблица строится один раз, код короткий — цена независимости здесь нулевая.
 */
internal object Crc32 {
    private val TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB8_8320.toInt() else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray): Int {
        var crc = -1
        for (b in bytes) crc = TABLE[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}
