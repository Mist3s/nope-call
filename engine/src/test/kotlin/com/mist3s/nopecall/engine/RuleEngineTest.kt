package com.mist3s.nopecall.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сопоставление, порядок правил и поведение при отказах.
 *
 * Центральный тест здесь — [индекс точных правил не меняет результат]: он закрепляет инвариант
 * «результат = наивный последовательный перебор», без которого любая оптимизация сопоставления
 * становится непроверяемой.
 */
class RuleEngineTest {

    private val normalizer = RuFastPathNormalizer()
    private val builder = SnapshotBuilder(normalizer)
    private val dict = setOf("dostavka", "it", "finans", "reklam", "agenstvo", "opros", "transport")

    // --- фабрики фактов и правил -------------------------------------------------------------

    private fun facts(
        number: String? = "+79991234567",
        name: String? = null,
        presentation: NumberPresentation = NumberPresentation.ALLOWED,
        inContacts: Boolean? = false,
    ) = CallFacts(
        number = normalizer.normalize(number, "RU"),
        presentation = presentation,
        name = NameCanonizer.canonize(name, dict),
        nameSource = if (name == null) NameSource.NONE else NameSource.CNAP,
        inContacts = inContacts,
        isEmergency = false,
    )

    private var nextId = 1L
    private fun rule(
        target: RuleTarget,
        matchType: MatchType,
        pattern: String,
        action: CallAction = CallAction.REJECT,
        order: Int = 0,
        variants: Boolean = false,
        regexField: RegexField? = null,
    ) = Rule(
        id = nextId++,
        title = "$target/$matchType $pattern",
        target = target,
        matchType = matchType,
        pattern = pattern,
        action = action,
        orderIndex = order,
        translitVariants = variants,
        regexField = regexField,
    )

    private fun snapshot(vararg rules: Rule, settings: DecisionSettings = DecisionSettings()) =
        builder.build(rules.toList(), settings.copy(categoryDictionary = dict))

    private fun decide(facts: CallFacts, snapshot: RuleSnapshot) =
        RuleEngine.decide(facts, snapshot, Budget.unlimited())

    // --- номера: ключевые векторы ТЗ §6.2.1 --------------------------------------------------

    @Test
    fun `шаблон с восьмёркой ловит номер в международном формате`() {
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.PREFIX, "8495", order = 600))
        assertEquals(CallAction.REJECT, decide(facts("+74951234567"), s).action)
    }

    @Test
    fun `шаблон в международном формате ловит номер с восьмёркой`() {
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.PREFIX, "+7495", order = 600))
        assertEquals(CallAction.REJECT, decide(facts("84951234567"), s).action)
    }

    @Test
    fun `код города не ловится как префикс из середины номера`() {
        // Критерий приёмки: `495` НЕ должен ловить +79994951234.
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.PREFIX, "495", order = 600))
        assertEquals(CallAction.ALLOW, decide(facts("+79994951234"), s).action)
        assertEquals(CallAction.REJECT, decide(facts("+74951234567"), s).action)
    }

    @Test
    fun `окончание номера`() {
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.SUFFIX, "1234", order = 600))
        assertEquals(CallAction.REJECT, decide(facts("+79990001234"), s).action)
        assertEquals(CallAction.ALLOW, decide(facts("+79991234000"), s).action)
    }

    @Test
    fun `точное правило блокирует только указанный номер`() {
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.EXACT, "8 999 123-45-67", order = 600))
        assertEquals(CallAction.REJECT, decide(facts("+79991234567"), s).action)
        assertEquals(CallAction.ALLOW, decide(facts("+79991234568"), s).action)
    }

    // --- названия: наблюдённый корпус --------------------------------------------------------

    @Test
    fun `правило по категории не срабатывает от наименования`() {
        val s = snapshot(rule(RuleTarget.NAME_CATEGORY, MatchType.PREFIX, "it", order = 900))
        assertEquals(CallAction.REJECT, decide(facts(name = "botto: IT"), s).action)
        // `IT Link` — часть наименования, категории у этой подписи нет.
        assertEquals(
            CallAction.ALLOW,
            decide(facts(name = "Agent Rostelecom IT Link Sol"), s).action,
        )
    }

    @Test
    fun `правило по категории различает две подписи одного юрлица`() {
        val s = snapshot(rule(RuleTarget.NAME_CATEGORY, MatchType.PREFIX, "reklam", order = 900))
        assertEquals(CallAction.REJECT, decide(facts(name = "OOO Polezniy zvonok: reklama"), s).action)
        assertEquals(CallAction.ALLOW, decide(facts(name = "OOO Poleznyy Zvonok: agenstvo"), s).action)
    }

    @Test
    fun `содержит слово ловит агента и не ловит середину слова`() {
        val s = snapshot(rule(RuleTarget.NAME_ORG, MatchType.TOKEN, "agent", order = 900))
        assertEquals(CallAction.REJECT, decide(facts(name = "OOO UG KC Agent Rostelecom"), s).action)
        // `agentstvo` — другое слово, «содержит слово» на нём не срабатывает.
        assertEquals(CallAction.ALLOW, decide(facts(name = "OOO Agentstvo Romashka"), s).action)
    }

    @Test
    fun `русский шаблон ловит подпись транслитом`() {
        val s = snapshot(rule(RuleTarget.NAME, MatchType.CONTAINS, "реклама", order = 900))
        assertEquals(CallAction.REJECT, decide(facts(name = "OOO Romashka, reklama"), s).action)
        assertEquals(CallAction.REJECT, decide(facts(name = "Reklama"), s).action)
    }

    @Test
    fun `варианты транслитерации ловят оба написания одного юрлица`() {
        // Poleznyy и Polezniy — одна организация, два написания (ТЗ §6.3.1).
        val s = snapshot(
            rule(RuleTarget.NAME_ORG, MatchType.CONTAINS, "полезный звонок", order = 900, variants = true)
        )
        assertEquals(CallAction.REJECT, decide(facts(name = "OOO Poleznyy Zvonok: agenstvo"), s).action)
        assertEquals(CallAction.REJECT, decide(facts(name = "OOO Polezniy zvonok: reklama"), s).action)
    }

    @Test
    fun `метка немаркированного вызова ловится по слову`() {
        val s = snapshot(rule(RuleTarget.NAME, MatchType.TOKEN, "markirovki", order = 900))
        assertEquals(CallAction.REJECT, decide(facts(name = "Zvonok bez markirovki"), s).action)
    }

    @Test
    fun `без названия правила по названию пропускаются, решение по номеру`() {
        val s = snapshot(
            rule(RuleTarget.NAME, MatchType.CONTAINS, "реклама", order = 900),
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 600),
        )
        val d = decide(facts(number = "+79991234567", name = null), s)
        assertEquals(CallAction.REJECT, d.action)
        assertTrue(d.has(Degradation.NAME_UNAVAILABLE))
    }

    // --- порядок правил ----------------------------------------------------------------------

    @Test
    fun `разрешающее правило выигрывает у блокирующего, если стоит выше`() {
        val s = snapshot(
            rule(RuleTarget.NUMBER, MatchType.EXACT, "+79991234567", CallAction.ALLOW, order = 100),
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", CallAction.REJECT, order = 600),
        )
        val d = decide(facts("+79991234567"), s)
        assertEquals(CallAction.ALLOW, d.action)
        assertEquals(DecisionReason.RULE_MATCH, d.reason)
    }

    @Test
    fun `первое совпавшее правило выигрывает, остальные не смотрятся`() {
        val s = snapshot(
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", CallAction.SILENCE, order = 300),
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", CallAction.REJECT, order = 600),
        )
        assertEquals(CallAction.SILENCE, decide(facts("+79991234567"), s).action)
    }

    @Test
    fun `индекс точных правил не меняет результат`() {
        // Инвариант, ради которого существует decideNaive. Наборы генерируются с фиксированным
        // семенем: тест обязан быть воспроизводимым.
        val random = Random(20260730)
        val numbers = listOf("+79991234567", "84951234567", "+79994951234", "+70001112233")
        val names = listOf(
            null, "PAO SOVKOMBANK", "botto: IT", "OOO Romashka, reklama",
            "Agent Rostelecom IT Link Sol", "Zvonok bez markirovki",
        )
        val patterns = listOf("7999", "8495", "495", "1234", "+79991234567", "84951234567")
        val namePatterns = listOf("reklama", "it", "agent", "pao", "sovkombank")

        repeat(400) { iteration ->
            val rules = (1..random.nextInt(1, 9)).map {
                val useNumber = random.nextBoolean()
                Rule(
                    id = it.toLong(),
                    title = "r$it",
                    target = if (useNumber) RuleTarget.NUMBER else listOf(
                        RuleTarget.NAME, RuleTarget.NAME_ORG, RuleTarget.NAME_CATEGORY
                    ).random(random),
                    matchType = if (useNumber) {
                        listOf(MatchType.EXACT, MatchType.PREFIX, MatchType.SUFFIX, MatchType.CONTAINS)
                            .random(random)
                    } else {
                        listOf(MatchType.EXACT, MatchType.PREFIX, MatchType.CONTAINS, MatchType.TOKEN)
                            .random(random)
                    },
                    pattern = if (useNumber) patterns.random(random) else namePatterns.random(random),
                    action = listOf(CallAction.REJECT, CallAction.ALLOW, CallAction.SILENCE, CallAction.DROP)
                        .random(random),
                    orderIndex = random.nextInt(0, 1000),
                    translitVariants = random.nextBoolean(),
                )
            }
            val s = snapshot(*rules.toTypedArray())
            val f = facts(number = numbers.random(random), name = names.random(random))

            val fast = RuleEngine.decide(f, s, Budget.unlimited())
            val naive = RuleEngine.decideNaive(f, s, Budget.unlimited())

            assertEquals(
                naive.action, fast.action,
                "итерация $iteration: индекс изменил действие.\nправила: $rules\nномер: ${f.number.raw}, имя: ${f.name.whole.raw}",
            )
            assertEquals(naive.reason, fast.reason, "итерация $iteration: разошлась причина")
            assertEquals(naive.matchedRuleId, fast.matchedRuleId, "итерация $iteration: разошлось правило")
        }
    }

    // --- отказы: всё разрешается ------------------------------------------------------------

    @Test
    fun `исчерпание бюджета разрешает звонок даже в режиме блокировать всё`() {
        // Прямое следствие ТЗ §1.1: default_action к таймауту не применяется.
        val s = snapshot(
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 600),
            settings = DecisionSettings(defaultAction = CallAction.REJECT),
        )
        val exhausted = object : Budget {
            override fun exceeded(): Boolean = true
            override val perRuleNanos: Long = 0
        }
        val d = RuleEngine.decide(facts("+79991234567"), s, exhausted)
        assertEquals(CallAction.ALLOW, d.action)
        assertEquals(DecisionReason.ENGINE_BUDGET_EXCEEDED, d.reason)
    }

    @Test
    fun `главный выключатель разрешает всё`() {
        val s = snapshot(
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 600),
            settings = DecisionSettings(blockingEnabled = false),
        )
        val d = decide(facts("+79991234567"), s)
        assertEquals(CallAction.ALLOW, d.action)
        assertEquals(DecisionReason.DISABLED_BY_USER, d.reason)
    }

    @Test
    fun `экстренный номер разрешается до прохода по правилам`() {
        val s = snapshot(
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "1", order = 600),
            settings = DecisionSettings(defaultAction = CallAction.REJECT),
        )
        val d = decide(facts("112"), s)
        assertEquals(CallAction.ALLOW, d.action)
        assertEquals(DecisionReason.EMERGENCY, d.reason)
    }

    @Test
    fun `скрытый номер решается отдельной настройкой`() {
        val s = snapshot(
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7", order = 600),
            settings = DecisionSettings(restrictedAction = CallAction.REJECT),
        )
        val hidden = facts(number = null, presentation = NumberPresentation.RESTRICTED)
        val d = decide(hidden, s)
        assertEquals(CallAction.REJECT, d.action)
        assertEquals(DecisionReason.RESTRICTED_NUMBER, d.reason)
    }

    @Test
    fun `скрытый номер по умолчанию проходит`() {
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.PREFIX, "7", order = 600))
        val hidden = facts(number = null, presentation = NumberPresentation.RESTRICTED)
        assertEquals(CallAction.ALLOW, decide(hidden, s).action)
    }

    @Test
    fun `катастрофическое выражение не попадает в снимок`() {
        // Первая линия защиты — проверка при сохранении: если такой шаблон окажется в базе,
        // каждый звонок будет упираться в бюджет, а пользователь увидит только
        // «правило не сработало» (ТЗ §6.5).
        val evil = Rule(
            id = 1, title = "катастрофическое", target = RuleTarget.NAME,
            matchType = MatchType.REGEX, pattern = "^(a+)+b$",
            action = CallAction.REJECT, orderIndex = 900, regexField = RegexField.NAME_FOLD,
        )
        assertTrue(RegexValidator.validate("^(a+)+b$") is PatternCheck.TooExpensive)
        assertNull(builder.compile(evil))
        assertEquals(0, builder.build(listOf(evil)).ruleCount)
    }

    @Test
    fun `сбойное разрешающее правило понижает блокировку до разрешения`() {
        // По весам ТЗ §5.1 разрешающие стоят выше блокирующих. Если разрешающее правило
        // пропущено из-за ошибки, блокировать нельзя — иначе разрешённый звонок станет
        // заблокированным (архитектура §6.7).
        //
        // Сценарий приходится создавать искусственно: катастрофические шаблоны в снимок
        // не попадают (тест выше), поэтому берётся заведомо дешёвое выражение, а бюджет
        // на правило выставляется нулевым. Длинный вход нужен, чтобы обход дошёл до проверки
        // часов — она делается раз в 1024 обращения к символам.
        val cheapButStarved = Rule(
            id = 100, title = "сбойное разрешающее",
            target = RuleTarget.NAME, matchType = MatchType.REGEX,
            pattern = "z{3}", action = CallAction.ALLOW, orderIndex = 100,
            regexField = RegexField.NAME_FOLD,
        )
        val s = snapshot(cheapButStarved, rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 600))
        assertEquals(2, s.ruleCount, "оба правила должны быть в снимке")

        val f = facts(number = "+79991234567", name = "a".repeat(10_000))
        val d = RuleEngine.decide(
            f, s,
            Budget.wallClock(totalNanos = 5_000_000_000, perRuleNanos = 0),
        )

        assertTrue(d.has(Degradation.RULE_SKIPPED), "правило должно быть пропущено по бюджету")
        assertTrue(d.has(Degradation.ALLOW_RULE_SKIPPED), "и отмечено как разрешающее")
        assertEquals(CallAction.ALLOW, d.action, "блокировка обязана быть понижена до разрешения")
    }

    @Test
    fun `сбойное правило «без звука» тоже понижает блокировку`() {
        // SILENCE звонок пропускает: телефон молчит, но ответить можно. Значит пропуск сбойного
        // SILENCE с последующим REJECT превращает «прошёл» в «заблокирован» — то же нарушение
        // §1.1, что и для разрешающего правила, просто для другого действия.
        val cheapButStarved = Rule(
            id = 100, title = "сбойное без звука",
            target = RuleTarget.NAME, matchType = MatchType.REGEX,
            pattern = "z{3}", action = CallAction.SILENCE, orderIndex = 100,
            regexField = RegexField.NAME_FOLD,
        )
        val s = snapshot(cheapButStarved, rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 600))

        val d = RuleEngine.decide(
            facts(number = "+79991234567", name = "a".repeat(10_000)), s,
            Budget.wallClock(totalNanos = 5_000_000_000, perRuleNanos = 0),
        )

        assertTrue(d.has(Degradation.RULE_SKIPPED))
        assertEquals(CallAction.ALLOW, d.action, "блокировка обязана быть понижена")
    }

    @Test
    fun `сбойное разрешающее правило понижает и «без звука»`() {
        // Зеркальный случай: пропущено ALLOW (вес выше), совпало SILENCE. Телефон остался бы
        // молчащим — то есть звонок от врача пользователь не услышал бы.
        val cheapButStarved = Rule(
            id = 100, title = "сбойное разрешающее",
            target = RuleTarget.NAME, matchType = MatchType.REGEX,
            pattern = "z{3}", action = CallAction.ALLOW, orderIndex = 100,
            regexField = RegexField.NAME_FOLD,
        )
        val s = snapshot(
            cheapButStarved,
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", action = CallAction.SILENCE, order = 600),
        )

        val d = RuleEngine.decide(
            facts(number = "+79991234567", name = "a".repeat(10_000)), s,
            Budget.wallClock(totalNanos = 5_000_000_000, perRuleNanos = 0),
        )

        assertEquals(CallAction.ALLOW, d.action, "«без звука» тоже понижается до разрешения")
    }

    @Test
    fun `выражение, роняющее стек, отклоняется как дорогое, а не падает`() {
        // Реализация регулярных выражений в JDK рекурсивна: на `(a|aa)+b` она падает по стеку,
        // не превысив бюджет по времени. Отказ уходил наружу и ронял **весь** импорт правил,
        // хотя обещано отклонение поштучно.
        val check = RegexValidator.validate("(a|aa)+b")
        assertTrue(check is PatternCheck.TooExpensive, "получено: $check")
    }

    // --- несколько категорий в одном правиле (41-ФЗ) -------------------------------------------

    @Test
    fun `правило по категории ловит любую из перечисленных`() {
        // Категория приходит из утверждённого перечня 41-ФЗ, и пользователь думает списком:
        // «доставка, реклама, опросы». Пять правил по одной категории — это пять мест,
        // где можно ошибиться порядком.
        val s = snapshot(
            rule(RuleTarget.NAME_CATEGORY, MatchType.EXACT, "Доставка, Реклама, Соц. опрос",
                order = 600, variants = true)
        )

        for (name in listOf(
            "OOO Romashka: dostavka",
            "OOO Romashka: reklama",
        )) {
            assertEquals(CallAction.REJECT, decide(facts(name = name), s).action, name)
        }
        // Категории вне списка правило не трогает.
        assertEquals(
            CallAction.ALLOW,
            decide(facts(name = "BANK RUSSKIY STANDART: finansy"), s).action,
        )
    }

    @Test
    fun `перечисление работает и без вариантов написания`() {
        // Набор шаблонов нужен сам по себе: при пустых вариантах правило искало бы только
        // первую категорию, а остальные молча игнорировало.
        val s = snapshot(
            rule(RuleTarget.NAME_CATEGORY, MatchType.EXACT, "dostavka, reklama",
                order = 600, variants = false)
        )

        assertEquals(CallAction.REJECT, decide(facts(name = "OOO A: dostavka"), s).action)
        assertEquals(CallAction.REJECT, decide(facts(name = "OOO B: reklama"), s).action)
    }

    @Test
    fun `запятая в наименовании остаётся частью текста`() {
        // Делить по запятой можно только категорию: в наименовании она часть названия,
        // и правило «ООО Ромашка, филиал» не должно превращаться в два шаблона.
        val s = snapshot(
            rule(RuleTarget.NAME_ORG, MatchType.CONTAINS, "Ромашка, филиал", order = 600)
        )

        assertEquals(CallAction.REJECT, decide(facts(name = "OOO Romashka, filial: dostavka"), s).action)
        assertEquals(CallAction.ALLOW, decide(facts(name = "OOO Romashka: dostavka"), s).action)
    }

    // --- короткие номера: правило безопасности ТЗ §5.4 ----------------------------------------

    @Test
    fun `широкое правило не блокирует короткий номер`() {
        // Ради этого правило и существует: 122, 155, 118 в резервный список экстренных
        // не входят, а «блокировать всё, начинающееся с 1» пользователь напишет легко.
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.PREFIX, "1", order = 600))

        for (short in listOf("103", "122", "155", "112")) {
            val d = decide(facts(short), s)
            assertEquals(CallAction.ALLOW, d.action, "«$short» блокировать нельзя")
        }
    }

    @Test
    fun `точное правило по короткому номеру работает`() {
        // Обратная сторона: явное точное правило пользователь написал сознательно.
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.EXACT, "900", order = 600))

        assertEquals(CallAction.REJECT, decide(facts("900"), s).action)
        assertEquals(DecisionReason.RULE_MATCH, decide(facts("900"), s).reason)
    }

    @Test
    fun `короткий номер проходит и в режиме «блокировать всё»`() {
        // «Не блокируются ничем, кроме точного правила» — значит и default_action тоже.
        val s = snapshot(
            settings = DecisionSettings(defaultAction = CallAction.REJECT),
        )

        // Номер вне встроенного списка экстренных: иначе сработала бы более ранняя ветка,
        // и тест проверял бы не то. Именно такие номера правило §5.4 и защищает.
        val d = decide(facts("122"), s)
        assertEquals(CallAction.ALLOW, d.action)
        assertEquals(DecisionReason.SHORT_NUMBER, d.reason)
    }

    @Test
    fun `правило по названию не блокирует короткий номер`() {
        val s = snapshot(rule(RuleTarget.NAME, MatchType.CONTAINS, "reklama", order = 600))

        val d = decide(facts(number = "122", name = "OOO Romashka: reklama"), s)
        assertEquals(CallAction.ALLOW, d.action)
        assertEquals(DecisionReason.SHORT_NUMBER, d.reason)
    }

    @Test
    fun `четырёхзначный номер под защиту не попадает`() {
        // Порог из ТЗ — три цифры, а не «короткий номер» нормализатора (до шести):
        // иначе правила молча перестали бы работать для сервисных четырёхзначных.
        val s = snapshot(rule(RuleTarget.NUMBER, MatchType.PREFIX, "9", order = 600))

        assertEquals(CallAction.REJECT, decide(facts("9001"), s).action)
    }

    // --- системное правило по контактам -----------------------------------------------------

    @Test
    fun `правило по контактам разрешает звонок от контакта`() {
        val s = snapshot(
            Rule(
                id = 1, title = "Разрешать контакты", target = RuleTarget.CONTACT,
                matchType = MatchType.IN_CONTACTS, pattern = "", action = CallAction.ALLOW,
                orderIndex = 100,
            ),
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 600),
        )
        assertEquals(CallAction.ALLOW, decide(facts("+79991234567", inContacts = true), s).action)
        assertEquals(CallAction.REJECT, decide(facts("+79991234567", inContacts = false), s).action)
    }

    // --- трасса решения ---------------------------------------------------------------------

    @Test
    fun `трасса содержит проверенные правила`() {
        val s = snapshot(
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "8888", order = 300),
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 600),
        )
        val trace = DecisionTrace()
        RuleEngine.decide(facts("+79991234567"), s, Budget.unlimited(), trace)
        assertEquals(2, trace.steps.size)
        assertEquals(false, trace.steps[0].matched)
        assertEquals(true, trace.steps[1].matched)
        assertNotNull(trace.facts)
    }

    // --- сборка снимка ----------------------------------------------------------------------

    @Test
    fun `выключенные правила в снимок не попадают`() {
        val s = builder.build(
            listOf(
                rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 600).copy(enabled = false),
            )
        )
        assertEquals(0, s.ruleCount)
        assertEquals(CallAction.ALLOW, decide(facts("+79991234567"), s).action)
    }

    @Test
    fun `некорректное регулярное выражение в снимок не попадает`() {
        val broken = Rule(
            id = 1, title = "битое", target = RuleTarget.NUMBER, matchType = MatchType.REGEX,
            pattern = "^(unclosed", action = CallAction.REJECT, orderIndex = 600,
        )
        assertNull(builder.compile(broken))
        assertEquals(0, builder.build(listOf(broken)).ruleCount)
    }

    @Test
    fun `точные правила по номеру попадают в индекс, а не в общий список`() {
        val s = snapshot(
            rule(RuleTarget.NUMBER, MatchType.EXACT, "+79991234567", order = 600),
            rule(RuleTarget.NUMBER, MatchType.PREFIX, "7999", order = 601),
        )
        assertEquals(1, s.exactNumberIndex.size)
        assertEquals(1, s.patternRules.size)
        assertEquals(2, s.ruleCount)
    }
}
