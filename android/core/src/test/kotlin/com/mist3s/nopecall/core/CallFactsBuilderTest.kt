package com.mist3s.nopecall.core

import com.mist3s.nopecall.core.facts.CallDetailsReader.Companion.PRESENTATION_PAYPHONE
import com.mist3s.nopecall.core.facts.CallDetailsReader.Companion.PRESENTATION_RESTRICTED
import com.mist3s.nopecall.core.facts.CallDetailsReader.Companion.PRESENTATION_UNKNOWN
import com.mist3s.nopecall.core.facts.CallFactsBuilder
import com.mist3s.nopecall.core.facts.ContactMembership
import com.mist3s.nopecall.core.facts.EmergencyNumbers
import com.mist3s.nopecall.engine.DecisionSettings
import com.mist3s.nopecall.engine.NameSource
import com.mist3s.nopecall.engine.NumberPresentation
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * Построение фактов о звонке из того, что отдала система (ТЗ §6.1, §6.3, §5.4).
 *
 * Все случаи §5.4 — скрытый номер, неопределённый, таксофон, отсутствующий обработчик, SIP —
 * проверяются здесь, на голой JVM. Это и есть смысл шва: без него они проверялись бы только
 * инструментальными тестами (критерий приёмки ТЗ §12.2).
 */
class CallFactsBuilderTest {

    private val settings = DecisionSettings(
        categoryDictionary = setOf("dostavka", "it", "reklam", "transport"),
    )

    private fun builder(
        contacts: ContactMembership = ContactMembership.NONE,
        emergency: EmergencyNumbers = EmergencyNumbers.NONE,
    ) = CallFactsBuilder(RuFastPathNormalizer(), contacts, emergency)

    // --- номер ------------------------------------------------------------------------------

    @Test
    fun `обычный номер разбирается во все формы`() {
        val f = builder().build(FakeCallDetails(handleValue = "8 999 123-45-67"), settings)
        assertEquals(NumberPresentation.ALLOWED, f.presentation)
        assertEquals("79991234567", f.number.canonicalDigits)
        assertEquals("+79991234567", f.number.e164)
        assertTrue(f.hasNumber)
    }

    @Test
    fun `скрытый номер не разбирается и не выдумывается`() {
        // У скрытого номера обработчика нет. Придумать номер было бы хуже, чем признать,
        // что сопоставлять нечем: правила по номеру должны пропускаться, а не срабатывать
        // на пустоте (ТЗ §5.4).
        val f = builder().build(
            FakeCallDetails(handleValue = null, handlePresentation = PRESENTATION_RESTRICTED),
            settings,
        )
        assertEquals(NumberPresentation.RESTRICTED, f.presentation)
        assertFalse(f.hasNumber)
        assertTrue(f.number.digits.isEmpty())
        assertNull(f.number.e164)
    }

    @Test
    fun `номер с признаком скрытого игнорируется, даже если обработчик есть`() {
        // Бывает: система отдаёт и обработчик, и признак «скрыт». Верить надо признаку.
        val f = builder().build(
            FakeCallDetails(handleValue = "+79991234567", handlePresentation = PRESENTATION_RESTRICTED),
            settings,
        )
        assertFalse(f.hasNumber)
        assertTrue(f.number.digits.isEmpty())
    }

    @Test
    fun `таксофон и неопределённый номер различаются`() {
        assertEquals(
            NumberPresentation.PAYPHONE,
            builder().build(FakeCallDetails(handlePresentation = PRESENTATION_PAYPHONE), settings).presentation,
        )
        assertEquals(
            NumberPresentation.UNKNOWN,
            builder().build(FakeCallDetails(handlePresentation = PRESENTATION_UNKNOWN), settings).presentation,
        )
    }

    @Test
    fun `неизвестное значение presentation не приводит к блокировке`() {
        // Прошивка может отдать значение, которого мы не знаем. Трактуем как «не определён»,
        // а не как «разрешён»: иначе неизвестное значение попало бы под правила по номеру,
        // которого фактически нет (ТЗ §1.1).
        val f = builder().build(FakeCallDetails(handlePresentation = 99), settings)
        assertEquals(NumberPresentation.UNKNOWN, f.presentation)
        assertFalse(f.hasNumber)
    }

    @Test
    fun `SIP-обработчик не превращается в телефонный номер`() {
        val f = builder().build(
            FakeCallDetails(handleScheme = "sip", handleValue = "user@example.com"),
            settings,
        )
        assertEquals("sip", f.number.scheme)
        assertNull(f.number.e164)
        assertFalse(f.hasNumber)
    }

    @Test
    fun `отсутствующий обработчик не роняет построение`() {
        val f = builder().build(FakeCallDetails(handleScheme = null, handleValue = null), settings)
        assertFalse(f.hasNumber)
        assertFalse(f.hasName)
    }

    // --- название ---------------------------------------------------------------------------

    @Test
    fun `операторская подпись разбирается на наименование и категорию`() {
        val f = builder().build(FakeCallDetails(callerDisplayName = "POChTA Ros.: dostavka"), settings)
        assertEquals(NameSource.CNAP, f.nameSource)
        assertEquals("pochtaros", f.name.org.fold)
        assertEquals("dostavka", f.name.category?.fold)
        assertTrue(f.hasName)
    }

    @Test
    fun `метка оператора опознаётся отдельным источником`() {
        // Иначе в статистике появилась бы «компания „Звонок без маркировки“», а правило
        // по наименованию ловило бы метку (ТЗ §6.3.1).
        val f = builder().build(FakeCallDetails(callerDisplayName = "Zvonok bez markirovki"), settings)
        assertEquals(NameSource.CNAP_OPERATOR_LABEL, f.nameSource)
        assertTrue(f.name.isOperatorLabel)
    }

    @Test
    fun `название со признаком скрытого не используется`() {
        val f = builder().build(
            FakeCallDetails(
                callerDisplayName = "PAO SOVKOMBANK",
                callerDisplayNamePresentation = PRESENTATION_RESTRICTED,
            ),
            settings,
        )
        assertEquals(NameSource.NONE, f.nameSource)
        assertFalse(f.hasName)
    }

    @Test
    fun `подпись принимается, когда presentation не заполнен`() {
        // Ноль — не значение `TelecomManager.PRESENTATION_*`, а «поле не заполнено».
        // На Pixel 3a (Android 12) в onScreenCall там ноль всегда, и прежнее условие
        // «presentation != ALLOWED» выбросило бы пришедшую подпись молча.
        val f = builder().build(
            FakeCallDetails(
                callerDisplayName = "OOO SDEK-GLOBAL: dostavka",
                callerDisplayNamePresentation = 0,
            ),
            settings,
        )
        assertEquals(NameSource.CNAP, f.nameSource)
        assertEquals("dostavka", f.name.category?.fold)
        assertTrue(f.hasName)
    }

    @Test
    fun `название с признаком «неопределено» или «таксофон» не используется`() {
        // Здесь система прямо говорит, что показывать нечего, — в отличие от незаполненного поля.
        for (presentation in listOf(PRESENTATION_UNKNOWN, PRESENTATION_PAYPHONE)) {
            val f = builder().build(
                FakeCallDetails(
                    callerDisplayName = "PAO SOVKOMBANK",
                    callerDisplayNamePresentation = presentation,
                ),
                settings,
            )
            assertEquals(NameSource.NONE, f.nameSource, "presentation=$presentation")
            assertFalse(f.hasName, "presentation=$presentation")
        }
    }

    @Test
    fun `пустое название считается отсутствующим`() {
        for (name in listOf(null, "", "   ")) {
            val f = builder().build(FakeCallDetails(callerDisplayName = name), settings)
            assertEquals(NameSource.NONE, f.nameSource)
            assertFalse(f.hasName)
        }
    }

    // --- контакты и экстренные номера --------------------------------------------------------

    @Test
    fun `недоступный индекс контактов даёт неизвестно, а не ложь`() {
        // Разница существенная: `false` означает «точно не в контактах» и позволяет широким
        // правилам сработать, а `null` помечает решение флагом CONTACT_INDEX_STALE.
        val f = builder(contacts = ContactMembership.UNKNOWN).build(FakeCallDetails(), settings)
        assertNull(f.inContacts)
    }

    @Test
    fun `индекс контактов спрашивается по каноническому номеру`() {
        var asked: String? = null
        val f = builder(contacts = { key -> asked = key; true })
            .build(FakeCallDetails(handleValue = "8 999 123-45-67"), settings)
        assertEquals("+79991234567", asked)
        assertEquals(true, f.inContacts)
    }

    @Test
    fun `экстренный номер помечается`() {
        val f = builder(emergency = { digits -> digits == "112" })
            .build(FakeCallDetails(handleValue = "112"), settings)
        assertTrue(f.isEmergency)
        assertTrue(f.number.isShort)
    }

    @Test
    fun `без проверки экстренных номеров признак не выставляется`() {
        // Гарантия при этом не исчезает: движок всё равно сверяется с резервным списком
        // в настройках снимка (ТЗ §5.4).
        val f = builder().build(FakeCallDetails(handleValue = "112"), settings)
        assertFalse(f.isEmergency)
    }
}
