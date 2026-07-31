package com.mist3s.nopecall.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Векторы нормализации номера — таблица ТЗ §6.1.
 *
 * Главное здесь — не «код работает», а что все записи одного номера сводятся к одному виду.
 * Без этого правило «начинается с `+7495`» не поймает звонок, пришедший как `84951234567`,
 * и это самый частый реальный случай.
 */
class NormalizerTest {

    private val n = RuFastPathNormalizer()

    private fun norm(raw: String?) = n.normalize(raw, "RU")

    /** Факты без названия: здесь проверяется только номер, подпись решению мешать не должна. */
    private fun facts(raw: String) = CallFacts(
        number = norm(raw),
        presentation = NumberPresentation.ALLOWED,
        name = NameForms.NONE,
        nameSource = NameSource.NONE,
        inContacts = false,
        isEmergency = false,
    )

    @Test
    fun `все записи одного номера дают один канонический вид`() {
        val expected = "79991234567"
        for (raw in listOf(
            "+7 (999) 123-45-67",
            "8 999 123-45-67",
            "79991234567",
            "+79991234567",
            "8(999)123-45-67",
            "9991234567",
        )) {
            assertEquals(expected, norm(raw).canonicalDigits, "не сошлось на «$raw»")
            assertEquals("+79991234567", norm(raw).e164, "не сошлось на «$raw»")
        }
    }

    @Test
    fun `московский номер в обеих записях`() {
        assertEquals("74951234567", norm("+7-495-123-45-67").canonicalDigits)
        assertEquals("74951234567", norm("84951234567").canonicalDigits)
    }

    @Test
    fun `цифры сохраняются как пришли, отдельно от канонического вида`() {
        // raw и digits нужны, чтобы объяснить пользователю решение и отладить сопоставление.
        val f = norm("8 999 123-45-67")
        assertEquals("89991234567", f.digits)
        assertEquals("79991234567", f.canonicalDigits)
        assertEquals("9991234567", f.national)
        assertEquals("8 999 123-45-67", f.raw)
    }

    @Test
    fun `кандидаты для сопоставления включают все виды`() {
        val c = norm("8 999 123-45-67").candidates
        assertTrue(c.contains("79991234567"), "канонический")
        assertTrue(c.contains("9991234567"), "национальный")
        assertTrue(c.contains("89991234567"), "как пришёл")
    }

    @Test
    fun `добавочный отрезается`() {
        val f = norm("+7 999 123-45-67,102")
        assertEquals("79991234567", f.canonicalDigits)
        assertEquals("+79991234567", f.e164)
    }

    @Test
    fun `короткие номера не переводятся в E164`() {
        for (raw in listOf("900", "112", "101")) {
            val f = norm(raw)
            assertTrue(f.isShort, "«$raw» должен быть коротким")
            assertNull(f.e164)
            assertEquals(raw, f.canonicalDigits)
        }
    }

    @Test
    fun `SIP-обработчик не считается телефонным номером`() {
        val f = norm("sip:user@example.com")
        assertEquals("sip", f.scheme)
        assertNull(f.e164)
        assertTrue(f.digits.isEmpty())
    }

    @Test
    fun `пустой и отсутствующий номер`() {
        assertEquals(NumberForms.EMPTY, norm(null))
        assertEquals("", norm("").digits)
    }

    @Test
    fun `иностранный номер отдаётся резервной реализации`() {
        // Быстрый путь знает только РФ. Без резерва он честно отдаёт то, что разобрал,
        // и не выдумывает E.164 — иначе правила по «международному формату» врали бы.
        val f = norm("+380441234567")
        assertNull(f.e164, "быстрый путь не должен угадывать чужую страну")
        assertEquals("380441234567", f.digits)

        val withFallback = RuFastPathNormalizer(fallback = object : PhoneNumberNormalizer {
            override fun normalize(raw: String?, region: String) = NumberForms(
                raw = raw ?: "",
                digits = "380441234567",
                e164 = "+380441234567",
                national = "441234567",
                canonicalDigits = "380441234567",
                isShort = false,
            )
        })
        assertEquals("+380441234567", withFallback.normalize("+380441234567", "RU").e164)
    }

    // --- международная запись против магистральной «8» ---------------------------------------

    @Test
    fun `иностранный номер с кодом страны на восьмёрку не становится российским`() {
        // Дефект: правило «11 цифр, первая 8 → отбросить 8» применялось к любому номеру, и
        // японский +81 3 1234 5678 превращался в 71312345678 — правило «начинается с 7»
        // блокировало звонок из Японии. Прямое нарушение ТЗ §1.1.
        val f = norm("+81312345678")
        assertEquals("81312345678", f.canonicalDigits, "цифры международной записи как есть")
        assertEquals("+81312345678", f.e164, "запись уже была в E.164, угадывать нечего")
        assertNull(f.national, "национальной части чужого плана нумерации быстрый путь не знает")
        assertTrue(
            f.candidates.none { it == "71312345678" },
            "российская форма чужого номера не должна попадать в кандидаты: ${f.candidates}",
        )
        assertTrue(
            f.candidates.none { it.startsWith("7") },
            "ни один кандидат не должен выглядеть российским: ${f.candidates}",
        )
    }

    @Test
    fun `правило «начинается с 7» не блокирует японский номер`() {
        // Тот же дефект, но глазами пользователя: правило по российским номерам не имеет права
        // задеть звонок из Японии. Проверяется сквозь снимок, потому что согласованность
        // «канонизированный шаблон ↔ кандидаты номера» ломается именно на стыке.
        val snapshot = SnapshotBuilder(n).build(
            listOf(
                Rule(
                    id = 1,
                    title = "все российские",
                    target = RuleTarget.NUMBER,
                    matchType = MatchType.PREFIX,
                    pattern = "7",
                    action = CallAction.REJECT,
                    orderIndex = 600,
                ),
            ),
        )
        assertEquals(
            CallAction.ALLOW,
            RuleEngine.decide(facts("+81312345678"), snapshot, Budget.unlimited()).action,
            "японский номер не российский",
        )
        assertEquals(
            CallAction.REJECT,
            RuleEngine.decide(facts("89991234567"), snapshot, Budget.unlimited()).action,
            "российский междугородний тем же правилом ловиться обязан",
        )
    }

    @Test
    fun `точное правило по иностранному номеру совпадает с ним же`() {
        // Обратная сторона: отказавшись от российской схемы, нельзя потерять сопоставление.
        // Шаблон проходит тот же конвейер, что и входные данные (ТЗ §6.2.1), поэтому точное
        // правило «+81 3 1234 5678» обязано поймать звонок с этого номера.
        val snapshot = SnapshotBuilder(n).build(
            listOf(
                Rule(
                    id = 1,
                    title = "японский офис",
                    target = RuleTarget.NUMBER,
                    matchType = MatchType.EXACT,
                    pattern = "+81 3 1234 5678",
                    action = CallAction.REJECT,
                    orderIndex = 600,
                ),
            ),
        )
        assertEquals(
            CallAction.REJECT,
            RuleEngine.decide(facts("+81312345678"), snapshot, Budget.unlimited()).action,
        )
    }

    @Test
    fun `магистральная восьмёрка срезается у неинтернациональной записи`() {
        // Регресс, который правка легко могла внести: 89991234567 без «+» — это российский
        // междугородний набор, и он обязан сводиться к 79991234567.
        assertEquals("79991234567", norm("89991234567").canonicalDigits)
        assertEquals("+79991234567", norm("89991234567").e164)
        assertEquals("9991234567", norm("89991234567").national)
    }

    @Test
    fun `российские записи по-прежнему сводятся к одному виду`() {
        // Тот же регресс с другой стороны: три записи российского номера не должны разъехаться
        // из-за проверки международного признака.
        for (raw in listOf("+79991234567", "79991234567", "9991234567")) {
            assertEquals("79991234567", norm(raw).canonicalDigits, "не сошлось на «$raw»")
            assertEquals("+79991234567", norm(raw).e164, "не сошлось на «$raw»")
        }
    }

    @Test
    fun `набор через 00 читается как международная запись`() {
        // 00 — префикс выхода на международную линию, то есть такой же явный признак, как «+».
        // Без этого 0081312345678 (13 цифр) молча уехал бы в резерв, а 0089991234567 —
        // в российскую схему.
        val f = norm("0081312345678")
        assertEquals("81312345678", f.canonicalDigits, "00 — способ набора, а не часть номера")
        assertEquals("+81312345678", f.e164)
        assertNull(f.national)
        assertEquals("0081312345678", f.digits, "цифры как пришли сохраняются целиком")
        assertTrue(
            f.candidates.none { it.startsWith("7") },
            "российских кандидатов быть не должно: ${f.candidates}",
        )
    }

    @Test
    fun `международная запись с кодом страны 7 остаётся российской`() {
        // Граница правки: «не тянуть в российскую схему» относится только к чужому коду страны.
        // +7 — это Россия, и все её формы обязаны строиться как раньше.
        val f = norm("+7 495 123-45-67")
        assertEquals("74951234567", f.canonicalDigits)
        assertEquals("+74951234567", f.e164)
        assertEquals("4951234567", f.national)
        assertTrue(f.candidates.contains("74951234567"), "канонический вид среди кандидатов")
    }
}
