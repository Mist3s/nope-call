package com.mist3s.nopecall.core

import com.mist3s.nopecall.core.facts.CallDetailsReader.Companion.PRESENTATION_RESTRICTED
import com.mist3s.nopecall.core.facts.CallFactsBuilder
import com.mist3s.nopecall.core.facts.ContactMembership
import com.mist3s.nopecall.core.facts.EmergencyNumbers
import com.mist3s.nopecall.core.screening.ScreeningPipeline
import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.engine.Budget
import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.DecisionReason
import com.mist3s.nopecall.engine.DecisionSettings
import com.mist3s.nopecall.engine.Degradation
import com.mist3s.nopecall.engine.MatchType
import com.mist3s.nopecall.engine.NumberForms
import com.mist3s.nopecall.engine.PhoneNumberNormalizer
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import com.mist3s.nopecall.engine.Rule as BlockRule
import com.mist3s.nopecall.engine.RuleTarget
import com.mist3s.nopecall.engine.SnapshotBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Весь путь принятия решения: данные системы → факты → снимок → решение.
 *
 * Конвейер вынесен из сервиса именно ради этого теста. В сервисе остаётся только то, что без
 * устройства не проверить — привязка Telecom, сторожевой таймер и отправка ответа
 * (архитектура §4).
 */
class ScreeningPipelineTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val settings = DecisionSettings(
        categoryDictionary = setOf("dostavka", "it", "reklam"),
    )

    private fun pipeline(
        rules: List<BlockRule>,
        settings: DecisionSettings = this.settings,
        directBoot: Boolean = false,
        writeSnapshot: Boolean = true,
        contacts: ContactMembership = ContactMembership.NONE,
    ): Pair<ScreeningPipeline, SnapshotStore> {
        val store = SnapshotStore(temp.newFolder("s-${counter++}"))
        if (writeSnapshot) {
            store.write(SnapshotBuilder(RuFastPathNormalizer()).build(rules, settings))
        }
        val p = ScreeningPipeline(
            snapshots = store,
            factsBuilder = CallFactsBuilder(
                RuFastPathNormalizer(),
                contacts,
                EmergencyNumbers { it == "112" },
            ),
            directBoot = { directBoot },
        )
        return p to store
    }

    private var counter = 0

    @Test
    fun `бюджет прохода — 200 мс, а не бюджет сторожа`() {
        // Раньше в движок уходил бюджет сторожа (250–1500 мс), и `ENGINE_BUDGET_EXCEEDED`
        // не наступал практически никогда: звонок доезжал до сторожа и получал более грубую
        // деградацию вместо аккуратного выхода с причиной.
        assertEquals(200L, ScreeningPipeline.engineBudgetMs(1_500))
        assertEquals(200L, ScreeningPipeline.engineBudgetMs(250))
        // На подходе к системному дедлайну бюджет сторожа меньше 200 мс — тогда он и главный.
        assertEquals(120L, ScreeningPipeline.engineBudgetMs(120))
    }

    @Test
    fun `сбой сборки фактов отличим от недоступного снимка`() {
        // Снимок при этом на месте. Раньше обе ветки давали `SNAPSHOT_UNAVAILABLE`,
        // и разбор жалобы уходил в неверную сторону.
        val store = SnapshotStore(temp.newFolder("s-facts-${counter++}"))
        store.write(SnapshotBuilder(RuFastPathNormalizer()).build(emptyList(), settings))
        // Сборку фактов роняем через нормализатор: подменить сам сборщик нельзя, он финальный,
        // а отказ нормализации — реальный путь этой ветки (чужой формат номера, сбой резерва).
        val p = ScreeningPipeline(
            snapshots = store,
            factsBuilder = CallFactsBuilder(
                object : PhoneNumberNormalizer {
                    override fun normalize(raw: String?, region: String): NumberForms =
                        throw IllegalStateException("нормализация отказала")
                },
                ContactMembership.NONE,
                EmergencyNumbers { false },
            ),
            directBoot = { false },
        )

        val out = p.decide(FakeCallDetails(), Budget.unlimited(), coldStart = false)
        assertEquals(CallAction.ALLOW, out.decision.action, "любой сбой — разрешить (§1.1)")
        assertEquals(DecisionReason.FACTS_FAILED, out.decision.reason)
        assertNull(out.facts)
    }

    @Test
    fun `исходящий звонок не проверяется правилами`() {
        // `CallScreeningService` вызывается и для исходящих. Без этой ветки блокирующее правило
        // совпало бы на номере, который пользователь набрал сам, — и звонок был бы отклонён
        // или в журнале появилась бы запись «отклонён» о звонке, который состоялся.
        val (p, _) = pipeline(
            listOf(BlockRule(1, "Москва", RuleTarget.NUMBER, MatchType.PREFIX, "8495",
                CallAction.REJECT, 600))
        )
        val out = p.decide(
            FakeCallDetails(handleValue = "+74951234567", callDirection = 1),
            Budget.unlimited(),
            coldStart = false,
        )
        assertEquals(CallAction.ALLOW, out.decision.action)
        assertEquals(DecisionReason.OUTGOING_CALL, out.decision.reason)
        assertNull(out.decision.matchedRuleId, "правила по исходящему не проверяются вовсе")
    }

    @Test
    fun `неизвестное направление считается входящим`() {
        // Часть прошивок Android 10 направления не отдаёт. Трактовать `null` как исходящий
        // означало бы отключить блокировку целиком — то есть починить одно, сломав главное.
        val (p, _) = pipeline(
            listOf(BlockRule(1, "Москва", RuleTarget.NUMBER, MatchType.PREFIX, "8495",
                CallAction.REJECT, 600))
        )
        val out = p.decide(
            FakeCallDetails(handleValue = "+74951234567", callDirection = null),
            Budget.unlimited(),
            coldStart = false,
        )
        assertEquals(CallAction.REJECT, out.decision.action)
    }

    @Test
    fun `входящий звонок проверяется как раньше`() {
        val (p, _) = pipeline(
            listOf(BlockRule(1, "Москва", RuleTarget.NUMBER, MatchType.PREFIX, "8495",
                CallAction.REJECT, 600))
        )
        val out = p.decide(
            FakeCallDetails(handleValue = "+74951234567", callDirection = 0),
            Budget.unlimited(),
            coldStart = false,
        )
        assertEquals(CallAction.REJECT, out.decision.action)
        assertEquals(DecisionReason.RULE_MATCH, out.decision.reason)
    }

    @Test
    fun `правило по номеру блокирует звонок`() {
        val (p, _) = pipeline(
            listOf(BlockRule(1, "Москва", RuleTarget.NUMBER, MatchType.PREFIX, "8495",
                CallAction.REJECT, 600))
        )
        val out = p.decide(FakeCallDetails(handleValue = "+74951234567"), Budget.unlimited(), coldStart = false)
        assertEquals(CallAction.REJECT, out.decision.action)
        assertEquals(DecisionReason.RULE_MATCH, out.decision.reason)
        assertEquals(1L, out.decision.matchedRuleId)
        assertNotNull(out.facts, "факты нужны журналу и режиму наблюдения после ответа")
    }

    @Test
    fun `правило по категории блокирует по операторской подписи`() {
        val (p, _) = pipeline(
            listOf(BlockRule(1, "реклама", RuleTarget.NAME_CATEGORY, MatchType.PREFIX, "reklam",
                CallAction.REJECT, 900))
        )
        val out = p.decide(
            FakeCallDetails(handleValue = "+79990000000", callerDisplayName = "OOO Romashka: reklama"),
            Budget.unlimited(), coldStart = false,
        )
        assertEquals(CallAction.REJECT, out.decision.action)
    }

    @Test
    fun `без совпадений звонок проходит`() {
        val (p, _) = pipeline(
            listOf(BlockRule(1, "Москва", RuleTarget.NUMBER, MatchType.PREFIX, "8495",
                CallAction.REJECT, 600))
        )
        val out = p.decide(FakeCallDetails(handleValue = "+79991234567"), Budget.unlimited(), coldStart = false)
        assertEquals(CallAction.ALLOW, out.decision.action)
        assertEquals(DecisionReason.DEFAULT_ACTION, out.decision.reason)
    }

    @Test
    fun `отсутствующий снимок разрешает звонок, а не роняет проверку`() {
        // Главный отказной путь: правил нет — блокировать нечем (ТЗ §1.1).
        val (p, store) = pipeline(emptyList(), writeSnapshot = false)
        assertFalse(store.exists())

        val out = p.decide(FakeCallDetails(), Budget.unlimited(), coldStart = true)
        assertEquals(CallAction.ALLOW, out.decision.action)
        assertEquals(DecisionReason.SNAPSHOT_UNAVAILABLE, out.decision.reason)
        assertTrue(out.decision.has(Degradation.COLD_START))
        assertNull(out.facts, "фактов нет: их не на чем было строить")
    }

    @Test
    fun `повреждённый снимок разрешает звонок`() {
        val (p, store) = pipeline(
            listOf(BlockRule(1, "всё", RuleTarget.NUMBER, MatchType.PREFIX, "7", CallAction.REJECT, 600))
        )
        val file = temp.root.walkTopDown().first { it.name == SnapshotStore.FILE_NAME }
        file.writeBytes("мусор".toByteArray())
        store.invalidate()

        val out = p.decide(FakeCallDetails(), Budget.unlimited(), coldStart = false)
        assertEquals(CallAction.ALLOW, out.decision.action)
        assertEquals(DecisionReason.SNAPSHOT_UNAVAILABLE, out.decision.reason)
    }

    @Test
    fun `Direct Boot помечается флагом, но решение принимается`() {
        // До первой разблокировки Room недоступен, а снимок — доступен: в этом весь смысл
        // держать его в Device Protected Storage (архитектура §5).
        val (p, _) = pipeline(
            listOf(BlockRule(1, "Москва", RuleTarget.NUMBER, MatchType.PREFIX, "8495",
                CallAction.REJECT, 600)),
            directBoot = true,
        )
        val out = p.decide(FakeCallDetails(handleValue = "+74951234567"), Budget.unlimited(), coldStart = true)
        assertEquals(CallAction.REJECT, out.decision.action)
        assertTrue(out.decision.has(Degradation.DIRECT_BOOT))
        assertTrue(out.decision.has(Degradation.COLD_START))
    }

    @Test
    fun `скрытый номер решается настройкой, а не правилами по номеру`() {
        val (p, _) = pipeline(
            listOf(BlockRule(1, "всё российское", RuleTarget.NUMBER, MatchType.PREFIX, "7",
                CallAction.REJECT, 600)),
            settings = settings.copy(restrictedAction = CallAction.REJECT),
        )
        val out = p.decide(
            FakeCallDetails(handleValue = null, handlePresentation = PRESENTATION_RESTRICTED),
            Budget.unlimited(), coldStart = false,
        )
        assertEquals(CallAction.REJECT, out.decision.action)
        assertEquals(DecisionReason.RESTRICTED_NUMBER, out.decision.reason)
    }

    @Test
    fun `экстренный номер проходит при любых правилах`() {
        val (p, _) = pipeline(
            listOf(BlockRule(1, "всё", RuleTarget.NUMBER, MatchType.PREFIX, "1", CallAction.REJECT, 600)),
            settings = settings.copy(defaultAction = CallAction.REJECT),
        )
        val out = p.decide(FakeCallDetails(handleValue = "112"), Budget.unlimited(), coldStart = false)
        assertEquals(CallAction.ALLOW, out.decision.action)
        assertEquals(DecisionReason.EMERGENCY, out.decision.reason)
    }

    @Test
    fun `правило по контактам разрешает звонок от контакта`() {
        val (p, _) = pipeline(
            listOf(
                BlockRule(1, "Разрешать контакты", RuleTarget.CONTACT, MatchType.IN_CONTACTS, "",
                    CallAction.ALLOW, 100),
                BlockRule(2, "всё российское", RuleTarget.NUMBER, MatchType.PREFIX, "7",
                    CallAction.REJECT, 600),
            ),
            contacts = ContactMembership { true },
        )
        val out = p.decide(FakeCallDetails(), Budget.unlimited(), coldStart = false)
        assertEquals(CallAction.ALLOW, out.decision.action)
        assertEquals(1L, out.decision.matchedRuleId)
    }

    @Test
    fun `недоступный индекс контактов помечает решение, а не меняет его`() {
        val (p, _) = pipeline(
            listOf(BlockRule(1, "Москва", RuleTarget.NUMBER, MatchType.PREFIX, "8495",
                CallAction.REJECT, 600)),
            contacts = ContactMembership.UNKNOWN,
        )
        val out = p.decide(FakeCallDetails(handleValue = "+74951234567"), Budget.unlimited(), coldStart = false)
        assertEquals(CallAction.REJECT, out.decision.action)
        assertTrue(out.decision.has(Degradation.CONTACT_INDEX_STALE))
    }
}
