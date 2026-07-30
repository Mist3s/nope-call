package com.mist3s.nopecall.engine

import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Кодек снимка: круговой рейс, устойчивость к повреждению и стоимость чтения.
 *
 * Стоимость чтения здесь не праздный интерес: от неё зависит, нужна ли секция под mmap
 * с бинарным поиском, которую предполагала архитектура §5.2. Как и с префиксными деревьями,
 * вопрос закрывается замером, а не рассуждением.
 */
class SnapshotCodecTest {

    private val normalizer = RuFastPathNormalizer()
    private val builder = SnapshotBuilder(normalizer)

    private fun sampleRules(): List<Rule> = listOf(
        Rule(1, "точный номер", RuleTarget.NUMBER, MatchType.EXACT, "+79991234567",
            CallAction.ALLOW, 100),
        Rule(2, "контакты", RuleTarget.CONTACT, MatchType.IN_CONTACTS, "",
            CallAction.ALLOW, 110),
        Rule(3, "префикс", RuleTarget.NUMBER, MatchType.PREFIX, "8495",
            CallAction.REJECT, 600),
        Rule(4, "категория", RuleTarget.NAME_CATEGORY, MatchType.PREFIX, "reklam",
            CallAction.REJECT, 900),
        Rule(5, "наименование с вариантами", RuleTarget.NAME_ORG, MatchType.CONTAINS,
            "полезный звонок", CallAction.REJECT, 910, translitVariants = true),
        Rule(6, "слово", RuleTarget.NAME_ORG, MatchType.TOKEN, "agent",
            CallAction.DROP, 920),
        Rule(7, "regex", RuleTarget.NUMBER, MatchType.REGEX, "^\\+7999555\\d{4}$",
            CallAction.SILENCE, 930, regexField = RegexField.E164),
    )

    private val settings = DecisionSettings(
        defaultAction = CallAction.ALLOW,
        restrictedAction = CallAction.REJECT,
        region = "RU",
        categoryDictionary = setOf("reklam", "dostavka", "it"),
    )

    @Test
    fun `круговой рейс сохраняет решения, а не только байты`() {
        // Сверять поля недостаточно: смысл снимка в том, что решения до и после перезапуска
        // процесса совпадают. Поэтому сравниваются решения на наборе входов.
        val original = builder.build(sampleRules(), settings)
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(original), verifyChecksums = true)

        assertEquals(original.ruleCount, restored.ruleCount)
        assertEquals(original.canonVersion, restored.canonVersion)
        assertEquals(original.settings, restored.settings)
        assertEquals(original.patternRules.size, restored.patternRules.size)
        assertEquals(original.exactNumberIndex.keys, restored.exactNumberIndex.keys)

        val inputs = listOf(
            "+79991234567" to null,
            "+74951234567" to null,
            "+79990000000" to "OOO Romashka: reklama",
            "+79990000000" to "OOO Poleznyy Zvonok: agenstvo",
            "+79990000000" to "OOO UG KC Agent Rostelecom",
            "112" to null,
            null to "Zvonok bez markirovki",
        )
        for ((number, name) in inputs) {
            val f = facts(number, name)
            val before = RuleEngine.decide(f, original, Budget.unlimited())
            val after = RuleEngine.decide(f, restored, Budget.unlimited())
            assertEquals(before.action, after.action, "разошлось действие на $number / $name")
            assertEquals(before.reason, after.reason, "разошлась причина на $number / $name")
            assertEquals(before.matchedRuleId, after.matchedRuleId, "разошлось правило на $number / $name")
        }
    }

    @Test
    fun `заголовок читается без разбора секций`() {
        val bytes = SnapshotCodec.encode(builder.build(sampleRules(), settings))
        val header = SnapshotCodec.readHeader(ByteBuffer.wrap(bytes))
        assertEquals(SnapshotCodec.FORMAT_VERSION, header.formatVersion)
        assertEquals(RuleSnapshot.CURRENT_CANON_VERSION, header.canonVersion)
        assertEquals(7, header.ruleCount)
        assertEquals(2, header.sections.size)
        assertTrue(header.sections.all { it.offset + it.length <= bytes.size })
    }

    @Test
    fun `обрезанный файл ловится заголовком, а не разбором`() {
        val bytes = SnapshotCodec.encode(builder.build(sampleRules(), settings))
        // Именно поэтому границы секций проверяются всегда: обрезка — самая частая беда
        // при аварийной записи, и ловить её надо дёшево.
        val truncated = bytes.copyOf(bytes.size / 2)
        assertFailsWith<SnapshotFormatException> {
            SnapshotCodec.readHeader(ByteBuffer.wrap(truncated))
        }
    }

    @Test
    fun `чужой файл не принимается за снимок`() {
        assertFailsWith<SnapshotFormatException> {
            SnapshotCodec.readHeader(ByteBuffer.wrap("это не снимок правил вообще".toByteArray()))
        }
        assertFailsWith<SnapshotFormatException> {
            SnapshotCodec.readHeader(ByteBuffer.wrap(ByteArray(4)))
        }
    }

    @Test
    fun `повреждение внутри секции ловится суммой`() {
        val bytes = SnapshotCodec.encode(builder.build(sampleRules(), settings))
        // Портим байт в середине секции правил: заголовок и границы при этом целы.
        bytes[bytes.size - 10] = (bytes[bytes.size - 10] + 1).toByte()

        // Без проверки сумм повреждение может пройти незамеченным — это осознанная цена
        // за отсутствие хеширования всего объёма в горячем пути.
        assertFailsWith<SnapshotFormatException> {
            SnapshotCodec.decode(bytes, verifyChecksums = true)
        }
    }

    @Test
    fun `пустой снимок кодируется и читается`() {
        val empty = builder.build(emptyList(), settings)
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(empty), verifyChecksums = true)
        assertEquals(0, restored.ruleCount)
        assertEquals(
            CallAction.ALLOW,
            RuleEngine.decide(facts("+79991234567", null), restored, Budget.unlimited()).action,
        )
    }

    @Test
    fun `варианты транслитерации переживают круговой рейс`() {
        val original = builder.build(sampleRules(), settings)
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(original))
        val rule = restored.patternRules.first { it.id == 5L }
        assertTrue(rule.variants.size > 1, "варианты должны сохраниться, а не пересчитываться")
        assertTrue(rule.variants.any { it.contains("poleznyy") } || rule.variants.any { it.contains("polezniy") })
    }

    @Test
    fun `regex переживает круговой рейс вместе с литералом префильтра`() {
        val original = builder.build(sampleRules(), settings)
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(original))
        val rule = restored.patternRules.first { it.matchType == MatchType.REGEX }
        // Литерал не пересчитывается при чтении, а хранится: он извлекается при сохранении
        // правила, то есть вне горячего пути.
        assertEquals("+7999555", rule.regexLiteral)
        assertEquals(
            CallAction.SILENCE,
            RuleEngine.decide(facts("+79995550000", null), restored, Budget.unlimited()).action,
        )
        // Префильтр отсекает по литералу до компиляции и матчинга.
        assertEquals(
            CallAction.ALLOW,
            RuleEngine.decide(facts("+78881112233", null), restored, Budget.unlimited()).action,
        )
    }

    @Test
    fun `слишком короткий литерал не извлекается`() {
        // `+7` есть у каждого российского номера: префильтр по нему не отсеял бы ничего,
        // только добавил бы работы на каждом звонке. Поэтому короткие литералы отбрасываются.
        val short = builder.compile(
            Rule(1, "любой российский", RuleTarget.NUMBER, MatchType.REGEX,
                "^\\+7\\d{10}$", CallAction.REJECT, 600, regexField = RegexField.E164)
        )
        assertEquals(null, short?.regexLiteral)
        // Правило при этом полностью работоспособно — просто без префильтра.
        val s = builder.build(
            listOf(Rule(1, "любой российский", RuleTarget.NUMBER, MatchType.REGEX,
                "^\\+7\\d{10}$", CallAction.REJECT, 600, regexField = RegexField.E164)),
            settings,
        )
        assertEquals(CallAction.REJECT, RuleEngine.decide(facts("+79991112233", null), s, Budget.unlimited()).action)
    }

    @Test
    fun `чтение предельного снимка укладывается в бюджет холодного старта`() {
        // Бюджет чтения снимка — 50 мс на холодном старте (ТЗ §8.2, архитектура §4.5).
        // Замер отвечает на отложенный вопрос: нужна ли отдельная секция под mmap
        // с бинарным поиском по плоскому массиву.
        val random = Random(20260730)
        val rules = ArrayList<Rule>(11_000)
        var id = 0L
        repeat(10_000) {
            rules += Rule(
                ++id, "точное $id", RuleTarget.NUMBER, MatchType.EXACT,
                "+79" + (100_000_000 + random.nextInt(0, 800_000_000)),
                CallAction.REJECT, 600_000 + it,
            )
        }
        repeat(1_000) {
            rules += Rule(
                ++id, "шаблон $id", RuleTarget.NUMBER, MatchType.PREFIX,
                random.nextInt(1_000, 9_999).toString(), CallAction.REJECT, 700_000 + it,
            )
        }

        val snapshot = builder.build(rules, settings)
        val bytes = SnapshotCodec.encode(snapshot)

        repeat(20) { SnapshotCodec.decode(bytes) } // прогрев

        val samples = LongArray(50) { measureNanoTime { SnapshotCodec.decode(bytes) } }
        samples.sort()
        val p50 = samples[samples.size / 2] / 1_000_000.0
        val p95 = samples[(samples.size * 95) / 100] / 1_000_000.0

        val withChecksums = LongArray(20) {
            measureNanoTime { SnapshotCodec.decode(bytes, verifyChecksums = true) }
        }
        withChecksums.sort()

        println(
            """
            |
            |Снимок ${snapshot.ruleCount} правил: ${bytes.size / 1024} КБ
            |  чтение без проверки сумм: p50 ${"%.1f".format(p50)} мс, p95 ${"%.1f".format(p95)} мс
            |  чтение с проверкой сумм: p50 ${"%.1f".format(withChecksums[withChecksums.size / 2] / 1_000_000.0)} мс
            |Бюджет чтения снимка — 50 мс на холодном старте.
            """.trimMargin()
        )

        assertTrue(
            p95 < 50.0,
            "p95 чтения ${"%.1f".format(p95)} мс не укладывается в бюджет 50 мс — " +
                "снимок надо перекладывать на mmap с бинарным поиском",
        )
    }

    private fun facts(number: String?, name: String?) = CallFacts(
        number = normalizer.normalize(number, "RU"),
        presentation = if (number == null) NumberPresentation.RESTRICTED else NumberPresentation.ALLOWED,
        name = NameCanonizer.canonize(name, settings.categoryDictionary),
        nameSource = if (name == null) NameSource.NONE else NameSource.CNAP,
        inContacts = false,
        isEmergency = false,
    )
}
