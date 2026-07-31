package com.mist3s.nopecall.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.core.storage.ImportMode
import com.mist3s.nopecall.core.storage.ImportReport
import com.mist3s.nopecall.core.storage.ImportResult
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.RuleEntity
import com.mist3s.nopecall.core.storage.RulesRepository
import com.mist3s.nopecall.core.storage.RulesTransfer
import com.mist3s.nopecall.engine.Budget
import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.CallFacts
import com.mist3s.nopecall.engine.MatchType
import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.NameSource
import com.mist3s.nopecall.engine.NumberPresentation
import com.mist3s.nopecall.engine.RegexField
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import com.mist3s.nopecall.engine.RuleEngine
import com.mist3s.nopecall.engine.RuleTarget
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Экспорт и импорт правил (ТЗ §15.8).
 *
 * Формат резервной копии проверяется на настоящей базе и настоящем снимке: цена дефекта здесь —
 * потерянный или подменённый набор правил пользователя, то есть изменившиеся решения по звонкам.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesTransferTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var db: NopeCallDatabase
    private lateinit var snapshots: SnapshotStore
    private lateinit var repo: RulesRepository
    private lateinit var transfer: RulesTransfer

    private val normalizer = RuFastPathNormalizer()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NopeCallDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        snapshots = SnapshotStore(temp.newFolder("snap"))
        repo = RulesRepository(db, snapshots, normalizer) { FIXED_NOW }
        transfer = RulesTransfer(repo, appVersion = "1.2.3", now = { FIXED_NOW })
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- вспомогательное ------------------------------------------------------------------------

    private suspend fun threeRules() {
        repo.save(
            title = "Московские городские", target = RuleTarget.NUMBER,
            matchType = MatchType.PREFIX, pattern = "8495", action = CallAction.REJECT,
            comment = "робоколлы по недвижимости",
        )
        repo.save(
            title = "Мой банк", target = RuleTarget.NUMBER, matchType = MatchType.EXACT,
            pattern = "+78001234567", action = CallAction.ALLOW,
        )
        repo.save(
            title = "Разрешать контакты", target = RuleTarget.CONTACT,
            matchType = MatchType.IN_CONTACTS, pattern = "", action = CallAction.ALLOW,
        )
    }

    /** Сравнимое представление правила: всё, что влияет на решение, без идентификаторов и времени. */
    private fun RuleEntity.essence(): List<Any?> = listOf(
        title, targetType, matchType, pattern, patternCanonical, patternVariants, action,
        isEnabled, regexField, translitVariants, leetVariants, comment,
    )

    private suspend fun essences(): List<List<Any?>> =
        repo.all().sortedBy { it.title }.map { it.essence() }

    private suspend fun wipeRules() {
        repo.all().forEach { repo.delete(it.id) }
    }

    private fun facts(number: String? = "+79991234567", name: String? = null) = CallFacts(
        number = normalizer.normalize(number, "RU"),
        presentation = if (number == null) NumberPresentation.RESTRICTED else NumberPresentation.ALLOWED,
        name = NameCanonizer.canonize(name, RulesRepository.DEFAULT_CATEGORY_DICT.split(',').toSet()),
        nameSource = if (name == null) NameSource.NONE else NameSource.CNAP,
        inContacts = false,
        isEmergency = false,
    )

    private fun decide(f: CallFacts) =
        RuleEngine.decide(f, snapshots.current()!!, Budget.unlimited())

    /** Файл нужного формата из готовых элементов массива `rules`. */
    private fun fileOf(vararg rules: String, schema: Int = RulesTransfer.SCHEMA_VERSION): String =
        """{"schema":$schema,"exported_at":"2026-08-14T10:00:00+03:00","rules":[${rules.joinToString(",")}]}"""

    private fun done(result: ImportResult): ImportReport = when (result) {
        is ImportResult.Done -> result.report
        is ImportResult.Failed -> fail("файл должен был разобраться, отказ: ${result.reason}")
    }

    private fun failed(result: ImportResult): String = when (result) {
        is ImportResult.Failed -> result.reason
        is ImportResult.Done -> fail("файл обязан быть отклонён целиком, получен отчёт: ${result.report}")
    }

    // --- круг «экспорт → импорт» ---------------------------------------------------------------

    @Test
    fun `экспорт и импорт возвращают те же правила`() {
        // Дефект, который ловит тест: копия, из которой восстанавливается не то, что было.
        // Сравниваются все поля, влияющие на решение, включая канонический шаблон и варианты.
        runBlocking {
            threeRules()
            val before = essences()
            val json = transfer.exportJson()

            wipeRules()
            assertEquals(0, repo.all().size)

            val report = done(transfer.importJson(json, ImportMode.ADD_MISSING))
            assertEquals(3, report.added, "должны вернуться все три правила: $report")
            assertTrue(report.rejected.isEmpty(), "отклонений быть не должно: ${report.rejected}")
            assertEquals(before, essences(), "правила обязаны восстановиться дословно")
        }
    }

    @Test
    fun `правило по названию переживает круг вместе с вариантами и полем regex`() {
        // Дефект: технические поля в формат ТЗ §15.8 не входят, и без них правило возвращается
        // изменённым — regex по свёрнутому названию становится regex по полю по умолчанию,
        // а выключенные варианты транслитерации включаются обратно (ТЗ §6.3.1).
        runBlocking {
            repo.save(
                title = "полезный звонок", target = RuleTarget.NAME_ORG,
                matchType = MatchType.CONTAINS, pattern = "полезный звонок",
                action = CallAction.REJECT,
            )
            repo.save(
                title = "regex по организации", target = RuleTarget.NAME_ORG,
                matchType = MatchType.REGEX, pattern = "^ooo .*bank$",
                action = CallAction.REJECT, regexField = RegexField.NAME_FOLD,
                translitVariants = false,
            )
            val before = essences()
            val json = transfer.exportJson()

            wipeRules()
            done(transfer.importJson(json, ImportMode.ADD_MISSING))

            assertEquals(before, essences())
            val restored = repo.all().single { it.matchType == MatchType.REGEX.name }
            assertEquals(RegexField.NAME_FOLD.name, restored.regexField, "поле regex обязано вернуться")
            assertFalse(restored.translitVariants, "выключенные варианты обязаны остаться выключенными")
        }
    }

    @Test
    fun `в файле есть версия схемы и ключи формата ТЗ`() {
        // Дефект: файл без версии схемы нельзя ни развить, ни отвергнуть; переименованный ключ
        // молча ломает совместимость с примером из ТЗ §15.8.
        runBlocking {
            threeRules()
            val root = JSONObject(transfer.exportJson())

            assertEquals(RulesTransfer.SCHEMA_VERSION, root.getInt("schema"))
            assertEquals("1.2.3", root.getString("app_version"))
            assertTrue(root.getString("exported_at").isNotBlank())
            val array = root.getJSONArray("rules")
            assertEquals(3, array.length())

            // Правило ищется по названию, а не берётся первым: порядок в файле — это порядок
            // `orderIndex`, то есть лестница весов §5.1, а не порядок создания.
            val moscow = (0 until array.length())
                .map { array.getJSONObject(it) }
                .single { it.getString("title") == "Московские городские" }
            assertEquals("NUMBER", moscow.getString("target_type"))
            assertEquals("PREFIX", moscow.getString("match_type"))
            assertEquals("8495", moscow.getString("pattern"))
            assertEquals("REJECT", moscow.getString("action"))
            assertTrue(moscow.getBoolean("is_enabled"))
            assertEquals("робоколлы по недвижимости", moscow.getString("comment"))
        }
    }

    // --- идемпотентность ------------------------------------------------------------------------

    @Test
    fun `повторный импорт того же файла не создаёт дублей`() {
        // Главное требование ТЗ §15.8: ключ — target_type + match_type + pattern_canonical.
        runBlocking {
            threeRules()
            val json = transfer.exportJson()

            val second = done(transfer.importJson(json, ImportMode.ADD_MISSING))
            assertEquals(0, second.added, "правила уже есть: $second")
            assertEquals(3, second.duplicates, "все три обязаны быть посчитаны дубликатами: $second")
            assertEquals(3, repo.all().size, "дублей в базе быть не должно")

            val third = done(transfer.importJson(json, ImportMode.ADD_MISSING))
            assertEquals(0, third.added)
            assertEquals(3, repo.all().size)
        }
    }

    @Test
    fun `дубликат считается по каноническому шаблону, а не по написанию`() {
        // Дефект: `8 (495)` и `+7495` — один и тот же шаблон после канонизации. Сравнение по
        // тому, «как ввёл пользователь», создавало бы дубли при каждом импорте копии,
        // отредактированной руками.
        runBlocking {
            repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8 (495)", action = CallAction.REJECT,
            )
            val report = done(
                transfer.importJson(
                    fileOf(
                        """{"title":"Москва иначе","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"+7495","action":"REJECT","is_enabled":true}""",
                    ),
                    ImportMode.ADD_MISSING,
                ),
            )
            assertEquals(0, report.added, "это то же самое правило: $report")
            assertEquals(1, report.duplicates)
            assertEquals(1, repo.all().size)
        }
    }

    @Test
    fun `два одинаковых правила в одном файле дают одно`() {
        // Дефект: идемпотентность, проверенная только по базе, не спасает от файла, внутри
        // которого одна и та же строка повторена дважды.
        runBlocking {
            val line = """{"title":"Москва","target_type":"NUMBER","match_type":"PREFIX",
                "pattern":"8495","action":"REJECT","is_enabled":true}"""
            val report = done(transfer.importJson(fileOf(line, line), ImportMode.ADD_MISSING))
            assertEquals(1, report.added, "второй раз — дубликат: $report")
            assertEquals(1, report.duplicates)
            assertEquals(1, repo.all().size)
        }
    }

    // --- режимы ---------------------------------------------------------------------------------

    @Test
    fun `режим заменить всё удаляет лишние правила`() {
        // Дефект: «заменить всё», которое только добавляет, оставляет пользователя с суммой
        // старого и нового набора — то есть с блокировками, которых в копии нет.
        runBlocking {
            threeRules()
            val report = done(
                transfer.importJson(
                    fileOf(
                        """{"title":"Только это","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8495","action":"REJECT","is_enabled":true}""",
                    ),
                    ImportMode.REPLACE_ALL,
                ),
            )

            assertEquals(1, report.updated, "правило с тем же ключом обновляется на месте: $report")
            assertEquals(0, report.added)
            assertEquals(
                listOf("Мой банк", "Разрешать контакты"),
                report.removed.sorted(),
                "отчёт обязан называть удалённые правила",
            )
            assertEquals(listOf("Только это"), repo.all().map { it.title })
        }
    }

    @Test
    fun `режим добавить отсутствующие не трогает существующие правила`() {
        // Дефект: импорт, переписывающий существующее правило под видом «добавить
        // отсутствующие», меняет действие правила, которое пользователь настроил руками.
        runBlocking {
            repo.save(
                title = "Мой заголовок", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8495", action = CallAction.REJECT,
            )
            val report = done(
                transfer.importJson(
                    fileOf(
                        """{"title":"Чужой заголовок","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8495","action":"ALLOW","is_enabled":false}""",
                        """{"title":"Новое","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8812","action":"REJECT","is_enabled":true}""",
                    ),
                    ImportMode.ADD_MISSING,
                ),
            )

            assertEquals(1, report.added, "добавиться должно только новое правило: $report")
            assertEquals(1, report.duplicates)
            val kept = repo.all().single { it.patternCanonical == "7495" }
            assertEquals("Мой заголовок", kept.title)
            assertEquals(CallAction.REJECT.name, kept.action, "действие менять было нельзя")
            assertTrue(kept.isEnabled)
        }
    }

    // --- поштучное отклонение -------------------------------------------------------------------

    @Test
    fun `битая запись отклоняется с причиной, остальные импортируются`() {
        // Дефект: одна испорченная строка обрывает разбор, и из копии на 40 правил не
        // восстанавливается ни одно (ТЗ §15.8).
        runBlocking {
            val report = done(
                transfer.importJson(
                    fileOf(
                        """{"title":"Первое","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8495","action":"REJECT","is_enabled":true}""",
                        """{"title":"Чужой тип","target_type":"CAR_PLATE","match_type":"PREFIX",
                            "pattern":"8499","action":"REJECT"}""",
                        """{"title":"Чужое сравнение","target_type":"NUMBER","match_type":"LOOKS_LIKE",
                            "pattern":"8499","action":"REJECT"}""",
                        """{"title":"Чужое действие","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8499","action":"EXPLODE"}""",
                        "\"строка вместо объекта\"",
                        """{"title":"Последнее","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8812","action":"REJECT","is_enabled":true}""",
                    ),
                    ImportMode.ADD_MISSING,
                ),
            )

            assertEquals(2, report.added, "целые правила обязаны импортироваться: $report")
            assertEquals(4, report.rejected.size, "отклонения: ${report.rejected}")
            assertTrue(
                report.rejected.all { it.reason.isNotBlank() },
                "у каждого отклонения обязана быть причина: ${report.rejected}",
            )
            assertEquals(
                listOf(1, 2, 3, 4), report.rejected.map { it.index },
                "индекс обязан указывать на строку файла",
            )
            assertEquals(listOf("Первое", "Последнее"), repo.all().map { it.title }.sorted())
        }
    }

    @Test
    fun `неверное регулярное выражение отклоняется поштучно`() {
        // Дефект: импорт как второй путь создания правил протаскивает шаблон, который нельзя
        // создать руками. Проверяются оба отказа валидатора: некомпилируемое выражение
        // и катастрофическое — второе упиралось бы в бюджет на каждом звонке (ТЗ §6.5).
        runBlocking {
            val report = done(
                transfer.importJson(
                    fileOf(
                        """{"title":"битое","target_type":"NUMBER","match_type":"REGEX",
                            "pattern":"^(unclosed","action":"REJECT"}""",
                        """{"title":"дорогое","target_type":"NAME","match_type":"REGEX",
                            "pattern":"^(a+)+b$","action":"REJECT"}""",
                        """{"title":"без цифр и букв","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"---","action":"REJECT"}""",
                        """{"title":"целое","target_type":"NUMBER","match_type":"REGEX",
                            "pattern":"^7495\\d{7}$","action":"REJECT","is_enabled":true}""",
                    ),
                    ImportMode.ADD_MISSING,
                ),
            )

            assertEquals(1, report.added, "уцелеть обязано только корректное правило: $report")
            assertEquals(3, report.rejected.size, "отклонения: ${report.rejected}")
            assertEquals(
                listOf("без цифр и букв", "битое", "дорогое"),
                report.rejected.mapNotNull { it.title }.sorted(),
            )
            assertEquals(listOf("целое"), repo.all().map { it.title })
        }
    }

    @Test
    fun `незнакомые ключи не мешают импорту`() {
        // В примере ТЗ §15.8 есть ключ `notify`, которого в схеме правила нет. Незнакомый ключ
        // обязан игнорироваться, иначе приложение не импортирует пример из собственного ТЗ.
        runBlocking {
            val report = done(
                transfer.importJson(
                    fileOf(
                        """{"title":"Москва","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8495","action":"REJECT","is_enabled":true,"notify":false,
                            "нечто":{"вложенное":1}}""",
                    ),
                    ImportMode.ADD_MISSING,
                ),
            )
            assertEquals(1, report.added, "отчёт: $report")
            assertTrue(report.rejected.isEmpty(), "${report.rejected}")
        }
    }

    // --- отклонение файла целиком ---------------------------------------------------------------

    @Test
    fun `посторонний файл не меняет базу`() {
        // Дефект: разрушающий режим, применённый к постороннему файлу, удаляет все правила.
        runBlocking {
            threeRules()

            assertTrue(
                failed(transfer.importJson("не json вовсе", ImportMode.REPLACE_ALL)).isNotBlank(),
                "у отказа обязана быть причина",
            )
            assertTrue(
                failed(transfer.importJson("""{"rules":[]}""", ImportMode.REPLACE_ALL)).isNotBlank(),
                "без версии схемы файл не наш",
            )
            assertTrue(
                failed(transfer.importJson("""{"schema":1}""", ImportMode.REPLACE_ALL)).isNotBlank(),
                "отсутствие списка правил — испорченный файл, а не пустой набор",
            )
            assertEquals(3, repo.all().size, "ни одно правило не должно было пострадать")
        }
    }

    @Test
    fun `файл более новой версии отклоняется целиком`() {
        // Дефект: частичный разбор файла из будущего создаёт правила, смысл ключей в которых
        // мог измениться, — то есть решения по звонкам, которых пользователь не задавал.
        runBlocking {
            val reason = failed(
                transfer.importJson(
                    fileOf(
                        """{"title":"Москва","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8495","action":"REJECT"}""",
                        schema = RulesTransfer.SCHEMA_VERSION + 1,
                    ),
                    ImportMode.ADD_MISSING,
                ),
            )
            assertTrue(reason.contains("версии"), "причина обязана называть версию: $reason")
            assertEquals(0, repo.all().size)
        }
    }

    @Test
    fun `явно пустой список правил в режиме заменить всё чистит набор`() {
        // Обратная сторона предыдущего теста: `"rules": []` — осознанное «правил нет»,
        // и путать его с испорченным файлом тоже нельзя.
        runBlocking {
            threeRules()
            val report = done(transfer.importJson(fileOf(), ImportMode.REPLACE_ALL))
            assertEquals(3, report.removed.size, "отчёт: $report")
            assertEquals(0, repo.all().size)
        }
    }

    // --- снимок после импорта -------------------------------------------------------------------

    @Test
    fun `после импорта снимок пересобран и правила действуют`() {
        // Без пересборки пользователь восстановил бы копию, увидел список правил и не понял,
        // почему звонки идут до перезапуска приложения (ТЗ §8.2).
        runBlocking {
            snapshots.clear()
            assertFalse(snapshots.exists())

            val report = done(
                transfer.importJson(
                    fileOf(
                        """{"title":"Москва","target_type":"NUMBER","match_type":"PREFIX",
                            "pattern":"8495","action":"REJECT","is_enabled":true}""",
                    ),
                    ImportMode.ADD_MISSING,
                ),
            )
            assertTrue(report.snapshotRebuilt, "отчёт обязан сообщать о пересборке: $report")
            assertTrue(snapshots.exists(), "снимок должен быть записан")
            assertNotNull(snapshots.current())
            assertEquals(CallAction.REJECT, decide(facts("+74951234567")).action)
            assertEquals(CallAction.ALLOW, decide(facts("+79991234567")).action)
        }
    }

    @Test
    fun `снимок пересобирается, даже если ни одно правило не применилось`() {
        // Дефект: пересборка только внутри save. Файл целиком из отклонений или из дубликатов
        // не вызывает save ни разу, и отсутствующий снимок — состояние после обновления
        // приложения — остался бы отсутствующим при живых правилах в базе.
        runBlocking {
            repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8495", action = CallAction.REJECT,
            )
            snapshots.clear()
            assertFalse(snapshots.exists())

            val report = done(
                transfer.importJson(
                    fileOf(
                        """{"title":"битое","target_type":"NUMBER","match_type":"REGEX",
                            "pattern":"^(unclosed","action":"REJECT"}""",
                    ),
                    ImportMode.ADD_MISSING,
                ),
            )
            assertEquals(0, report.added, "отчёт: $report")
            assertEquals(1, report.rejected.size)
            assertTrue(report.snapshotRebuilt)
            assertTrue(snapshots.exists(), "снимок обязан быть пересобран и без единого save")
            assertEquals(CallAction.REJECT, decide(facts("+74951234567")).action)
        }
    }

    private companion object {
        const val FIXED_NOW = 1_800_000_000_000L
    }
}
