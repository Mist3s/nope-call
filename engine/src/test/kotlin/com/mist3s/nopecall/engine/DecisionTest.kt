package com.mist3s.nopecall.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты контракта решения (ТЗ §1.1, §5.2, архитектура §6.7).
 *
 * Проверяется не «работает ли код», а сохраняются ли свойства, из которых выведена
 * вся остальная архитектура: единая воронка отказов и совместимость деградаций.
 */
class DecisionTest {

    @Test
    fun `любой отказ разрешает звонок`() {
        val failures = listOf(
            DecisionReason.ENGINE_BUDGET_EXCEEDED,
            DecisionReason.SNAPSHOT_UNAVAILABLE,
            DecisionReason.WATCHDOG_ANSWERED,
        )
        for (reason in failures) {
            val decision = Decision.allow(reason)
            assertEquals(CallAction.ALLOW, decision.action, "отказ $reason обязан разрешать звонок")
            assertFalse(decision.action.blocks)
        }
    }

    @Test
    fun `деградации совмещаются и не затирают друг друга`() {
        // Реальный случай: правило сработало, но имя было недоступно, и это уже второй
        // холодный старт после смерти процесса. Одно поле reason такое не выражает.
        val decision = Decision(CallAction.REJECT, DecisionReason.RULE_MATCH, matchedRuleId = 7)
            .withDegradation(Degradation.NAME_UNAVAILABLE)
            .withDegradation(Degradation.COLD_START)

        assertTrue(decision.has(Degradation.NAME_UNAVAILABLE))
        assertTrue(decision.has(Degradation.COLD_START))
        assertFalse(decision.has(Degradation.RULE_SKIPPED))
        assertEquals(
            listOf(Degradation.NAME_UNAVAILABLE, Degradation.COLD_START),
            Degradation.describe(decision.degradations),
        )
    }

    @Test
    fun `блокирующими считаются только отклонение и тихий сброс`() {
        // «Без звука» звонок не блокирует: он доходит, и на него можно ответить (ТЗ §5.2).
        assertTrue(CallAction.REJECT.blocks)
        assertTrue(CallAction.DROP.blocks)
        assertFalse(CallAction.SILENCE.blocks)
        assertFalse(CallAction.ALLOW.blocks)
    }

    @Test
    fun `у каждой деградации свой бит`() {
        val bits = Degradation.entries.map { it.bit }
        assertEquals(bits.size, bits.distinct().size, "биты деградаций пересекаются")
        assertEquals(0, bits.fold(0) { acc, b -> acc and b }, "биты не должны накладываться")
    }
}
