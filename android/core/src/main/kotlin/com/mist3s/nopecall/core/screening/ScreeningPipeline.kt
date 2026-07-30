package com.mist3s.nopecall.core.screening

import com.mist3s.nopecall.core.facts.CallDetailsReader
import com.mist3s.nopecall.core.facts.CallFactsBuilder
import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.engine.Budget
import com.mist3s.nopecall.engine.CallFacts
import com.mist3s.nopecall.engine.Decision
import com.mist3s.nopecall.engine.DecisionReason
import com.mist3s.nopecall.engine.Degradation
import com.mist3s.nopecall.engine.RuleEngine

/**
 * Принятие решения по звонку, отделённое от Android-сервиса (архитектура §4).
 *
 * Отдельный класс, а не метод сервиса, по одной причине: так весь путь «данные системы → факты →
 * снимок → решение» проверяется обычными unit-тестами. В сервисе остаётся только то, что без
 * устройства не проверить: привязка Telecom, сторожевой таймер и отправка ответа.
 *
 * Здесь же живёт единая воронка отказов: **любой сбой означает разрешить звонок** и записать
 * причину (ТЗ §1.1). Наружу исключения не выходят.
 */
internal class ScreeningPipeline(
    private val snapshots: SnapshotStore,
    private val factsBuilder: CallFactsBuilder,
    private val directBoot: () -> Boolean = { false },
) {
    /** Результат вместе с фактами: факты нужны журналу и режиму наблюдения после ответа. */
    data class Outcome(val decision: Decision, val facts: CallFacts?)

    fun decide(details: CallDetailsReader, budget: Budget, coldStart: Boolean): Outcome {
        var flags = 0
        if (coldStart) flags = flags or Degradation.COLD_START.bit
        if (directBoot()) flags = flags or Degradation.DIRECT_BOOT.bit

        val snapshot = snapshots.current()
        if (snapshot == null) {
            // Снимка нет или он повреждён. Блокировать нечем — разрешаем и записываем причину.
            // Пересборку ставит в очередь вызывающий: править снимок из горячего пути нельзя.
            return Outcome(
                Decision.allow(DecisionReason.SNAPSHOT_UNAVAILABLE).withDegradations(flags),
                facts = null,
            )
        }

        val facts = try {
            factsBuilder.build(details, snapshot.settings)
        } catch (t: Throwable) {
            return Outcome(
                Decision.allow(DecisionReason.SNAPSHOT_UNAVAILABLE).withDegradations(flags),
                facts = null,
            )
        }

        val decision = try {
            RuleEngine.decide(facts, snapshot, budget)
        } catch (t: Throwable) {
            // Движок не должен бросать наружу; если всё же бросил — разрешаем.
            Decision.allow(DecisionReason.SNAPSHOT_UNAVAILABLE)
        }

        return Outcome(decision.withDegradations(flags), facts)
    }
}

/** Добавляет уже собранную маску деградаций, не теряя выставленные движком. */
internal fun Decision.withDegradations(mask: Int): Decision =
    if (mask == 0) this else copy(degradations = degradations or mask)
