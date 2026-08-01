package com.mist3s.nopecall.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Векторы канонизации названий — таблица ТЗ §6.3.2 целиком.
 *
 * Это не «тесты кода», а зафиксированный корпус реально наблюдённых операторских подписей.
 * Любое изменение канонизации обязано его проходить, иначе правила пользователей перестанут
 * срабатывать после обновления.
 */
class NameCanonizerTest {

    /**
     * Словарь категорий: нужен только для подписей, где категория стоит без двоеточия.
     *
     * Корни здесь должны быть однозначными. `agen` не годится: он совпадает и с категорией
     * `agenstvo`, и со словом `Agent` из наименования `Agent Rostelecom`. Для сопоставления
     * уже выделенной категории короткий корень нормален (пресет «категория начинается
     * с `agen`»), а для распознавания — нет.
     */
    private val dict = setOf("dostavka", "it", "finans", "reklam", "agenstvo", "opros", "transport")

    private fun canon(raw: String) = NameCanonizer.canonize(raw, dict)

    // --- формат `[Наименование]: [Категория]` -----------------------------------------------

    @Test
    fun `делит по первому двоеточию`() {
        val n = canon("POChTA Ros.: dostavka")
        assertEquals("pochtaros", n.org.fold)
        assertEquals(listOf("pochta", "ros"), n.org.tokens)
        assertEquals("dostavka", n.category?.fold)
    }

    @Test
    fun `категория из двух слов`() {
        val n = canon("OOO Mnogomashin: avto torgovlya")
        assertEquals("ooomnogomashin", n.org.fold)
        assertEquals("avtotorgovlya", n.category?.fold)
        assertEquals(listOf("avto", "torgovlya"), n.category?.tokens)
    }

    @Test
    fun `короткая категория не путается с наименованием`() {
        // Правило «категория it» обязано ловить `botto: IT` и НЕ срабатывать от `IT Link`
        // внутри наименования — ради этого наименование и категория разделены.
        val botto = canon("botto: IT")
        assertEquals("botto", botto.org.fold)
        assertEquals("it", botto.category?.fold)

        val agent = canon("Agent Rostelecom IT Link Sol")
        assertNull(agent.category, "у этой подписи категории нет")
        assertTrue(agent.org.tokens.contains("it"), "но `it` есть среди слов наименования")
    }

    @Test
    fun `банк с многословным наименованием`() {
        val n = canon("BANK RUSSKIY STANDART: finansy")
        assertEquals(listOf("bank", "russkiy", "standart"), n.org.tokens)
        assertEquals("finansy", n.category?.fold)
    }

    // --- категория без двоеточия, по словарю -----------------------------------------------

    @Test
    fun `категория без двоеточия распознаётся по словарю`() {
        val n = canon("AYSBERG-ZAPAD Transport")
        assertEquals("transport", n.category?.fold)
        assertEquals("aysbergzapad", n.org.fold)
        assertEquals(listOf("aysberg", "zapad"), n.org.tokens)
    }

    @Test
    fun `без словаря категория не выдумывается`() {
        val n = NameCanonizer.canonize("AYSBERG-ZAPAD Transport", categoryDictionary = emptySet())
        assertNull(n.category)
        assertEquals("aysbergzapadtransport", n.org.fold)
    }

    // --- подписи без категории --------------------------------------------------------------

    @Test
    fun `подписи без категории остаются целиком наименованием`() {
        assertEquals("rostelecom", canon("Rostelecom").org.fold)
        assertEquals("paosovkombank", canon("PAO SOVKOMBANK").org.fold)
        assertEquals(
            listOf("ooo", "ug", "kc", "agent", "rostelecom"),
            canon("OOO UG KC Agent Rostelecom").org.tokens,
        )
    }

    // --- разделители: все виды и их отсутствие ---------------------------------------------

    @Test
    fun `разделители любые и их отсутствие дают одинаковый fold`() {
        val expected = "oooromashkareklama"
        assertEquals(expected, canon("OOO Romashka, reklama").whole.fold)
        assertEquals(expected, canon("OOO_Romashka_Reklama").whole.fold)
        assertEquals(expected, canon("OOORomashkaReklama").whole.fold)
        assertEquals("romashkareklama", canon("Romashka.Reklama").whole.fold)
    }

    @Test
    fun `слитное написание не даёт отдельных слов`() {
        // Осознанное следствие: «содержит» сработает, «содержит слово» — нет.
        val merged = canon("OOORomashkaReklama")
        assertEquals(listOf("oooromashkareklama"), merged.whole.tokens)
        assertTrue(merged.whole.fold.contains("reklama"))
    }

    @Test
    fun `по смене регистра слова не режутся`() {
        // Транслитерация даёт POChTA и SHCHerbakov: camelCase-разбор сломал бы сопоставление.
        assertEquals(listOf("pochta"), canon("POChTA").whole.tokens)
        assertEquals(listOf("shcherbakov"), canon("SHCHerbakov").whole.tokens)
    }

    // --- транслитерация и омоглифы ---------------------------------------------------------

    @Test
    fun `кириллица и латиница сходятся в одном виде`() {
        assertEquals("reklama", canon("РЕКЛАМА").whole.fold)
        assertEquals("reklama", canon("Reklama").whole.fold)
        assertEquals("reklama", canon("реклама").whole.fold)
    }

    @Test
    fun `омоглифы сводятся в слове со смешанным алфавитом`() {
        // `Rеklamа` — латинское слово с кириллическими `е` и `а`.
        assertEquals("reklama", canon("Rеklamа").whole.fold)
    }

    @Test
    fun `честная кириллица омоглифами не ломается`() {
        // СБЕР целиком кириллический: свести его в CBEP было бы ошибкой.
        assertEquals("sber", canon("СБЕР").whole.fold)
        assertEquals("sberbank", canon("Сбербанк").whole.fold)
    }

    @Test
    fun `невидимые символы выбрасываются`() {
        assertEquals("reklama", canon("rek​lam­a").whole.fold)
    }

    @Test
    fun `шаблон канонизируется тем же конвейером`() {
        // Ради этого всё и делается: пользователь пишет по-русски, подпись приходит транслитом.
        assertEquals(canon("Reklama").whole.fold, NameCanonizer.canonizePattern("реклама"))
        assertEquals(canon("PAO SOVKOMBANK").org.fold, NameCanonizer.canonizePattern("ПАО Совкомбанк"))
    }

    // --- метки оператора --------------------------------------------------------------------

    @Test
    fun `метка оператора опознаётся и не считается наименованием`() {
        val n = canon("Zvonok bez markirovki")
        assertTrue(n.isOperatorLabel)
        assertTrue(n.whole.tokens.contains("markirovki"))
    }

    @Test
    fun `обычная подпись меткой не считается`() {
        assertFalse(canon("PAO SOVKOMBANK").isOperatorLabel)
    }

    // --- усечение на 32 символах ------------------------------------------------------------

    @Test
    fun `усечённая подпись канонизируется без потерь`() {
        // `Agent Rostelecom IT Link Sol` — 28 символов, последнее слово оборвано.
        // Значит правила «заканчивается на» и «точное» по названию ненадёжны, а «содержит»
        // и «содержит слово» работают.
        val n = canon("Agent Rostelecom IT Link Sol")
        assertTrue(n.org.tokens.contains("agent"))
        assertTrue(n.org.tokens.contains("rostelecom"))
        assertEquals("sol", n.org.tokens.last())
    }

    @Test
    fun `класс «ый» сводит наблюдённые написания одного юрлица`() {
        // Корпус: `OOO Poleznyy Zvonok` и `OOO Polezniy zvonok` — одна организация.
        // Закрепляется явно, потому что следующая правка класса вариантов обязана
        // сохранить именно это, а не «что-нибудь похожее».
        val pattern = NameCanonizer.canonizePattern("Полезный")
        val variants = Translit.variants(pattern).toSet()

        assertTrue("poleznyy" in variants, "написание с yy: $variants")
        assertTrue("polezniy" in variants, "написание с iy: $variants")
    }

    @Test
    fun `шаблон не раскрывается в мусорные написания`() {
        // «IT» раскрывалось в `yt` и ловило `BYTOVAYA TEHNIKA`: замена шла по всей строке,
        // а односимвольные `y` и `i` стояли в одном классе. Расширение блокировки —
        // то направление, в котором §1.1 требует осторожности.
        val variants = Translit.variants(NameCanonizer.canonizePattern("IT")).toSet()

        assertEquals(setOf("it"), variants, "у двухбуквенного шаблона вариантов быть не должно")
    }

    @Test
    fun `пустое и отсутствующее название`() {
        assertEquals(NameForms.NONE, NameCanonizer.canonize(null))
        assertEquals(NameForms.NONE, NameCanonizer.canonize("   "))
    }
}
