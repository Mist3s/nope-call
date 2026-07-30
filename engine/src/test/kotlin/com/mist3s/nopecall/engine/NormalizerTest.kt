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
}
