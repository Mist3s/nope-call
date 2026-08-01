package com.mist3s.nopecall.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.storage.EventRecorder
import com.mist3s.nopecall.core.storage.EventSpool
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.RuleEntity
import com.mist3s.nopecall.core.storage.ScreeningRecord
import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.CallFacts
import com.mist3s.nopecall.engine.Decision
import com.mist3s.nopecall.engine.DecisionReason
import com.mist3s.nopecall.engine.Degradation
import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.NameSource
import com.mist3s.nopecall.engine.NumberPresentation
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import com.mist3s.nopecall.engine.RuleSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Очередь событий и её перенос в Room (архитектура §4.6, §9.2).
 *
 * Очередь существует потому, что сервис проверки не имеет права трогать Room: до первой
 * разблокировки база недоступна, а после ответа системе процесс может умереть в любой момент.
 * Поэтому событие сначала дописывается одной строкой в файл, а в базу попадает позже.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventSpoolTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

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

    private fun record(
        number: String = "+74951234567",
        name: String? = null,
        action: CallAction = CallAction.REJECT,
        reason: DecisionReason = DecisionReason.RULE_MATCH,
        ruleId: Long? = 1L,
    ) = ScreeningRecord(
        occurredAt = 1_800_000_000_000L,
        facts = CallFacts(
            number = normalizer.normalize(number, "RU"),
            presentation = NumberPresentation.ALLOWED,
            name = NameCanonizer.canonize(name),
            nameSource = if (name == null) NameSource.NONE else NameSource.CNAP,
            inContacts = false,
            isEmergency = false,
        ),
        decision = Decision(action, reason, matchedRuleId = ruleId, elapsedNanos = 12_000_000),
        matchedRuleTitle = null,
        budgetMs = 1500,
    )

    @Test
    fun `событие проходит путь очередь - Room и сохраняет данные`() {
        val spool = EventSpool(temp.newFolder("spool1"))
        spool.append(record(name = "OOO Romashka: reklama"))

        val moved = runBlocking { EventRecorder(db).drain(spool) }
        assertEquals(1, moved)

        val saved = runBlocking { db.events().recent(10) }.single()
        assertEquals("74951234567", saved.digits)
        assertEquals("+74951234567", saved.e164)
        assertEquals("REJECT", saved.action)
        assertEquals("OOO Romashka: reklama", saved.nameRaw)
        // Ограничители по краям нужны, чтобы «содержит слово» работало через LIKE '% x %'.
        assertTrue(saved.nameTokens!!.startsWith(" ") && saved.nameTokens!!.endsWith(" "))
        assertEquals(12, saved.latencyMs)
        assertEquals(RuleSnapshot.CURRENT_CANON_VERSION, saved.canonVersion)
    }

    @Test
    fun `название правила подставляется при переносе, а не в горячем пути`() {
        // В момент звонка Room недоступен, движок возвращает только идентификатор. Без этой
        // подстановки журнал не выполнял бы критерий приёмки ТЗ §18 п. 10.
        val ruleId = runBlocking {
            db.rules().insert(
                RuleEntity(
                    title = "Москва", targetType = "NUMBER", matchType = "PREFIX",
                    pattern = "8495", patternCanonical = "7495", action = "REJECT",
                    orderIndex = 600, createdAt = 0, updatedAt = 0,
                    canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
                )
            )
        }
        val spool = EventSpool(temp.newFolder("spool2"))
        spool.append(record(ruleId = ruleId))

        runBlocking { EventRecorder(db).drain(spool) }

        val saved = runBlocking { db.events().recent(10) }.single()
        assertEquals("Москва", saved.matchedRuleTitle)
        // И счётчик срабатываний обновляется тем же переносом.
        assertEquals(1, runBlocking { db.rules().byId(ruleId) }!!.matchCount.toInt())
    }

    @Test
    fun `счётчик не растёт, если решение приняло не правило`() {
        // Ответил сторож — правило не срабатывало, инкрементировать нельзя (архитектура §6.7).
        val ruleId = runBlocking {
            db.rules().insert(
                RuleEntity(
                    title = "Москва", targetType = "NUMBER", matchType = "PREFIX",
                    pattern = "8495", patternCanonical = "7495", action = "REJECT",
                    orderIndex = 600, createdAt = 0, updatedAt = 0,
                    canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
                )
            )
        }
        val spool = EventSpool(temp.newFolder("spool3"))
        spool.append(
            record(action = CallAction.ALLOW, reason = DecisionReason.WATCHDOG_ANSWERED, ruleId = ruleId)
        )

        runBlocking { EventRecorder(db).drain(spool) }
        assertEquals(0, runBlocking { db.rules().byId(ruleId) }!!.matchCount.toInt())
    }

    @Test
    fun `перенос идемпотентен - повторный слив не дублирует`() {
        val spool = EventSpool(temp.newFolder("spool4"))
        spool.append(record())
        spool.append(record())

        val recorder = EventRecorder(db)
        assertEquals(2, runBlocking { recorder.drain(spool) })
        // Очередь очищена: второй слив ничего не находит.
        assertEquals(0, runBlocking { recorder.drain(spool) })
        assertEquals(2, runBlocking { db.events().count() })
    }

    @Test
    fun `событие, пришедшее во время слива, не теряется`() {
        // Слив открывает Room, а это десятки-сотни миллисекунд: за это время может прийти
        // звонок. Раньше `clear()` удалял файл целиком, и такая запись исчезала — при том,
        // что синхронная запись после ответа сделана ровно ради того, чтобы не потеряться.
        val dir = temp.newFolder("spool-race")
        val spool = EventSpool(dir)
        spool.append(record())

        val taken = spool.drain()
        assertEquals(1, taken.size)

        // Звонок во время слива.
        spool.append(record())
        spool.clear()

        assertEquals(1, spool.drain().size, "запись, пришедшая во время слива, обязана остаться")
    }

    @Test
    fun `прерванный слив повторяется, а не теряется`() {
        val dir = temp.newFolder("spool-crash")
        val spool = EventSpool(dir)
        spool.append(record())

        assertEquals(1, spool.drain().size)
        // clear() не вызван: процесс умер между сливом и подтверждением.
        val afterRestart = EventSpool(dir)
        assertEquals(1, afterRestart.drain().size, "тот же файл обязан прочитаться заново")
    }

    @Test
    fun `по достижении предела новые записи отбрасываются со счётчиком`() {
        // Без предела спул растёт неограниченно, если Room долго недоступен (§9.2).
        val dir = temp.newFolder("spool-limit")
        val spool = EventSpool(dir)
        val file = java.io.File(dir, EventSpool.FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText("x".repeat((EventSpool.MAX_BYTES + 1).toInt()))

        spool.append(record())

        assertEquals(1, spool.droppedCount(), "отброшенное обязано быть видно счётчиком")
        assertEquals(0, spool.drain().count { it.startsWith("{") }, "новых записей не добавилось")
    }

    @Test
    fun `обрезанная строка отбрасывается молча, остальные переносятся`() {
        // Процесс мог умереть посередине записи — это ожидаемый случай, а не ошибка.
        val dir = temp.newFolder("spool5")
        val spool = EventSpool(dir)
        spool.append(record())
        java.io.File(dir, EventSpool.FILE_NAME).appendText("обрезано\tи\tнеполно\n")

        assertEquals(1, runBlocking { EventRecorder(db).drain(spool) })
    }

    @Test
    fun `скрытый номер записывается без выдуманных полей`() {
        val spool = EventSpool(temp.newFolder("spool6"))
        spool.append(
            ScreeningRecord(
                occurredAt = 1_800_000_000_000L,
                facts = CallFacts(
                    number = com.mist3s.nopecall.engine.NumberForms.EMPTY,
                    presentation = NumberPresentation.RESTRICTED,
                    name = com.mist3s.nopecall.engine.NameForms.NONE,
                    nameSource = NameSource.NONE,
                    inContacts = null,
                    isEmergency = false,
                ),
                decision = Decision.allow(DecisionReason.RESTRICTED_NUMBER)
                    .withDegradation(Degradation.NAME_UNAVAILABLE),
                matchedRuleTitle = null,
                budgetMs = 1500,
            )
        )
        runBlocking { EventRecorder(db).drain(spool) }

        val saved = runBlocking { db.events().recent(10) }.single()
        assertEquals("", saved.digits)
        assertEquals(null, saved.e164)
        assertEquals("RESTRICTED", saved.presentation)
        assertEquals("NONE", saved.nameSource)
        assertTrue(saved.degradations and Degradation.NAME_UNAVAILABLE.bit != 0)
    }

    @Test
    fun `пустая очередь не создаёт записей`() {
        val spool = EventSpool(temp.newFolder("spool7"))
        assertEquals(0, runBlocking { EventRecorder(db).drain(spool) })
        assertEquals(0, runBlocking { db.events().count() })
    }
}
