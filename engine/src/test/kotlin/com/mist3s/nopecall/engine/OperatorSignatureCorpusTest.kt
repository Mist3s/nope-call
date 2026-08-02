package com.mist3s.nopecall.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Корпус операторских подписей с реального телефона — как он есть, а не как хотелось бы.
 *
 * Собран из двух выгрузок журнала (31.07.2026 и 02.08.2026, 515 записей, 50 номеров). Это
 * **не** тест кода: это зафиксированное поведение операторов, на которое опирается вся затея
 * с правилами по названию. Каждое утверждение здесь проверяемо по выгрузке.
 *
 * Что корпус говорит о подписи — по пунктам, потому что каждый пункт влияет на решения:
 *
 *  1. **В пределах одного номера подпись стабильна.** 50 номеров, ни одного номера с двумя
 *     разными подписями. Значит подпись — не шум.
 *  2. **Номера под одной подписью меняются массово.** `OOO UG KC Agent Rostelecom` — 10 номеров,
 *     `Rostelecom` — 7, `BANK RUSSKIY STANDART: finansy` — 6, `Agent Rostelecom IT Link Sol` — 5,
 *     `OOO Magnum: dostavka` — 4. Это и есть довод в пользу правил по названию: 34 номера
 *     против 6 правил.
 *  3. **Одно юрлицо приходит в двух написаниях и с двумя разными категориями**:
 *     `OOO Poleznyy Zvonok: agentstvo` и `OOO Polezniy zvonok: reklama`. Правило, записанное
 *     с одного звонка целиком, второй не поймает — см. тест про всю подпись.
 *  4. **Формат `Наименование: категория` не гарантирован**: из 13 подписей у 5 двоеточия нет
 *     вовсе (`AYSBERG-ZAPAD Transport`, `Rostelecom`, три вида `Agent Rostelecom`).
 *  5. **Категории в основном вне перечня Минцифры**: из наблюдённых `dostavka`, `finansy`,
 *     `agentstvo`, `IT`, `Transport`, `avto torgovlya`, `reklama` перечню принадлежит одна.
 *  6. **Оператор присылает служебные метки вместо наименования**: `Zvonok bez markirovki`.
 */
class OperatorSignatureCorpusTest {

    private val normalizer = RuFastPathNormalizer()
    private val builder = SnapshotBuilder(normalizer)

    /** Корни категорий для подписей, где категория стоит без `:` (`AYSBERG-ZAPAD Transport`). */
    private val dict = setOf("dostavka", "it", "finans", "reklam", "opros", "transport")

    /** Корпус целиком: подпись — сколько разных номеров под ней встретилось. */
    private val corpus = mapOf(
        "OOO UG KC Agent Rostelecom" to 10,
        "Agent Rostelecom IT Link Sol" to 5,
        "Rostelecom" to 7,
        "BANK RUSSKIY STANDART: finansy" to 6,
        "OOO Magnum: dostavka" to 4,
        "POChTA Ros.: dostavka" to 2,
        "OOO Mnogomashin: avto torgovlya" to 2,
        "OOO SDEK-GLOBAL: dostavka" to 1,
        "OOO Poleznyy Zvonok: agentstvo" to 1,
        "OOO Polezniy zvonok: reklama" to 1,
        "Yandex: IT" to 1,
        "AYSBERG-ZAPAD Transport" to 1,
        "Zvonok bez markirovki" to 1,
    )

    private fun facts(name: String, number: String = "+79001466539") = CallFacts(
        number = normalizer.normalize(number, "RU"),
        presentation = NumberPresentation.ALLOWED,
        name = NameCanonizer.canonize(name, dict),
        nameSource = NameSource.CNAP,
        inContacts = false,
        isEmergency = false,
    )

    private var nextId = 1L
    private fun rule(
        target: RuleTarget,
        matchType: MatchType,
        pattern: String,
        variants: Boolean = true,
    ) = Rule(
        id = nextId++,
        title = "$target/$matchType $pattern",
        target = target,
        matchType = matchType,
        pattern = pattern,
        action = CallAction.REJECT,
        orderIndex = 600,
        translitVariants = variants,
    )

    private fun snapshot(vararg rules: Rule) =
        builder.build(rules.toList(), DecisionSettings(categoryDictionary = dict))

    private fun action(name: String, snapshot: RuleSnapshot) =
        RuleEngine.decide(facts(name), snapshot, Budget.unlimited()).action

    /** Кого из корпуса ловит снимок. Ответ на вопрос «что будет с моими звонками». */
    private fun caught(snapshot: RuleSnapshot): Set<String> =
        corpus.keys.filter { action(it, snapshot) == CallAction.REJECT }.toSet()

    // --- пункт 3: одно юрлицо в двух написаниях ----------------------------------------------

    @Test
    fun `одно правило по наименованию ловит оба написания юрлица`() {
        // Кириллический шаблон: канонический транслит даёт `poleznyi`, а оператор присылает
        // `Poleznyy` и `Polezniy`. Сводит их класс вариантов `yy/iy/yi` — из-за этого случая
        // он и появился.
        val s = snapshot(rule(RuleTarget.NAME_ORG, MatchType.CONTAINS, "полезный звонок"))

        assertEquals(
            setOf("OOO Poleznyy Zvonok: agentstvo", "OOO Polezniy zvonok: reklama"),
            caught(s),
            "правило обязано поймать оба написания и не задеть остальной корпус",
        )
    }

    @Test
    fun `правило по всей подписи ловит только один звонок из двух`() {
        // Ровно тот отказ, из-за которого предзаполнять правило целой подписью нельзя:
        // категория у одного и того же юрлица меняется, и правило «равно подписи» промахивается.
        val s = snapshot(
            rule(RuleTarget.NAME, MatchType.EXACT, "OOO Poleznyy Zvonok: agentstvo")
        )

        assertEquals(CallAction.REJECT, action("OOO Poleznyy Zvonok: agentstvo", s))
        assertEquals(
            CallAction.ALLOW,
            action("OOO Polezniy zvonok: reklama", s),
            "то же юрлицо, другая категория и другой транслит — правило по всей подписи мимо",
        )
    }

    // --- пункт 2: ротация номеров ------------------------------------------------------------

    @Test
    fun `одно правило по названию заменяет тридцать четыре правила по номерам`() {
        val byName = snapshot(rule(RuleTarget.NAME_ORG, MatchType.TOKEN, "rostelecom"))
        val rostelecomFamily = setOf(
            "OOO UG KC Agent Rostelecom",
            "Agent Rostelecom IT Link Sol",
            "Rostelecom",
        )
        assertEquals(rostelecomFamily, caught(byName))

        // Столько номеров пришлось бы перечислить правилами, чтобы добиться того же — и завтра
        // список сменится, потому что подпись остаётся, а номера у этих звонков одноразовые.
        val numbersBehind = corpus.filterKeys { it in rostelecomFamily }.values.sum()
        assertEquals(22, numbersBehind)
    }

    // --- пункты 4-6: чем подпись ломает разбор -----------------------------------------------

    @Test
    fun `категория без двоеточия опознаётся по словарю`() {
        val s = snapshot(rule(RuleTarget.NAME_CATEGORY, MatchType.EXACT, "transport"))
        assertEquals(setOf("AYSBERG-ZAPAD Transport"), caught(s))
    }

    @Test
    fun `правило по категории ловит все службы доставки`() {
        val s = snapshot(rule(RuleTarget.NAME_CATEGORY, MatchType.EXACT, "доставка"))
        assertEquals(
            setOf("OOO Magnum: dostavka", "POChTA Ros.: dostavka", "OOO SDEK-GLOBAL: dostavka"),
            caught(s),
        )
    }

    @Test
    fun `служебная метка оператора опознаётся, а не считается наименованием`() {
        val forms = NameCanonizer.canonize("Zvonok bez markirovki", dict)
        assertTrue(forms.isOperatorLabel, "иначе в статистике появится компания с таким названием")
    }

    @Test
    fun `наблюдённая категория пишется agentstvo, а не agenstvo`() {
        // В обеих выгрузках — `agentstvo`, написания `agenstvo` в данных нет ни разу.
        // Правило, собранное из списка с опечаткой, не сработает никогда, и понять это
        // по интерфейсу невозможно: правило выглядит корректным.
        val forms = NameCanonizer.canonize("OOO Poleznyy Zvonok: agentstvo", dict)
        assertEquals("agentstvo", forms.category?.fold)

        val wrong = snapshot(rule(RuleTarget.NAME_CATEGORY, MatchType.EXACT, "agenstvo"))
        assertEquals(
            emptySet(),
            caught(wrong),
            "шаблон с пропущенной `t` не ловит ничего — это и есть цена опечатки в перечне",
        )
    }

    // --- пункт 1: подпись внутри номера стабильна --------------------------------------------

    @Test
    fun `корпус зафиксирован целиком`() {
        // Страховка от тихого расхождения: если корпус пополнится, тест заставит перечитать
        // выводы выше, а не молча продолжить опираться на устаревшую картину.
        assertEquals(13, corpus.size)
        assertEquals(42, corpus.values.sum(), "столько номеров стояло за 13 подписями")
    }
}
