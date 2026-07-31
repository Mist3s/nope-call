package com.mist3s.nopecall.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.contacts.ContactNumberSource
import com.mist3s.nopecall.core.storage.JournalRepository
import com.mist3s.nopecall.core.storage.JournalRepository.ContactsState
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.RuleEntity
import com.mist3s.nopecall.core.storage.ScreeningEventEntity
import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.NumberForms
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import com.mist3s.nopecall.engine.RuleSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Предпросмотр правила: три величины, а не одна (ТЗ §9.3, критерий приёмки §18 п. 16).
 *
 * Показатели «перекроет N разрешающих правил» и «зацепит N номеров книги» существуют потому,
 * что подсчёт по журналу на этот вопрос не отвечает: номер, по которому ещё не звонили,
 * в журнале отсутствует, а в телефонной книге есть — и именно он превращается в жалобу
 * «заблокировали врача».
 *
 * Главный дефект, который здесь возможен, — выдать «не смогли проверить» за «ноль».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulePreviewTest {

    private lateinit var db: NopeCallDatabase
    private lateinit var journal: JournalRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            NopeCallDatabase::class.java,
        ).allowMainThreadQueries().build()
        journal = JournalRepository(db)
    }

    @After
    fun tearDown() = db.close()

    /**
     * Книга в памяти вместо `ContactsContract`: показатель обязан проверяться без устройства,
     * иначе ошибка в нём обнаружится только жалобой пользователя.
     *
     * `null` в [raw] — разрешения нет.
     */
    private class FakeContacts(private val raw: List<String>?) : ContactNumberSource {
        override fun numbers(limit: Int): List<NumberForms>? =
            raw?.map { NORMALIZER.normalize(it, "RU") }?.take(limit)

        private companion object {
            val NORMALIZER = RuFastPathNormalizer()
        }
    }

    private suspend fun rule(
        pattern: String,
        canonical: String = pattern,
        action: String = "ALLOW",
        target: String = "NUMBER",
        matchType: String = "EXACT",
        enabled: Boolean = true,
        variants: String = "",
    ): Long = db.rules().insert(
        RuleEntity(
            title = "Правило $pattern",
            targetType = target,
            matchType = matchType,
            pattern = pattern,
            patternCanonical = canonical,
            patternVariants = variants,
            action = action,
            orderIndex = 0,
            isEnabled = enabled,
            createdAt = NOW,
            updatedAt = NOW,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )
    )

    private suspend fun event(at: Long, digits: String) = db.events().insert(
        ScreeningEventEntity(
            occurredAt = at,
            rawNumber = "+$digits",
            digits = digits,
            e164 = "+$digits",
            presentation = "ALLOWED",
            nameSource = "NONE",
            action = "ALLOW",
            reason = "DEFAULT_ACTION",
            latencyMs = 10,
            budgetMs = 1500,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )
    )

    // --- номера телефонной книги --------------------------------------------------------------

    @Test
    fun `правило «начинается с 7495» находит контакт с этим кодом и не находит остальные`() {
        // Ради этого показатель и существует: по журналу такой контакт не виден, пока он
        // не позвонил, а правило его уже блокирует.
        val contacts = FakeContacts(listOf("+74951234567", "+79991234567"))

        val preview = runBlocking {
            journal.previewMatches("NUMBER", "PREFIX", "7495", contacts = contacts)
        }

        assertEquals(1, preview.contactsCovered, "под правило попадает ровно один номер книги")
        assertTrue(!preview.contactsTruncated, "книга прочитана целиком")
    }

    @Test
    fun `контакт находится в любом виде записи номера`() {
        // Пользователь не обязан угадывать, в каком виде номер записан в книге: `8495…`
        // и `+7495…` — один и тот же номер, и движок сопоставляет по всем видам.
        val contacts = FakeContacts(listOf("84951234567"))
        assertEquals(
            1,
            runBlocking {
                journal.previewMatches("NUMBER", "PREFIX", "7495", contacts = contacts)
            }.contactsCovered,
        )
    }

    @Test
    fun `«книга не при чём» и «книгу не прочитали» — разные состояния`() {
        // Тот самый дефект, который дошёл до устройства: `null` значил и то и другое, и экран
        // сообщал «нет доступа к телефонной книге» у правила по операторской подписи —
        // при выданном разрешении. Сообщать неправду о разрешениях нельзя: пользователь
        // пойдёт их выдавать и не поймёт, почему ничего не изменилось.
        val byName = runBlocking {
            journal.previewMatches(
                target = "NAME_ORG",
                matchType = "TOKEN",
                canonicalPattern = "romashka",
                contacts = FakeContacts(listOf("+74951234567")),
            )
        }
        assertEquals(
            ContactsState.NOT_APPLICABLE,
            byName.contactsState,
            "правило по названию к книге отношения не имеет",
        )
        assertNull(byName.contactsCovered)

        val noAccess = runBlocking {
            journal.previewMatches(
                target = "NUMBER",
                matchType = "PREFIX",
                canonicalPattern = "7495",
                contacts = FakeContacts(null),
            )
        }
        assertEquals(
            ContactsState.NO_ACCESS,
            noAccess.contactsState,
            "правило про номера, но книга недоступна",
        )
        assertNull(noAccess.contactsCovered)

        val counted = runBlocking {
            journal.previewMatches(
                target = "NUMBER",
                matchType = "PREFIX",
                canonicalPattern = "7495",
                contacts = FakeContacts(listOf("+79991112233")),
            )
        }
        assertEquals(ContactsState.COUNTED, counted.contactsState, "книга прочитана")
        assertEquals(0, counted.contactsCovered, "ни один контакт не подошёл — это ноль, не «не знаю»")
    }

    @Test
    fun `без доступа к контактам предпросмотр отвечает «не знаю», а не «ноль»`() {
        // «Ноль контактов» и «мы не смогли проверить» — разные утверждения, и второе нельзя
        // показывать как первое: пользователь прочитает его как «своих не тронет» (ТЗ §1.1).
        val preview = runBlocking {
            journal.previewMatches("NUMBER", "PREFIX", "7495", contacts = FakeContacts(null))
        }
        assertNull(preview.contactsCovered, "без READ_CONTACTS показателя нет")
    }

    @Test
    fun `по умолчанию число контактов неизвестно, а не равно нулю`() {
        // Вызывающий, который источник не передал, не должен получить обнадёживающий ноль.
        assertNull(
            runBlocking { journal.previewMatches("NUMBER", "PREFIX", "7495") }.contactsCovered,
        )
    }

    @Test
    fun `правило по названию не выдаёт число контактов за ноль`() {
        // Правило по подписи с цифрами не сопоставляется, но контакт зацепить может — если
        // оператор пришлёт подходящую подпись. Название контакта в решении не участвует,
        // поэтому проверить нечем, и единственный честный ответ — «не знаю».
        val preview = runBlocking {
            journal.previewMatches(
                "NAME_ORG",
                "CONTAINS",
                "romashka",
                contacts = FakeContacts(listOf("+74951234567")),
            )
        }
        assertNull(preview.contactsCovered)
    }

    @Test
    fun `правило «номер в телефонной книге» показывает всю книгу`() {
        // Самый опасный случай: блокирующее правило по принадлежности книге отрезает всех
        // своих сразу, а по журналу это не видно вообще — принадлежность в событии не хранится.
        val preview = runBlocking {
            journal.previewMatches(
                "CONTACT",
                "IN_CONTACTS",
                "",
                contacts = FakeContacts(listOf("+74951234567", "+79991234567")),
            )
        }
        assertEquals(2, preview.contactsCovered, "под такое правило попадает вся книга")
        assertEquals(0, preview.count, "по журналу такое правило посчитать нечем")
        assertNull(preview.allowRulesCovered, "пересечение с шаблонами номеров не вычисляется")
    }

    @Test
    fun `усечённая книга помечается как нижняя граница`() {
        // Иначе «≥ 1» показывалось бы как «ровно 1», и пользователь принял бы решение
        // по числу, которое к книге не относится.
        val contacts = FakeContacts(listOf("+74951234567", "+74957654321"))
        val preview = runBlocking {
            journal.previewMatches(
                "NUMBER",
                "PREFIX",
                "7495",
                contacts = contacts,
                contactLimit = 1,
            )
        }
        assertEquals(1, preview.contactsCovered)
        assertTrue(preview.contactsTruncated, "предел достигнут — показатель неполный")
    }

    // --- перекрытые разрешающие правила -------------------------------------------------------

    @Test
    fun `правило перекрывает существующее разрешающее правило`() {
        runBlocking {
            rule(pattern = "+74951234567", canonical = "74951234567")
            // Не считается: разрешающее правило про другой код.
            rule(pattern = "+79991112233", canonical = "79991112233")
            // Не считается: правило блокирующее — перекрывать своих оно не может.
            rule(pattern = "+74957654321", canonical = "74957654321", action = "REJECT")
            // Не считается: выключенное правило никого не пропускает, и тревога о нём ложная.
            rule(pattern = "+74950001122", canonical = "74950001122", enabled = false)
            // Не считается: другой алфавит. Свёрнутое название и цифры совпасть могут только
            // случайно, и такое совпадение — не перекрытие.
            rule(pattern = "romashka", canonical = "7495", target = "NAME_ORG")
        }

        val preview = runBlocking { journal.previewMatches("NUMBER", "PREFIX", "7495") }
        assertEquals(1, preview.allowRulesCovered, "перекрыто ровно одно разрешающее правило")
    }

    @Test
    fun `перекрытие считается и по вариантам написания разрешающего правила`() {
        // Правило, ловящее вариант транслитерации, иначе выглядело бы безобидным: канонический
        // шаблон разрешающего правила — только одно из написаний.
        runBlocking {
            rule(
                pattern = "Poleznyy",
                canonical = "poleznyy",
                target = "NAME_ORG",
                variants = "poleznyy\npolezniy",
            )
        }
        assertEquals(
            1,
            runBlocking {
                journal.previewMatches("NAME", "CONTAINS", "polezniy")
            }.allowRulesCovered,
        )
    }

    @Test
    fun `regex-правило не притворяется, что не перекрывает ничего`() {
        // Ноль здесь читался бы как «своих не тронет», а пересечение регулярного выражения
        // с шаблоном разрешающего правила мы не вычисляем.
        runBlocking { rule(pattern = "+74951234567", canonical = "74951234567") }
        val preview = runBlocking {
            journal.previewMatches("NUMBER", "REGEX", "^7495.*$")
        }
        assertNull(preview.allowRulesCovered)
    }

    @Test
    fun `разрешающее regex-правило пропускается, а не сравнивается с текстом выражения`() {
        // Применить «начинается с 7495» к тексту регулярного выражения — значит сравнивать
        // себя с исходным кодом правила, а не с множеством номеров.
        runBlocking {
            rule(pattern = "^7495\\d+$", canonical = "^7495\\d+$", matchType = "REGEX")
        }
        assertEquals(
            0,
            runBlocking { journal.previewMatches("NUMBER", "PREFIX", "7495") }.allowRulesCovered,
        )
    }

    private suspend fun mirror(
        systemId: Long,
        at: Long,
        digits: String = "74951234567",
        name: String? = null,
        type: String = "INCOMING",
    ) = db.mirror().upsert(
        systemId = systemId,
        startedAt = at,
        rawNumber = "+$digits",
        digits = digits,
        e164 = "+$digits",
        name = name,
        nameFold = name?.let { NameCanonizer.canonize(it).whole.fold },
        type = type,
        durationSeconds = 0,
        phoneAccountId = null,
        syncedAt = at,
        canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
    )

    // --- предпросмотр по названию считает и зеркало --------------------------------------------

    @Test
    fun `правило по названию считает подписи из зеркала`() {
        // На реальном телефоне подписи организаций живут почти целиком в зеркале: в момент
        // проверки система их не отдаёт. Предпросмотр, считающий только свои события, говорил
        // «таких звонков нет» про звонок, название которого видно в журнале на том же экране.
        runBlocking {
            mirror(1, NOW - 1000, digits = "79520560329", name = "POChTA Ros.: dostavka")
            mirror(2, NOW - 2000, digits = "79919864925", name = "Agent Rostelecom IT Link Sol")
            mirror(3, NOW - 3000, digits = "79001112233", name = "OOO Magnum: dostavka")
        }

        val preview = runBlocking {
            journal.previewMatches("NAME_CATEGORY", "TOKEN", "dostavka")
        }
        assertEquals(2, preview.count, "две записи с категорией «dostavka» — обе из зеркала")
    }

    @Test
    fun `русский шаблон находит подпись в транслите из зеркала`() {
        runBlocking { mirror(1, NOW - 1000, name = "OOO Romashka: reklama") }

        val preview = runBlocking {
            journal.previewMatches("NAME_CATEGORY", "TOKEN", "reklama")
        }
        assertEquals(1, preview.count)
    }

    @Test
    fun `сшитая запись зеркала не считается дважды`() {
        runBlocking {
            val eventId = db.events().insert(
                ScreeningEventEntity(
                    occurredAt = NOW - 1000,
                    rawNumber = "+74951234567",
                    digits = "74951234567",
                    e164 = "+74951234567",
                    presentation = "ALLOWED",
                    nameRaw = "OOO Romashka: reklama",
                    nameFold = "ooromashkareklama",
                    nameTokens = " ooo romashka reklama ",
                    orgFold = "ooromashka",
                    categoryFold = "reklama",
                    nameSource = "CNAP",
                    action = "ALLOW",
                    reason = "DEFAULT_ACTION",
                    latencyMs = 10,
                    budgetMs = 1500,
                    canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
                )
            )
            mirror(1, NOW - 1000, name = "OOO Romashka: reklama")
            db.events().attachSystemId(eventId, 1)
        }

        val preview = runBlocking {
            journal.previewMatches("NAME_CATEGORY", "TOKEN", "reklama")
        }
        assertEquals(1, preview.count, "звонок один, а записей о нём две")
    }

    @Test
    fun `исходящие и скрытые записи зеркала в подсчёт по названию не идут`() {
        runBlocking {
            mirror(1, NOW - 1000, name = "OOO Romashka: reklama", type = "OUTGOING")
            mirror(2, NOW - 2000, name = "OOO Romashka: reklama", type = "VOICEMAIL")
        }

        val preview = runBlocking {
            journal.previewMatches("NAME_CATEGORY", "TOKEN", "reklama")
        }
        assertEquals(0, preview.count, "правило к таким записям не применяется")
    }

    // --- регресс: подсчёт по журналу ----------------------------------------------------------

    @Test
    fun `подсчёт по журналу не изменился от появления новых показателей`() {
        // Новые величины считаются рядом, а не вместо: смешать их в одно число значило бы
        // сложить «столько звонков было» с «столько своих зацепим».
        runBlocking {
            event(at = NOW - 1000, digits = "74951234567")
            event(at = NOW - 2000, digits = "74957654321")
            event(at = NOW - 3000, digits = "79991112233")
            rule(pattern = "+74951234567", canonical = "74951234567")
        }

        val preview = runBlocking {
            journal.previewMatches(
                "NUMBER",
                "PREFIX",
                "7495",
                contacts = FakeContacts(listOf("+74951234567")),
            )
        }
        assertEquals(2, preview.count, "по журналу — две записи, как и раньше")
        assertTrue(!preview.truncated)
        assertEquals(1, preview.allowRulesCovered)
        assertEquals(1, preview.contactsCovered)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
