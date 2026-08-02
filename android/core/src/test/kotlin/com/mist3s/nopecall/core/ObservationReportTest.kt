package com.mist3s.nopecall.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.observe.ObservationConfig
import com.mist3s.nopecall.core.observe.ObservationLog
import com.mist3s.nopecall.core.observe.ObservationReporter
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.ScreeningEventEntity
import com.mist3s.nopecall.engine.Degradation
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сводка режима наблюдения (ТЗ §7.7.5).
 *
 * Считается запросами по журналу, а не разбором сегментов JSONL: экран сводки открывают часто,
 * и читать ради него десятки мегабайт JSON значило бы сделать его бесполезным. Тесты фиксируют
 * именно те показатели, ради которых собираются логи.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObservationReportTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var db: NopeCallDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            NopeCallDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun reporter(): ObservationReporter = ObservationReporter(
        db = db,
        log = ObservationLog(
            dir = temp.root,
            configProvider = { ObservationConfig() },
            now = { NOW },
        ),
        now = { NOW },
    )

    private suspend fun event(
        at: Long = NOW - 1000,
        nameRaw: String? = null,
        nameSource: String = "NONE",
        nameLate: Boolean? = null,
        presentation: String = "ALLOWED",
        latencyMs: Int = 10,
        degradations: Int = 0,
        coldStart: Boolean? = null,
        networkType: String? = null,
        volte: Boolean? = null,
        extrasKeys: String? = null,
    ) {
        db.events().insert(
            ScreeningEventEntity(
                occurredAt = at,
                rawNumber = "+74951234567",
                digits = "74951234567",
                presentation = presentation,
                nameRaw = nameRaw,
                nameFold = nameRaw?.lowercase(),
                nameSource = nameSource,
                nameLate = nameLate,
                action = "ALLOW",
                reason = "DEFAULT_ACTION",
                degradations = degradations,
                latencyMs = latencyMs,
                budgetMs = 1500,
                canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
                coldStart = coldStart,
                networkType = networkType,
                volte = volte,
                extrasKeys = extrasKeys,
            )
        )
    }

    @Test
    fun `доля подписей и доля досланных после решения считаются отдельно`() {
        // Ключевой показатель проекта: если подпись досылается после решения, правила
        // по названию на таких звонках работать не могут (ТЗ §21 п. 4).
        runBlocking {
            event(nameRaw = "Yandex: IT", nameSource = "CNAP")
            event(nameRaw = "OOO Romashka", nameSource = "CNAP")
            // Подпись дошла после решения — это и есть ответ на §21 п. 4.
            event(nameRaw = "PAO SOVKOMBANK", nameSource = "CNAP", nameLate = true)
            // Имя из телефонной книги, узнанное позже. Подписью НЕ является: на реальном
            // телефоне ровно такие записи давали «оператор досылает подпись в 67 % звонков».
            event(nameRaw = "Мама", nameSource = "CONTACTS", nameLate = true)
            event()
        }

        val report = runBlocking { reporter().report(periodDays = 30) }
        assertEquals(5, report.checks)
        assertEquals(2, report.withSignature, "подпись в момент решения — только две")
        assertEquals(2, report.namesAtDecision, "поздние названия сюда не входят")
        assertEquals(2, report.lateNames, "поздних названий два: подпись и имя контакта")
        assertEquals(1, report.lateSignatures, "но подпись среди них одна")
        assertEquals(1, report.withoutName)
        assertEquals(0.4, report.signatureShare)
        assertEquals(0.2, report.lateSignatureShare)
    }

    @Test
    fun `имя из книги, узнанное позже, не считается операторской подписью`() {
        // Дефект, найденный на реальном телефоне: `CallLog.CACHED_NAME` для номера из книги —
        // это имя контакта, а показатель «оператор досылает подпись» считал таковой любое
        // позднее название. Сводка утверждала 67 % там, где подписей не было вовсе.
        runBlocking {
            event(nameRaw = "Мама", nameSource = "CONTACTS", nameLate = true)
            event(nameRaw = "Теща Лена", nameSource = "CONTACTS", nameLate = true)
            event()
        }

        val report = runBlocking { reporter().report() }
        assertEquals(0, report.lateSignatures, "подписей не было ни одной")
        assertEquals(0.0, report.lateSignatureShare)
        assertEquals(2, report.lateNames, "но два названия действительно дошли позже")
        assertEquals(0, report.namesAtDecision, "в момент решения названия не было ни у одного")
    }

    @Test
    fun `неустановленный источник позднего названия не идёт в подписи`() {
        // Так помечены события, записанные до появления флага: задним числом выяснить,
        // было ли название именем контакта, нельзя, и выдавать его за подпись нельзя тоже.
        runBlocking { event(nameRaw = "Мама", nameSource = "SYSTEM_LOG", nameLate = true) }

        val report = runBlocking { reporter().report() }
        assertEquals(0, report.lateSignatures)
        assertEquals(1, report.lateNames)
    }

    @Test
    fun `перцентили задержки считаются по фактическим значениям`() {
        // Метод «ближайший ранг»: p-й перцентиль сотни значений — это ровно p-е по величине.
        // Раньше индекс округлялся вниз и результат сдвигался на ранг: p50 показывал 51-е,
        // p95 — 96-е. Для показателя, по которому судят, укладывается ли решение в бюджет,
        // сдвиг в сторону «хуже, чем есть» безвреден, но это всё равно неверное число.
        runBlocking { (1..100).forEach { event(latencyMs = it) } }
        val report = runBlocking { reporter().report() }
        assertEquals(50, report.latencyP50)
        assertEquals(95, report.latencyP95)
        assertEquals(100, report.latencyMax)
    }

    @Test
    fun `сводка называет настоящую причину отсутствия названия`() {
        // Причина не в операторе и не в типе сети: система не передаёт название приложениям
        // проверки звонков — Telecom обнуляет поле в toParcelableCallForScreening. Прежние
        // формулировки объясняли это VoLTE, и обе оказались неверны.
        runBlocking {
            event(volte = false)
            event(volte = true)
        }
        val text = runBlocking { reporter().report() }.toText()

        assertTrue(text.contains("система его не передаёт"), text)
        assertTrue(text.contains("ParcelableCallUtils"), text)
        // Про VoLTE в объяснении больше ничего нет: разбивка по сети осталась отдельной
        // строкой как наблюдение о сети, а не как причина.
        assertFalse(text.contains("без VoLTE"), text)
    }

    @Test
    fun `разбивка по VoLTE остаётся наблюдением о сети`() {
        runBlocking {
            event(volte = false)
            event(volte = true)
        }
        val report = runBlocking { reporter().report() }

        assertEquals(1, report.checksWithoutVolte)
        assertEquals(1, report.volte.single { it.bucket == "VOLTE" }.total)
    }

    @Test
    fun `перцентиль на двух значениях берёт большее`() {
        // Крайний случай, на котором старая формула ошибалась заметнее всего: индекс 1 против 0.
        runBlocking {
            event(latencyMs = 10)
            event(latencyMs = 20)
        }
        val report = runBlocking { reporter().report() }
        assertEquals(10, report.latencyP50, "медиана двух значений — первое по ранку")
        assertEquals(20, report.latencyP95)
    }

    @Test
    fun `холодные старты и срабатывания сторожа видны`() {
        runBlocking {
            event(coldStart = true)
            event(coldStart = false)
            event(degradations = Degradation.WATCHDOG_ANSWERED.bit)
        }
        val report = runBlocking { reporter().report() }
        assertEquals(1, report.coldStarts)
        assertEquals(1, report.watchdogFired)
    }

    @Test
    fun `скрытые и неопределённые номера считаются вместе`() {
        runBlocking {
            event(presentation = "RESTRICTED")
            event(presentation = "UNKNOWN")
            event(presentation = "ALLOWED")
        }
        assertEquals(2, runBlocking { reporter().report() }.hiddenNumbers)
    }

    @Test
    fun `ключи extras считаются по звонкам, а не по вхождениям`() {
        // Вопрос «куда вендор кладёт подпись» — это вопрос «в каком количестве звонков
        // вообще встретился такой ключ».
        runBlocking {
            event(extrasKeys = "a.b.SUBJECT,vendor.x,vendor.x")
            event(extrasKeys = "vendor.x")
        }
        val keys = runBlocking { reporter().report() }.extrasKeys.associate { it.bucket to it.total }
        assertEquals(2, keys["vendor.x"], "повтор внутри одного звонка не удваивает счёт")
        assertEquals(1, keys["a.b.SUBJECT"])
    }

    @Test
    fun `разбивка по сети и VoLTE отличает неизвестное от отсутствия`() {
        runBlocking {
            event(networkType = "LTE", volte = true)
            event(networkType = "LTE", volte = false)
            event()
        }
        val report = runBlocking { reporter().report() }
        assertEquals(2, report.networkTypes.single { it.bucket == "LTE" }.total)
        assertEquals(1, report.networkTypes.single { it.bucket == "UNKNOWN" }.total)
        assertEquals(1, report.volte.single { it.bucket == "VOLTE" }.total)
        assertEquals(1, report.volte.single { it.bucket == "NO_VOLTE" }.total)
        assertEquals(1, report.volte.single { it.bucket == "UNKNOWN" }.total)
    }

    @Test
    fun `примеры подписей группируются и показывают свёрнутую форму`() {
        runBlocking {
            event(nameRaw = "OOO Poleznyy Zvonok: agentstvo", nameSource = "CNAP")
            event(nameRaw = "OOO Poleznyy Zvonok: agentstvo", nameSource = "CNAP")
            event(nameRaw = "Yandex: IT", nameSource = "CNAP")
            // Имя из телефонной книги в примеры подписей попадать не должно.
            event(nameRaw = "Мама", nameSource = "CONTACTS")
        }
        val samples = runBlocking { reporter().report() }.signatures
        assertEquals(2, samples.size)
        assertEquals("OOO Poleznyy Zvonok: agentstvo", samples.first().raw)
        assertEquals(2, samples.first().total)
        assertTrue(samples.none { it.raw == "Мама" })
    }

    @Test
    fun `записи вне периода в сводку не попадают`() {
        runBlocking {
            event(at = NOW - 1000)
            event(at = NOW - 40L * 24 * 60 * 60 * 1000)
        }
        assertEquals(1, runBlocking { reporter().report(periodDays = 30) }.checks)
    }

    @Test
    fun `текстовая сводка содержит главные показатели`() {
        // Она уходит в архив как summary.txt: присланный архив открывают, чтобы ответить
        // на вопрос, а не чтобы писать под него парсер (ТЗ §7.7.3).
        runBlocking {
            event(nameRaw = "Yandex: IT", nameSource = "CNAP")
            event(nameRaw = "PAO SOVKOMBANK", nameSource = "CNAP", nameLate = true)
            event(nameRaw = "Мама", nameSource = "CONTACTS", nameLate = true)
        }
        val text = runBlocking { reporter().report() }.toText()
        assertTrue(text.contains("Проверок: 3"))
        assertTrue(text.contains("подпись оператора ПОСЛЕ решения: 1"), text)
        assertTrue(
            text.contains("название дописано позже: 2, из них подпись оператора: 1"),
            "имя контакта обязано быть отделено от подписи: $text",
        )
        // Подписи показываются дословно: ради них режим наблюдения и существует.
        assertTrue(text.contains("Yandex: IT"))
        // И в разбивке по источнику — по-русски, а не служебным кодом.
        assertTrue(text.contains("телефонная книга, узнали позже"), text)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
