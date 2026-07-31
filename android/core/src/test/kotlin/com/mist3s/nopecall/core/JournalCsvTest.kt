package com.mist3s.nopecall.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.storage.JournalCsv
import com.mist3s.nopecall.core.storage.JournalFilter
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
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Экспорт журнала в CSV (ТЗ §7.6).
 *
 * Проверяется ровно то, что нельзя увидеть глазами при чтении кода: байты BOM, невидимый CRLF,
 * экранирование и обезвреживание формул. Дефекты здесь молчаливые: файл открывается, выглядит
 * похоже на правду, а колонки в одной строке съехали.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JournalCsvTest {

    private lateinit var db: NopeCallDatabase
    private lateinit var journal: JournalRepository
    private lateinit var csv: JournalCsv

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            NopeCallDatabase::class.java,
        ).allowMainThreadQueries().build()
        journal = JournalRepository(db)
        // Зона фиксирована: иначе тест на формат даты падал бы или проходил в зависимости
        // от настроек машины, на которой его запустили.
        csv = JournalCsv(journal, ZoneId.of("Europe/Moscow"))
    }

    @After
    fun tearDown() = db.close()

    // --- фикстуры (повторяют JournalFeedTest: тот же журнал, тот же запрос) --------------------

    private suspend fun event(
        at: Long,
        digits: String = "74951234567",
        action: String = "REJECT",
        nameRaw: String? = null,
        nameSource: String = "NONE",
        matchedSystemId: Long? = null,
    ): Long = db.events().insert(
        ScreeningEventEntity(
            occurredAt = at,
            rawNumber = "+$digits",
            digits = digits,
            e164 = "+$digits",
            presentation = "ALLOWED",
            nameRaw = nameRaw,
            nameSource = nameSource,
            action = action,
            reason = if (action == "ALLOW") "DEFAULT_ACTION" else "RULE_MATCH",
            matchedRuleId = if (action == "ALLOW") null else 1L,
            matchedRuleTitle = if (action == "ALLOW") null else "Правило",
            matchedSystemId = matchedSystemId,
            latencyMs = 12,
            budgetMs = 1500,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )
    )

    private suspend fun mirror(
        systemId: Long,
        at: Long,
        digits: String = "79998887766",
        type: String = "INCOMING",
        duration: Int = 42,
        name: String? = null,
    ) {
        db.mirror().upsert(
            systemId = systemId,
            startedAt = at,
            rawNumber = "+$digits",
            digits = digits,
            e164 = "+$digits",
            name = name,
            nameFold = name?.lowercase(),
            type = type,
            durationSeconds = duration,
            phoneAccountId = null,
            syncedAt = NOW,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )
    }

    /** Байты выгрузки — проверять формат можно только по ним: BOM это байты, а не символ. */
    private fun exportBytes(filter: JournalFilter = JournalFilter(), pageSize: Int = 500): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { csv.writeTo(out, filter, pageSize) }
        return out.toByteArray()
    }

    /** Текст без BOM, разбитый на строки по CRLF. Первая строка — заголовки. */
    private fun exportLines(
        filter: JournalFilter = JournalFilter(),
        pageSize: Int = 500,
    ): List<String> {
        val text = String(exportBytes(filter, pageSize), Charsets.UTF_8).removePrefix(JournalCsv.BOM)
        return text.split("\r\n").dropLast(1)
    }

    // --- формат файла -------------------------------------------------------------------------

    @Test
    fun `файл начинается с BOM в UTF-8`() {
        // Без BOM Excel читает файл в системной кодировке, и русские заголовки приходят
        // «крокозябрами». Ошибка невидима в тексте — проверять можно только байты.
        runBlocking { event(at = NOW - 1000) }
        val bytes = exportBytes()
        assertEquals(
            listOf(0xEF, 0xBB, 0xBF),
            bytes.take(3).map { it.toInt() and 0xFF },
            "первые три байта обязаны быть BOM UTF-8",
        )
    }

    @Test
    fun `строки разделены CRLF, а поля — точкой с запятой`() {
        // Excel в русской локали считает разделителем полей `;`; с запятой он свалил бы всю
        // строку в одну ячейку. CRLF — требование RFC 4180, и именно его Excel ждёт.
        runBlocking { event(at = NOW - 1000) }
        val text = String(exportBytes(), Charsets.UTF_8).removePrefix(JournalCsv.BOM)

        assertTrue(text.startsWith("когда;номер;e164;название;"), "заголовки: $text")
        assertTrue(text.endsWith("\r\n"), "последняя строка тоже обязана быть закрыта CRLF")
        assertEquals(
            0,
            text.replace("\r\n", "").count { it == '\n' || it == '\r' },
            "одиночных LF и CR в файле быть не должно",
        )

        val lines = exportLines()
        assertEquals(11, lines[0].split(';').size, "одиннадцать колонок по ТЗ §7.6")
        assertEquals(2, lines.size, "заголовок и одна запись")
    }

    @Test
    fun `метка времени — ISO 8601 с локальным смещением`() {
        // Без смещения метка неотличима от UTC, и выгрузки с разных устройств не сопоставить.
        // Excel такой формат не переписывает по своему усмотрению.
        runBlocking { event(at = NOW - 1000) }
        assertEquals(
            "2027-01-15T10:59:59+03:00",
            exportLines()[1].substringBefore(';'),
            "дата в ISO 8601 со смещением зоны",
        )
    }

    // --- экранирование (RFC 4180) --------------------------------------------------------------

    @Test
    fun `кавычка в названии удваивается, а значение берётся в кавычки`() {
        // Операторская подпись приходит в виде `ООО "Ромашка"`. Без удвоения кавычек значение
        // обрывает поле на середине, и дальше съезжают все колонки строки.
        runBlocking { event(at = NOW - 1000, nameRaw = "ООО \"Ромашка\"", nameSource = "CNAP") }
        assertTrue(
            exportLines()[1].contains(";\"ООО \"\"Ромашка\"\"\";"),
            "кавычки обязаны быть удвоены, поле — в кавычках: ${exportLines()[1]}",
        )
    }

    @Test
    fun `точка с запятой и перевод строки внутри значения не ломают строку`() {
        // Разделитель внутри значения — самый дорогой дефект выгрузки: файл открывается,
        // выглядит правдоподобно, но у одной строки все колонки сдвинуты вправо.
        runBlocking {
            event(at = NOW - 1000, nameRaw = "Ромашка; реклама\nи маркетинг", nameSource = "CNAP")
        }
        val text = String(exportBytes(), Charsets.UTF_8)
        assertTrue(
            text.contains("\"Ромашка; реклама\nи маркетинг\""),
            "значение с `;` и переводом строки обязано быть в кавычках: $text",
        )
        // Перевод строки внутри кавычек — единственный допустимый LF в файле: он часть значения,
        // и разбор по CRLF его не заметит.
        assertEquals(
            2,
            text.split("\r\n").dropLast(1).size,
            "заголовок и одна запись: перенос внутри значения не создаёт лишней строки",
        )
    }

    // --- CSV-инъекция --------------------------------------------------------------------------

    @Test
    fun `номер, начинающийся с плюса, обезврежен`() {
        // `+79991234567` Excel исполняет как формулу и показывает `#ЗНАЧ!` вместо номера.
        // Это не редкий случай, а обычный вид поля «номер» — то есть отказ по умолчанию.
        runBlocking { event(at = NOW - 1000, digits = "79991234567") }
        val fields = exportLines()[1].split(';')
        assertEquals("'+79991234567", fields[1], "номер обязан быть помечен как текст")
        assertEquals("'+79991234567", fields[2], "e164 — такой же случай")
    }

    @Test
    fun `формула в названии обезврежена и остаётся читаемой`() {
        // Название приходит от оператора, то есть снаружи. Апостроф, а не удаление символа:
        // выгрузка существует ради верных данных, а не ради безопасно испорченных.
        runBlocking {
            event(at = NOW - 1000, nameRaw = "=HYPERLINK(\"http://z\")", nameSource = "CNAP")
        }
        val line = exportLines()[1]
        assertTrue(
            line.contains("\"'=HYPERLINK(\"\"http://z\"\")\""),
            "апостроф впереди, кавычки удвоены, текст цел: $line",
        )
        assertTrue(!line.contains(";=HYPERLINK"), "значение не должно начинаться с `=`")
    }

    // --- содержимое выгрузки -------------------------------------------------------------------

    @Test
    fun `в выгрузку попадают и записи зеркала, и собственные события`() {
        // Выгружаться обязан тот же объединённый журнал, что показан на экране. Если брать
        // только свои события, файл не совпадёт с экраном — и это читается как потеря данных.
        runBlocking {
            mirror(systemId = 1, at = NOW - 1000, digits = "79998887766", name = "Мама")
            event(at = NOW - 2000, digits = "74951234567")
            // Сшитый звонок — одна строка, а не две: дубли исключены по matchedSystemId.
            mirror(systemId = 2, at = NOW - 3000, digits = "74957654321", type = "BLOCKED")
            event(at = NOW - 3000, digits = "74957654321", matchedSystemId = 2)
        }

        val lines = exportLines()
        assertEquals(4, lines.size, "заголовок и три звонка")
        assertTrue(lines[1].contains("Мама"), "запись зеркала: ${lines[1]}")
        assertTrue(lines[2].contains("'+74951234567"), "собственное событие: ${lines[2]}")
        assertTrue(
            lines[3].contains("BLOCKED_BY_APP") && lines[3].contains("Правило"),
            "сшитая запись выгружается с решением и правилом: ${lines[3]}",
        )
    }

    @Test
    fun `неизвестная длительность выгружается пустым полем, а не нулём`() {
        // `0` на месте `null` читался бы как «звонок длился ноль секунд», хотя исход неизвестен:
        // сервис проверки вызывается до звонка (ТЗ §7.2).
        runBlocking { event(at = NOW - 1000) }
        val fields = exportLines()[1].split(';')
        assertEquals("", fields[9], "длительность неизвестна")
        assertEquals("12", fields[10], "задержка решения, наоборот, известна всегда")
    }

    // --- фильтры и постраничное чтение ---------------------------------------------------------

    @Test
    fun `фильтр по периоду отсекает лишнее`() {
        // Фильтры выгрузки — те же, что у экрана. Иначе пользователь выбирает период на экране,
        // а получает файл за всё время.
        runBlocking {
            event(at = NOW - 1000, digits = "74951234567")
            mirror(systemId = 3, at = NOW - 2000, digits = "79998887766")
            event(at = NOW - 10 * DAY, digits = "79991112233")
            mirror(systemId = 4, at = NOW - 10 * DAY, digits = "79993334455")
        }

        val recent = exportLines(JournalFilter(fromAt = NOW - DAY))
        assertEquals(3, recent.size, "заголовок и две записи внутри периода")

        val old = exportLines(JournalFilter(toAt = NOW - DAY))
        assertEquals(3, old.size, "и симметрично — только записи до границы")
    }

    @Test
    fun `порционное чтение выгружает все записи по одному разу`() {
        // Экспорт идёт страницами, чтобы не собирать журнал в память. Курсор страницы тройной,
        // и при совпадающих метках времени легко потерять или удвоить строку на границе порции.
        runBlocking {
            repeat(7) { i -> event(at = NOW, digits = "7495000000$i") }
            repeat(7) { i -> mirror(systemId = 200L + i, at = NOW, digits = "7999000000$i") }
        }

        val lines = exportLines(pageSize = 3).drop(1)
        assertEquals(14, lines.size, "должны выгрузиться все записи")
        assertEquals(14, lines.distinct().size, "и ни одна дважды")
    }

    private companion object {
        /** 2027-01-15T11:00:00+03:00 — фиксированный момент, чтобы дата в тесте была явной. */
        const val NOW = 1_800_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
