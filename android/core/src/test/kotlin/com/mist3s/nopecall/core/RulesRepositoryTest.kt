package com.mist3s.nopecall.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.RulesRepository
import com.mist3s.nopecall.core.storage.SaveResult
import com.mist3s.nopecall.engine.Budget
import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.CallFacts
import com.mist3s.nopecall.engine.DecisionReason
import com.mist3s.nopecall.engine.MatchType
import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.NameSource
import com.mist3s.nopecall.engine.NumberPresentation
import com.mist3s.nopecall.engine.PatternCheck
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import com.mist3s.nopecall.engine.RuleEngine
import com.mist3s.nopecall.engine.RuleTarget
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правила от сохранения до действующего снимка.
 *
 * Это главный интеграционный тест приложения: он проверяет то, из-за чего пользователь решил бы,
 * что приложение не работает, — создал правило, а звонки не блокируются. Room здесь настоящий
 * (in-memory через Robolectric), снимок пишется на диск и читается обратно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RulesRepositoryTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var db: NopeCallDatabase
    private lateinit var snapshots: SnapshotStore
    private lateinit var repo: RulesRepository

    private val normalizer = RuFastPathNormalizer()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NopeCallDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        snapshots = SnapshotStore(temp.newFolder("snap"))
        repo = RulesRepository(db, snapshots, normalizer) { FIXED_NOW }
    }

    @After
    fun tearDown() {
        db.close()
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

    // --- сохранение делает правило действующим -----------------------------------------------

    @Test
    fun `редактор обещает ровно те написания, что попадут в снимок`() {
        // Инвариант, а не пример: раньше проверка шаблона считала варианты для категории
        // вызова, а сохранение флаг не ставило — редактор показывал десятки написаний,
        // в снимке было одно.
        for (target in listOf(RuleTarget.NAME, RuleTarget.NAME_ORG, RuleTarget.NAME_CATEGORY)) {
            val check = repo.validate(target, MatchType.TOKEN, "Полезный") as PatternCheck.Ok
            val id = runBlocking {
                repo.save(
                    id = null, title = "п", target = target, matchType = MatchType.TOKEN,
                    pattern = "Полезный", action = CallAction.REJECT,
                )
            }
            val saved = runBlocking { db.rules().byId((id as SaveResult.Saved).id) }!!

            val inSnapshot = if (saved.translitVariants) {
                saved.patternVariants.split("\n").filter { it.isNotEmpty() }.toSet()
            } else {
                emptySet()
            }
            val promised = check.variants.toSet()

            assertEquals(
                promised.isEmpty(), inSnapshot.isEmpty(),
                "$target: редактор и снимок обязаны сходиться в самом факте вариантов",
            )
            if (promised.isNotEmpty()) assertEquals(promised, inSnapshot, "$target")
        }
    }

    @Test
    fun `сохранённое правило сразу начинает блокировать`() {
        // Это и есть смысл пересборки снимка при каждом изменении: без неё пользователь
        // создал бы правило, увидел его в списке и не понял, почему звонки идут.
        runBlocking {
            val result = repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8495", action = CallAction.REJECT,
            )
            assertTrue(result is SaveResult.Saved)
            assertTrue(snapshots.exists(), "снимок должен быть записан")

            assertEquals(CallAction.REJECT, decide(facts("+74951234567")).action)
            assertEquals(CallAction.ALLOW, decide(facts("+79991234567")).action)
        }
    }

    @Test
    fun `выключение правила сразу перестаёт блокировать`() {
        runBlocking {
            val saved = repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8495", action = CallAction.REJECT,
            ) as SaveResult.Saved
            assertEquals(CallAction.REJECT, decide(facts("+74951234567")).action)

            repo.setEnabled(saved.id, false)
            assertEquals(CallAction.ALLOW, decide(facts("+74951234567")).action)
        }
    }

    @Test
    fun `удаление правила сразу перестаёт блокировать`() {
        runBlocking {
            val saved = repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8495", action = CallAction.REJECT,
            ) as SaveResult.Saved
            repo.delete(saved.id)
            assertEquals(CallAction.ALLOW, decide(facts("+74951234567")).action)
            assertEquals(0, repo.all().size)
        }
    }

    // --- проверка шаблона до записи ----------------------------------------------------------

    @Test
    fun `некорректное регулярное выражение не сохраняется`() {
        runBlocking {
            val result = repo.save(
                title = "битое", target = RuleTarget.NUMBER, matchType = MatchType.REGEX,
                pattern = "^(unclosed", action = CallAction.REJECT,
            )
            assertTrue(result is SaveResult.Rejected)
            assertEquals(0, repo.all().size, "в базе не должно остаться следов")
        }
    }

    @Test
    fun `катастрофическое регулярное выражение не сохраняется`() {
        // Иначе каждый звонок упирался бы в бюджет, а пользователь видел бы только
        // «правило не сработало» (ТЗ §6.5).
        runBlocking {
            val result = repo.save(
                title = "дорогое", target = RuleTarget.NAME, matchType = MatchType.REGEX,
                pattern = "^(a+)+b$", action = CallAction.REJECT,
            )
            assertTrue(result is SaveResult.Rejected)
            assertEquals(0, repo.all().size)
        }
    }

    @Test
    fun `шаблон без цифр и букв отвергается`() {
        runBlocking {
            val result = repo.save(
                title = "мусор", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "---", action = CallAction.REJECT,
            )
            assertTrue(result is SaveResult.Rejected)
        }
    }

    // --- канонизация шаблона ------------------------------------------------------------------

    @Test
    fun `шаблон хранится и как ввёл пользователь, и канонизированным`() {
        runBlocking {
            repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8 (495)", action = CallAction.REJECT,
            )
            val stored = repo.all().single()
            assertEquals("8 (495)", stored.pattern, "показывать надо то, что ввёл пользователь")
            assertEquals("7495", stored.patternCanonical, "сопоставлять — канонизированное")
        }
    }

    @Test
    fun `русский шаблон по названию ловит подпись транслитом`() {
        runBlocking {
            repo.save(
                title = "реклама", target = RuleTarget.NAME, matchType = MatchType.CONTAINS,
                pattern = "реклама", action = CallAction.REJECT,
            )
            assertEquals(
                CallAction.REJECT,
                decide(facts("+79990000000", "OOO Romashka, reklama")).action,
            )
        }
    }

    @Test
    fun `варианты транслитерации для наименований включены по умолчанию`() {
        // У одного юрлица наблюдались `Poleznyy` и `Polezniy`: без вариантов правило поймало бы
        // одно написание и пропустило второе (ТЗ §6.3.1).
        runBlocking {
            val saved = repo.save(
                title = "полезный звонок", target = RuleTarget.NAME_ORG,
                matchType = MatchType.CONTAINS, pattern = "полезный звонок",
                action = CallAction.REJECT,
            ) as SaveResult.Saved
            assertTrue(saved.variants.size > 1, "варианты должны быть раскрыты и показаны")
            assertTrue(repo.all().single().translitVariants)

            assertEquals(CallAction.REJECT, decide(facts("+79990000000", "OOO Poleznyy Zvonok: agentstvo")).action)
            assertEquals(CallAction.REJECT, decide(facts("+79990000000", "OOO Polezniy zvonok: reklama")).action)
        }
    }

    @Test
    fun `для номеров варианты не раскрываются`() {
        runBlocking {
            val saved = repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8495", action = CallAction.REJECT,
            ) as SaveResult.Saved
            assertTrue(saved.variants.isEmpty(), "транслит к цифрам не применяется")
        }
    }

    // --- порядок правил ----------------------------------------------------------------------

    @Test
    fun `разрешающее правило автоматически встаёт выше блокирующего`() {
        // Лестница весов §5.1 работает как автоматическая расстановка: пользователь не обязан
        // думать о порядке, чтобы «разрешить конкретный номер» победило «блокировать префикс».
        runBlocking {
            repo.save(
                title = "блок", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "7999", action = CallAction.REJECT,
            )
            repo.save(
                title = "разрешить", target = RuleTarget.NUMBER, matchType = MatchType.EXACT,
                pattern = "+79991234567", action = CallAction.ALLOW,
            )
            val d = decide(facts("+79991234567"))
            assertEquals(CallAction.ALLOW, d.action)
            assertEquals(DecisionReason.RULE_MATCH, d.reason)
        }
    }

    @Test
    fun `перенумерация в одной транзакции не ломает порядок`() {
        runBlocking {
            val a = (repo.save(title = "A", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "7999", action = CallAction.REJECT) as SaveResult.Saved).id
            val b = (repo.save(title = "B", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "7999", action = CallAction.SILENCE) as SaveResult.Saved).id

            // Сначала выигрывает A: у него меньший orderIndex.
            assertEquals(CallAction.REJECT, decide(facts("+79991234567")).action)

            repo.reorder(listOf(b, a))
            assertEquals(CallAction.SILENCE, decide(facts("+79991234567")).action)

            val ordered = repo.all().sortedBy { it.orderIndex }.map { it.id }
            assertEquals(listOf(b, a), ordered)
            assertEquals(
                ordered.size, ordered.distinct().size,
                "orderIndex обязан остаться уникальным: это инвариант, а не UNIQUE в схеме",
            )
        }
    }

    // --- настройки ----------------------------------------------------------------------------

    @Test
    fun `главный выключатель через настройки разрешает всё`() {
        runBlocking {
            repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8495", action = CallAction.REJECT,
            )
            assertEquals(CallAction.REJECT, decide(facts("+74951234567")).action)

            repo.putSetting(RulesRepository.KEY_BLOCKING_ENABLED, "false")
            val d = decide(facts("+74951234567"))
            assertEquals(CallAction.ALLOW, d.action)
            assertEquals(DecisionReason.DISABLED_BY_USER, d.reason)
        }
    }

    @Test
    fun `режим блокировать всё кроме разрешённого работает через default_action`() {
        runBlocking {
            repo.putSetting(RulesRepository.KEY_DEFAULT_ACTION, "REJECT")
            assertEquals(CallAction.REJECT, decide(facts("+79991234567")).action)

            repo.save(
                title = "разрешить", target = RuleTarget.NUMBER, matchType = MatchType.EXACT,
                pattern = "+79991234567", action = CallAction.ALLOW,
            )
            assertEquals(CallAction.ALLOW, decide(facts("+79991234567")).action)
        }
    }

    @Test
    fun `словарь категорий из настроек применяется к подписям без двоеточия`() {
        runBlocking {
            repo.save(
                title = "транспорт", target = RuleTarget.NAME_CATEGORY, matchType = MatchType.PREFIX,
                pattern = "transport", action = CallAction.REJECT,
            )
            // `AYSBERG-ZAPAD Transport` — категория без двоеточия, распознаётся по словарю.
            val f = CallFacts(
                number = normalizer.normalize("+79990000000", "RU"),
                presentation = NumberPresentation.ALLOWED,
                name = NameCanonizer.canonize(
                    "AYSBERG-ZAPAD Transport",
                    snapshots.current()!!.settings.categoryDictionary,
                ),
                nameSource = NameSource.CNAP,
                inContacts = false,
                isEmergency = false,
            )
            assertEquals(CallAction.REJECT, decide(f).action)
        }
    }

    // --- пересборка снимка --------------------------------------------------------------------

    @Test
    fun `пересборка снимка восстанавливает его после удаления файла`() {
        // Сценарий после обновления приложения: снимка нет, а правила есть.
        runBlocking {
            repo.save(
                title = "Москва", target = RuleTarget.NUMBER, matchType = MatchType.PREFIX,
                pattern = "8495", action = CallAction.REJECT,
            )
            snapshots.clear()
            assertFalse(snapshots.exists())
            assertNull(snapshots.current())

            assertTrue(repo.rebuildSnapshot())
            assertNotNull(snapshots.current())
            assertEquals(CallAction.REJECT, decide(facts("+74951234567")).action)
        }
    }

    private companion object {
        const val FIXED_NOW = 1_800_000_000_000L
    }
}
