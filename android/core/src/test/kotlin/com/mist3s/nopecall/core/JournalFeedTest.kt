package com.mist3s.nopecall.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.storage.CallLogMirrorEntity
import com.mist3s.nopecall.core.storage.JournalCursor
import com.mist3s.nopecall.core.storage.JournalFilter
import com.mist3s.nopecall.core.storage.JournalKind
import com.mist3s.nopecall.core.storage.JournalRepository
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.ScreeningEventEntity
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
 * Объединённая поверхность журнала: два слоя, фильтры, пагинация (ТЗ §7.3, §7.4, §7.5).
 *
 * Место, где легче всего получить дубли и потерянные записи: одна и та же запись существует
 * и как наше событие, и как строка системного журнала, а идентификаторы двух таблиц
 * независимы и совпадают численно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalFeedTest {

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

    private suspend fun event(
        at: Long,
        digits: String = "74951234567",
        action: String = "REJECT",
        nameRaw: String? = null,
        nameSource: String = "NONE",
        nameFold: String? = null,
        matchedSystemId: Long? = null,
        ruleId: Long? = 1L,
        sim: String? = null,
    ): Long = db.events().insert(
        ScreeningEventEntity(
            occurredAt = at,
            rawNumber = if (digits.isEmpty()) "" else "+$digits",
            digits = digits,
            e164 = if (digits.isEmpty()) null else "+$digits",
            presentation = if (digits.isEmpty()) "RESTRICTED" else "ALLOWED",
            nameRaw = nameRaw,
            nameFold = nameFold,
            nameSource = nameSource,
            action = action,
            reason = if (action == "ALLOW") "DEFAULT_ACTION" else "RULE_MATCH",
            matchedRuleId = if (action == "ALLOW") null else ruleId,
            matchedRuleTitle = if (action == "ALLOW") null else "Правило",
            matchedSystemId = matchedSystemId,
            latencyMs = 12,
            budgetMs = 1500,
            phoneAccountId = sim,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )
    )

    private suspend fun mirror(
        systemId: Long,
        at: Long,
        digits: String = "74951234567",
        type: String = "INCOMING",
        duration: Int = 42,
        name: String? = null,
        nameFold: String? = null,
        hidden: Boolean = false,
        sim: String? = null,
    ) {
        db.mirror().upsert(
            systemId = systemId,
            startedAt = at,
            rawNumber = "+$digits",
            digits = digits,
            e164 = "+$digits",
            name = name,
            nameFold = nameFold,
            type = type,
            durationSeconds = duration,
            phoneAccountId = sim,
            syncedAt = NOW,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )
        if (hidden) db.mirror().hide(systemId)
    }

    // --- объединение --------------------------------------------------------------------------

    @Test
    fun `сшитый звонок показывается один раз, с решением и с длительностью`() {
        // Главный дефект, который здесь возможен: одна и та же запись как наше событие
        // и как строка системного журнала — то есть дубль в списке (ТЗ §7.3).
        runBlocking {
            mirror(systemId = 10, at = NOW - 1000, type = "BLOCKED", duration = 0)
            event(at = NOW - 1000, matchedSystemId = 10)
        }

        val items = runBlocking { journal.page().items }
        assertEquals(1, items.size, "сшитая запись обязана быть одной")
        assertEquals(JournalKind.BLOCKED_BY_APP, items[0].kind)
        assertEquals("REJECT", items[0].action)
        assertEquals(10L, items[0].systemId)
        assertTrue(items[0].eventId != null, "событие тоже должно быть доступно из записи")
    }

    @Test
    fun `наше событие без записи зеркала не теряется`() {
        // Заблокированный звонок, который система по каким-то причинам не записала.
        runBlocking { event(at = NOW - 2000) }

        val items = runBlocking { journal.page().items }
        assertEquals(1, items.size)
        assertEquals(JournalKind.BLOCKED_BY_APP, items[0].kind)
        assertNull(items[0].systemId)
        assertNull(items[0].durationSeconds, "длительность неизвестна, а не ноль")
    }

    @Test
    fun `запись зеркала без нашего события не притворяется нашей проверкой`() {
        // Заблокировал встроенный блокировщик прошивки. Приписать это себе нельзя: иначе
        // на жалобу «почему заблокировали» ответить нечем (ТЗ §7.4).
        runBlocking { mirror(systemId = 11, at = NOW - 3000, type = "BLOCKED") }

        val items = runBlocking { journal.page().items }
        assertEquals(1, items.size)
        assertEquals(JournalKind.BLOCKED_EXTERNAL, items[0].kind)
        assertNull(items[0].action)
        assertNull(items[0].reason)
    }

    @Test
    fun `скрытая запись не показывается`() {
        runBlocking {
            mirror(systemId = 12, at = NOW - 4000, hidden = true)
            mirror(systemId = 13, at = NOW - 5000)
        }
        val items = runBlocking { journal.page().items }
        assertEquals(listOf(13L), items.map { it.systemId })
    }

    @Test
    fun `исходящий звонок виден только из зеркала`() {
        runBlocking { mirror(systemId = 14, at = NOW - 6000, type = "OUTGOING") }
        assertEquals(
            JournalKind.OUTGOING,
            runBlocking { journal.page().items }.single().kind,
        )
    }

    @Test
    fun `позднее имя из зеркала попадает в список`() {
        runBlocking {
            mirror(systemId = 15, at = NOW - 7000, name = "PAO SOVKOMBANK", nameFold = "paosovkombank")
        }
        val item = runBlocking { journal.page().items }.single()
        assertEquals("PAO SOVKOMBANK", item.nameRaw)
        assertEquals("SYSTEM_LOG", item.nameSource)
        assertTrue(!item.hadSignature, "имя из системного журнала — не подпись в момент проверки")
    }

    // --- пагинация ---------------------------------------------------------------------------

    @Test
    fun `страницы не пропускают и не дублируют записи при совпадающем времени`() {
        // Пакетная вставка зеркала даёт одинаковые метки времени у соседних записей, а `id`
        // уникален только внутри своей таблицы. Курсор по времени или по времени и `id`
        // здесь терял бы строки на границе страницы.
        runBlocking {
            repeat(10) { i -> mirror(systemId = 100L + i, at = NOW, digits = "7495000000$i") }
            repeat(10) { event(at = NOW, digits = "7999${it}000000") }
        }

        val seen = mutableListOf<Pair<Int, Long>>()
        var cursor: JournalCursor? = null
        do {
            val page = runBlocking { journal.page(cursor = cursor, limit = 3) }
            seen += page.items.map { it.sourceRank to it.id }
            cursor = page.next
        } while (cursor != null)

        assertEquals(20, seen.size, "должны прийти все записи")
        assertEquals(20, seen.distinct().size, "и ни одна дважды")
    }

    // --- фильтры (ТЗ §7.5) --------------------------------------------------------------------

    @Test
    fun `фильтр «мы заблокировали» отделяет наши блокировки от системных`() {
        runBlocking {
            mirror(systemId = 20, at = NOW - 1000, type = "BLOCKED")
            event(at = NOW - 2000)
            mirror(systemId = 21, at = NOW - 3000, type = "INCOMING")
        }

        val ours = runBlocking {
            journal.page(filter = JournalFilter(kind = JournalFilter.KIND_BLOCKED_BY_US)).items
        }
        assertEquals(1, ours.size)
        assertEquals(JournalKind.BLOCKED_BY_APP, ours.single().kind)

        val any = runBlocking {
            journal.page(filter = JournalFilter(kind = JournalFilter.KIND_BLOCKED_ANY)).items
        }
        assertEquals(2, any.size, "системная блокировка тоже блокировка")
    }

    @Test
    fun `фильтр «входящие» работает и без доступа к системному журналу`() {
        // Без READ_CALL_LOG зеркало пусто, но звонок всё равно был входящим: иначе фильтр
        // не показывал бы ничего, и это выглядело бы как поломка.
        runBlocking { event(at = NOW - 1000, action = "ALLOW") }
        assertEquals(
            1,
            runBlocking {
                journal.page(filter = JournalFilter(kind = JournalFilter.KIND_INCOMING)).items.size
            },
        )
    }

    @Test
    fun `фильтр по номеру ищет по канонической форме`() {
        runBlocking {
            event(at = NOW - 1000, digits = "74951234567")
            event(at = NOW - 2000, digits = "79991112233")
        }
        val found = runBlocking {
            journal.page(filter = JournalFilter(digitsQuery = "4951234")).items
        }
        assertEquals(1, found.size)
        assertEquals("+74951234567", found.single().e164)
    }

    @Test
    fun `поиск по названию находит русский запрос в транслите`() {
        // Пользователь ищет «ромашка», а в подписи придёт `OOO Romashka`. Без канонизации
        // запроса поиск не нашёл бы ничего — и это первое, что попробует пользователь.
        runBlocking {
            event(
                at = NOW - 1000,
                nameRaw = "OOO Romashka: reklama",
                nameFold = "ooo romashka reklama",
                nameSource = "CNAP",
            )
        }
        assertEquals(
            1,
            runBlocking { journal.page(filter = JournalFilter(nameQuery = "ромашка")).items.size },
        )
    }

    @Test
    fun `фильтр по наличию операторской подписи разделяет записи`() {
        runBlocking {
            event(at = NOW - 1000, nameRaw = "Yandex: IT", nameSource = "CNAP")
            event(at = NOW - 2000, digits = "79990000000")
        }
        assertEquals(
            1,
            runBlocking { journal.page(filter = JournalFilter(hadSignature = true)).items.size },
        )
        assertEquals(
            1,
            runBlocking { journal.page(filter = JournalFilter(hadSignature = false)).items.size },
        )
    }

    @Test
    fun `фильтры по периоду, правилу и SIM применяются`() {
        runBlocking {
            event(at = NOW - 1000, ruleId = 7, sim = "sim1")
            event(at = NOW - 10 * DAY, digits = "79991112233", ruleId = 8, sim = "sim2")
        }

        assertEquals(
            1,
            runBlocking { journal.page(filter = JournalFilter(fromAt = NOW - DAY)).items.size },
        )
        assertEquals(
            1,
            runBlocking { journal.page(filter = JournalFilter(ruleId = 8)).items.size },
        )
        assertEquals(
            1,
            runBlocking { journal.page(filter = JournalFilter(sim = "sim2")).items.size },
        )
        assertEquals(listOf("sim1", "sim2"), runBlocking { journal.sims() })
    }

    // --- предпросмотр правила (ТЗ §9.3) -------------------------------------------------------

    @Test
    fun `предпросмотр считает и записи зеркала, а не только наши проверки`() {
        // Иначе он говорил «в журнале нет подходящих записей», хотя они в журнале прямо видны:
        // расхождение показанного и посчитанного читается как ошибка подсчёта.
        runBlocking {
            event(at = NOW - 1000, digits = "74951234567")
            mirror(systemId = 50, at = NOW - 2000, digits = "74957654321")
            mirror(systemId = 51, at = NOW - 3000, digits = "74959998877", type = "BLOCKED")
            // Исходящий не считается: правило к нему не применяется никогда.
            mirror(systemId = 52, at = NOW - 4000, digits = "74951112233", type = "OUTGOING")
            // Скрытая запись тоже не считается: её в журнале нет.
            mirror(systemId = 53, at = NOW - 5000, digits = "74954445566", hidden = true)
        }

        val preview = runBlocking { journal.previewMatches("NUMBER", "PREFIX", "7495") }
        assertEquals(3, preview.count, "событие плюс две видимые входящие записи зеркала")
        assertTrue(!preview.truncated)
    }

    @Test
    fun `сшитая запись зеркала не удваивает предпросмотр`() {
        runBlocking {
            mirror(systemId = 60, at = NOW - 1000, digits = "74951234567")
            event(at = NOW - 1000, digits = "74951234567", matchedSystemId = 60)
        }
        assertEquals(1, runBlocking { journal.previewMatches("NUMBER", "EXACT", "74951234567") }.count)
    }

    @Test
    fun `предпросмотр по названию считает оба слоя журнала`() {
        // Раньше считались только свои проверки: считалось, что разбор названия в зеркале
        // недоступен. Он доступен — название канонизируется на месте тем же движком. А цена
        // старого решения оказалась высокой: подписи организаций живут почти целиком в зеркале,
        // потому что в момент проверки система их не отдаёт, и предпросмотр отвечал
        // «таких звонков нет» про звонок, видимый на том же экране.
        runBlocking {
            event(
                at = NOW - 1000,
                nameRaw = "OOO Romashka",
                nameFold = "ooromashka",
                nameSource = "CNAP",
            )
            mirror(systemId = 70, at = NOW - 2000, name = "OOO Romashka", nameFold = "ooromashka")
        }
        assertEquals(2, runBlocking { journal.previewMatches("NAME", "CONTAINS", "romashka") }.count)
    }

    // --- ретеншен и очистка (ТЗ §7.6) ---------------------------------------------------------

    @Test
    fun `ретеншен удаляет по сроку и по числу записей`() {
        runBlocking {
            event(at = NOW - 400 * DAY, digits = "79990000001")
            repeat(5) { event(at = NOW - it * 1000L, digits = "7495000000$it") }
            mirror(systemId = 30, at = NOW - 400 * DAY)
        }

        val removedByAge = runBlocking { journal.applyRetention(NOW, keepDays = 365, keepRecords = 0) }
        assertEquals(2, removedByAge, "старое событие и старая запись зеркала")

        val removedByCount = runBlocking {
            journal.applyRetention(NOW, keepDays = 0, keepRecords = 3)
        }
        assertEquals(2, removedByCount)
        assertEquals(3, runBlocking { db.events().count() })
    }

    @Test
    fun `очистка удаляет оба слоя`() {
        runBlocking {
            event(at = NOW - 1000)
            mirror(systemId = 40, at = NOW - 1000)
        }
        assertEquals(2, runBlocking { journal.clear() })
        assertTrue(runBlocking { journal.page().items }.isEmpty())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
