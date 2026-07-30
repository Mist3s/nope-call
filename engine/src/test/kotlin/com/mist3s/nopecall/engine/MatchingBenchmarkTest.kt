package com.mist3s.nopecall.engine

import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Замер сопоставления на предельном наборе правил (ТЗ §11.2: 10 000 правил, из них до 1000
 * не-точных) — тот самый бенчмарк, от которого зависит решение по префиксным деревьям
 * (архитектура §6.2, §12.3).
 *
 * Сознательно **не** утверждает точные значения времени: на общей CI-машине они шумят, и
 * падающий по таймингу тест быстро отключат. Числа печатаются в отчёт, а проверяется только
 * грубый потолок — бюджет прохода из ТЗ §11.1. Его превышение означает, что движок не способен
 * выполнить требование в принципе, а не что машина медленная.
 */
class MatchingBenchmarkTest {

    private val normalizer = RuFastPathNormalizer()

    @Test
    fun `проход по предельному набору правил укладывается в бюджет`() {
        val random = Random(20260730)
        val rules = buildRules(random, exactCount = 10_000, patternCount = 1_000)
        val snapshot = SnapshotBuilder(normalizer).build(rules)

        // Худший случай для прохода — «ни одно правило не совпало»: он же самый частый исход
        // звонка, поэтому мерить надо именно его (архитектура §6.4).
        val miss = facts("+70001112233", "OOO Nesovpadenie: prochee")
        val hit = facts("+79991234567", "OOO Romashka, reklama")

        // Прогрев: ленивая компиляция regex и загрузка классов не должны попасть в замер.
        repeat(200) {
            RuleEngine.decide(miss, snapshot, Budget.unlimited())
            RuleEngine.decide(hit, snapshot, Budget.unlimited())
        }

        val samples = LongArray(1_000) {
            measureNanoTime { RuleEngine.decide(miss, snapshot, Budget.unlimited()) }
        }
        samples.sort()

        val p50 = samples[samples.size / 2] / 1_000.0
        val p95 = samples[(samples.size * 95) / 100] / 1_000.0
        val max = samples.last() / 1_000.0

        println(
            """
            |
            |Сопоставление, ${snapshot.ruleCount} правил (${snapshot.exactNumberIndex.size} точных в индексе,
            |${snapshot.patternRules.size} шаблонных в проходе), исход «не совпало»:
            |  p50 ${"%.1f".format(p50)} мкс
            |  p95 ${"%.1f".format(p95)} мкс
            |  max ${"%.1f".format(max)} мкс
            |Бюджет прохода по ТЗ §11.1 — 200 000 мкс.
            """.trimMargin()
        )

        assertTrue(
            p95 < BUDGET_MICROS,
            "p95 ${"%.1f".format(p95)} мкс не укладывается в бюджет прохода $BUDGET_MICROS мкс — " +
                "движок не способен выполнить ТЗ §11.1, дело не в скорости машины",
        )
    }

    @Test
    fun `индекс точных правил не деградирует с их числом`() {
        // Смысл индекса: стоимость поиска точного правила не зависит от их количества.
        // Если это перестанет быть так, вернётся линейный перебор 10 000 правил.
        val random = Random(1)
        val small = SnapshotBuilder(normalizer).build(buildRules(random, 100, 50))
        val large = SnapshotBuilder(normalizer).build(buildRules(random, 10_000, 50))
        val f = facts("+70001112233", null)

        repeat(200) {
            RuleEngine.decide(f, small, Budget.unlimited())
            RuleEngine.decide(f, large, Budget.unlimited())
        }

        fun median(snapshot: RuleSnapshot): Long {
            val s = LongArray(500) { measureNanoTime { RuleEngine.decide(f, snapshot, Budget.unlimited()) } }
            s.sort()
            return s[s.size / 2]
        }

        val smallNanos = median(small)
        val largeNanos = median(large)
        println("100 точных: $smallNanos нс, 10 000 точных: $largeNanos нс")

        // Порог грубый: важно поймать возврат к линейному перебору (это дало бы разницу
        // в десятки раз), а не измерить константу.
        assertTrue(
            largeNanos < smallNanos * 10 + 50_000,
            "рост с числом точных правил похож на линейный: $smallNanos -> $largeNanos нс",
        )
    }

    private fun facts(number: String?, name: String?) = CallFacts(
        number = normalizer.normalize(number, "RU"),
        presentation = NumberPresentation.ALLOWED,
        name = NameCanonizer.canonize(name, setOf("reklam", "prochee")),
        nameSource = if (name == null) NameSource.NONE else NameSource.CNAP,
        inContacts = false,
        isEmergency = false,
    )

    private fun buildRules(random: Random, exactCount: Int, patternCount: Int): List<Rule> {
        var id = 0L
        val rules = ArrayList<Rule>(exactCount + patternCount)

        repeat(exactCount) {
            val number = "+79" + (100_000_000 + random.nextInt(0, 800_000_000)).toString()
            rules += Rule(
                id = ++id, title = "точное $id", target = RuleTarget.NUMBER,
                matchType = MatchType.EXACT, pattern = number,
                action = CallAction.REJECT, orderIndex = 600_000 + it,
            )
        }

        val types = listOf(MatchType.PREFIX, MatchType.SUFFIX, MatchType.CONTAINS)
        repeat(patternCount) {
            val useName = it % 3 == 0
            rules += if (useName) {
                Rule(
                    id = ++id, title = "название $id", target = RuleTarget.NAME_ORG,
                    matchType = if (it % 6 == 0) MatchType.TOKEN else MatchType.CONTAINS,
                    pattern = "firma" + random.nextInt(0, 100_000),
                    action = CallAction.REJECT, orderIndex = 900_000 + it,
                    translitVariants = it % 9 == 0,
                )
            } else {
                Rule(
                    id = ++id, title = "номер $id", target = RuleTarget.NUMBER,
                    matchType = types[it % types.size],
                    pattern = (random.nextInt(1_000, 9_999)).toString(),
                    action = CallAction.REJECT, orderIndex = 700_000 + it,
                )
            }
        }
        return rules
    }

    private companion object {
        /** Бюджет прохода по всем правилам, ТЗ §11.1. */
        const val BUDGET_MICROS = 200_000.0
    }
}
