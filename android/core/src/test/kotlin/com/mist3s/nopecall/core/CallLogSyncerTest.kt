package com.mist3s.nopecall.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.calllog.CallLogCursor
import com.mist3s.nopecall.core.calllog.CallLogRow
import com.mist3s.nopecall.core.calllog.CallLogSource
import com.mist3s.nopecall.core.calllog.CallLogSyncer
import com.mist3s.nopecall.core.calllog.CallType
import com.mist3s.nopecall.core.facts.ContactMembership
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.ScreeningEventEntity
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Зеркало системного журнала и сшивка (ТЗ §7.2, §7.3).
 *
 * Самая тонкая часть журнала: здесь легко получить дубли, потерянные записи и воскресшие
 * скрытые. Каждый тест ниже соответствует конкретному дефекту, найденному ревью архитектуры.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallLogSyncerTest {

    private lateinit var db: NopeCallDatabase
    private val normalizer = RuFastPathNormalizer()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            NopeCallDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** Источник, который можно менять между проходами — как это делает система. */
    private class FakeSource(
        var rows: List<CallLogRow> = emptyList(),
        var available: Boolean = true,
    ) : CallLogSource {
        var queries = 0
            private set

        override fun query(sinceMillis: Long, after: CallLogCursor?, limit: Int): List<CallLogRow> {
            queries++
            // Порядок и курсор — как в `AndroidCallLogSource`: по паре «время, идентификатор».
            // Подделка, которая сравнивает иначе, проверяла бы не тот обход, что на устройстве.
            return rows
                .filter { it.dateMillis >= sinceMillis }
                .filter {
                    after == null ||
                        it.dateMillis > after.dateMillis ||
                        (it.dateMillis == after.dateMillis && it.systemId > after.systemId)
                }
                .sortedWith(compareBy({ it.dateMillis }, { it.systemId }))
                .take(limit)
        }

        override fun isAvailable(): Boolean = available
    }

    private fun syncer(
        source: CallLogSource,
        contacts: ContactMembership = ContactMembership.UNKNOWN,
    ) = CallLogSyncer(db, source, normalizer, now = { NOW }, contacts = contacts)

    private fun row(
        id: Long,
        date: Long = NOW - 60_000,
        number: String? = "+74951234567",
        name: String? = null,
        type: Int = 6,
        duration: Int = 0,
    ) = CallLogRow(id, date, number, name, type, duration, phoneAccountId = null)

    private fun event(
        occurredAt: Long,
        digits: String = "74951234567",
        nameRaw: String? = null,
    ) = ScreeningEventEntity(
        occurredAt = occurredAt,
        rawNumber = "+74951234567",
        digits = digits,
        e164 = if (digits.isEmpty()) null else "+$digits",
        presentation = if (digits.isEmpty()) "RESTRICTED" else "ALLOWED",
        nameRaw = nameRaw,
        nameSource = if (nameRaw == null) "NONE" else "CNAP",
        action = "REJECT",
        reason = "RULE_MATCH",
        latencyMs = 10,
        budgetMs = 1500,
        canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
    )

    // --- синхронизация ------------------------------------------------------------------------

    @Test
    fun `без разрешения синхронизация не выполняется и это видно`() {
        val result = runBlocking { syncer(FakeSource(available = false)).sync() }
        assertFalse(result.available)
        assertEquals(0, runBlocking { db.mirror().count() })
    }

    @Test
    fun `записи попадают в зеркало в канонической форме`() {
        // Каноническая, а не «как пришло»: системный журнал может отдать 8…, а Call.Details
        // тот же номер как +7…, и сшивка по «как пришло» их не нашла бы.
        val source = FakeSource(listOf(row(1, number = "8 495 123-45-67")))
        runBlocking { syncer(source).sync() }

        val saved = runBlocking { db.mirror().bySystemId(1) }
        assertNotNull(saved)
        assertEquals("74951234567", saved!!.digits)
        assertEquals("+74951234567", saved.e164)
        assertEquals("8 495 123-45-67", saved.rawNumber, "как пришло — тоже сохраняется")
        assertEquals(CallType.BLOCKED, saved.type)
    }

    @Test
    fun `сшивка находит запись, даже если форматы номера разные`() {
        // Событие пришло из Call.Details как +7…, системный журнал отдал 8…. Без канонизации
        // обеих сторон эти записи никогда бы не сшились.
        runBlocking { db.events().insert(event(occurredAt = NOW - 60_000, digits = "74951234567")) }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000, number = "8 (495) 123-45-67")))

        assertEquals(1, runBlocking { syncer(source).sync() }.stitched)
    }

    @Test
    fun `повторная синхронизация обновляет запись, а не игнорирует её`() {
        // Ключевой дефект, найденный ревью: при INSERT OR IGNORE длительность и позднее имя
        // не появились бы никогда, а перекрытие окна в сутки было бы бессмысленным (ТЗ §7.2).
        val source = FakeSource(listOf(row(1, type = 1, duration = 0, name = null)))
        runBlocking { syncer(source).sync() }
        assertEquals(0, runBlocking { db.mirror().bySystemId(1) }!!.durationSeconds)

        // Система дописала запись после звонка: появились длительность и имя.
        source.rows = listOf(row(1, type = 1, duration = 42, name = "PAO SOVKOMBANK"))
        runBlocking { syncer(source).sync() }

        val saved = runBlocking { db.mirror().bySystemId(1) }!!
        assertEquals(42, saved.durationSeconds)
        assertEquals("PAO SOVKOMBANK", saved.name)
        assertEquals("paosovkombank", saved.nameFold)
        assertEquals(1, runBlocking { db.mirror().count() }, "дубля быть не должно")
    }

    @Test
    fun `скрытая локально запись не воскресает при повторной синхронизации`() {
        // Критерий приёмки ТЗ §18 п. 18.
        val source = FakeSource(listOf(row(1)))
        runBlocking { syncer(source).sync() }
        runBlocking { db.mirror().hide(1) }
        assertTrue(runBlocking { db.mirror().bySystemId(1) }!!.hiddenLocally)

        runBlocking { syncer(source).sync() }
        assertTrue(
            runBlocking { db.mirror().bySystemId(1) }!!.hiddenLocally,
            "hiddenLocally обязан сохраниться: скрытая запись не должна возвращаться",
        )
    }

    @Test
    fun `известное имя не затирается пустым при повторной синхронизации`() {
        val source = FakeSource(listOf(row(1, name = "PAO SOVKOMBANK")))
        runBlocking { syncer(source).sync() }

        source.rows = listOf(row(1, name = null))
        runBlocking { syncer(source).sync() }
        assertEquals("PAO SOVKOMBANK", runBlocking { db.mirror().bySystemId(1) }!!.name)
    }

    // --- сшивка -------------------------------------------------------------------------------

    @Test
    fun `событие сшивается с ближайшей записью зеркала`() {
        val eventId = runBlocking { db.events().insert(event(occurredAt = NOW - 60_000)) }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000 + 3_000)))

        val result = runBlocking { syncer(source).sync() }
        assertEquals(1, result.stitched)
        assertEquals(1L, runBlocking { db.events().recent(10) }.single { it.id == eventId }.matchedSystemId)
    }

    @Test
    fun `запись вне окна не сшивается`() {
        runBlocking { db.events().insert(event(occurredAt = NOW - 60_000)) }
        // Разница больше 20 секунд — это другой звонок.
        val source = FakeSource(listOf(row(1, date = NOW - 60_000 + 40_000)))

        assertEquals(0, runBlocking { syncer(source).sync() }.stitched)
        assertNull(runBlocking { db.events().recent(10) }.single().matchedSystemId)
    }

    @Test
    fun `событие со скрытым номером не сшивается ни с чем`() {
        // Дефект Су5 из ревью: у скрытого номера digits пуст, и без явного условия такое
        // событие сшилось бы с произвольной записью зеркала с таким же пустым номером.
        runBlocking { db.events().insert(event(occurredAt = NOW - 60_000, digits = "")) }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000, number = "")))

        assertEquals(0, runBlocking { syncer(source).sync() }.stitched)
        assertNull(runBlocking { db.events().recent(10) }.single().matchedSystemId)
    }

    @Test
    fun `одна запись зеркала не достаётся двум событиям`() {
        // Второй дефект Су5: два звонка с одного номера в пределах окна сшились бы с одной
        // записью, а исключение дублей затем скрыло бы одно из них — запись пропала бы.
        runBlocking {
            db.events().insert(event(occurredAt = NOW - 60_000))
            db.events().insert(event(occurredAt = NOW - 60_000 + 5_000))
        }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000 + 2_000)))

        val result = runBlocking { syncer(source).sync() }
        assertEquals(1, result.stitched, "сшиться должно ровно одно событие")

        val stitched = runBlocking { db.events().recent(10) }.count { it.matchedSystemId != null }
        assertEquals(1, stitched)
    }

    @Test
    fun `позднее имя дописывается в событие и считается`() {
        // Позднее название: в момент проверки его не было, значит правила по названию на этом
        // звонке не работали. Источник при этом не установлен — см. тесты ниже.
        runBlocking { db.events().insert(event(occurredAt = NOW - 60_000, nameRaw = null)) }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000, name = "OOO Romashka")))

        val result = runBlocking { syncer(source).sync() }
        assertEquals(1, result.lateNames)

        val saved = runBlocking { db.events().recent(10) }.single()
        assertEquals("OOO Romashka", saved.nameRaw)
        assertEquals("SYSTEM_LOG", saved.nameSource)
    }

    @Test
    fun `записи с одинаковым временем не теряются на границе страницы`() {
        // В системном журнале реального телефона нашлись две пары строк с равным DATE.
        // При строгом `DATE >` та из них, что оказалась последней на странице, выпадала
        // молча: в зеркале записи просто нет, и заметить это по интерфейсу нельзя.
        val source = FakeSource(
            listOf(
                row(1, date = NOW - 5_000, number = "+74951111111"),
                row(2, date = NOW - 4_000, number = "+74952222222"),
                row(3, date = NOW - 4_000, number = "+74953333333"), // то же время, что у 2
                row(4, date = NOW - 3_000, number = "+74954444444"),
            )
        )

        val result = runBlocking { syncer(source).sync(pageSize = 2) }

        assertEquals(4, result.fetched, "все четыре записи обязаны попасть в зеркало")
        assertEquals(4, runBlocking { db.mirror().count() })
    }

    @Test
    fun `страница целиком из одинакового времени не зацикливает обход`() {
        // Если бы курсор был одним временем, он бы здесь не сдвинулся, и обход упёрся бы
        // в предел страниц: сорок запросов к провайдеру на каждой синхронизации. Пара
        // «время, идентификатор» двигается всегда.
        val source = FakeSource(
            listOf(
                row(1, date = NOW - 4_000, number = "+74951111111"),
                row(2, date = NOW - 4_000, number = "+74952222222"),
            )
        )

        val result = runBlocking { syncer(source).sync(pageSize = 2, maxPages = 40) }

        assertEquals(2, result.fetched)
        // Два запроса, а не сорок: первый отдал полную страницу, второй — пустую. Меньше
        // нельзя: полная страница не отличима от «данные кончились ровно на границе».
        assertEquals(2, source.queries)
    }

    @Test
    fun `позднее имя из телефонной книги подписью не считается`() {
        runBlocking { db.events().insert(event(occurredAt = NOW - 60_000)) }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000, name = "Мама")))

        runBlocking { syncer(source, contacts = ContactMembership { true }).sync() }

        val saved = runBlocking { db.events().recent(10) }.single()
        assertEquals("Мама", saved.nameRaw)
        assertEquals(CallLogSyncer.SOURCE_CONTACTS, saved.nameSource)
        assertEquals(true, saved.nameLate)
        assertEquals(0, runBlocking { db.events().lateSignaturesSince(0) })
    }

    @Test
    fun `отсутствие номера в книге не делает позднее имя подписью оператора`() {
        // Данные с реального телефона: у номера вне книги CACHED_NAME заполнен у части
        // звонков, а звонилка показывает одно название у всех — значит столбец об источнике
        // не свидетельствует. Вывод «не в книге, значит от оператора» дал бы ложную подпись —
        // ровно тот дефект, но в обратную сторону.
        runBlocking { db.events().insert(event(occurredAt = NOW - 60_000)) }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000, name = "OOO Romashka")))

        runBlocking { syncer(source, contacts = ContactMembership { false }).sync() }

        val saved = runBlocking { db.events().recent(10) }.single()
        assertEquals(CallLogSyncer.SOURCE_UNKNOWN, saved.nameSource)
        assertEquals(true, saved.nameLate)
        assertEquals(0, runBlocking { db.events().lateSignaturesSince(0) })
        assertEquals(1, runBlocking { db.events().lateNamesSince(0) })
    }

    @Test
    fun `позднее название не идёт в счёт известных в момент решения`() {
        runBlocking { db.events().insert(event(occurredAt = NOW - 60_000)) }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000, name = "Мама")))

        runBlocking { syncer(source, contacts = ContactMembership { true }).sync() }

        assertEquals(0, runBlocking { db.events().namesAtDecisionSince(0) })
    }

    @Test
    fun `своя подпись не затирается системным именем`() {
        // Операторская подпись, полученная в момент проверки, ценнее системного имени:
        // она и есть предмет исследования.
        runBlocking {
            db.events().insert(event(occurredAt = NOW - 60_000, nameRaw = "OOO Romashka: reklama"))
        }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000, name = "Другое имя")))

        val result = runBlocking { syncer(source).sync() }
        assertEquals(0, result.lateNames)

        val saved = runBlocking { db.events().recent(10) }.single()
        assertEquals("OOO Romashka: reklama", saved.nameRaw)
        assertEquals("CNAP", saved.nameSource)
    }

    @Test
    fun `повторная синхронизация не сшивает уже сшитое заново`() {
        runBlocking { db.events().insert(event(occurredAt = NOW - 60_000)) }
        val source = FakeSource(listOf(row(1, date = NOW - 60_000)))

        assertEquals(1, runBlocking { syncer(source).sync() }.stitched)
        assertEquals(0, runBlocking { syncer(source).sync() }.stitched)
    }

    // --- типы записей -------------------------------------------------------------------------

    @Test
    fun `типы системного журнала разбираются, неизвестный остаётся отличимым`() {
        assertEquals(CallType.INCOMING, CallType.fromSystem(1))
        assertEquals(CallType.OUTGOING, CallType.fromSystem(2))
        assertEquals(CallType.MISSED, CallType.fromSystem(3))
        assertEquals(CallType.REJECTED, CallType.fromSystem(5))
        assertEquals(CallType.BLOCKED, CallType.fromSystem(6))
        // Константы CallLog могут пополняться: неизвестное значение не должно сливаться
        // с известными, иначе фильтры журнала начнут врать.
        assertEquals(CallType.UNKNOWN, CallType.fromSystem(99))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
