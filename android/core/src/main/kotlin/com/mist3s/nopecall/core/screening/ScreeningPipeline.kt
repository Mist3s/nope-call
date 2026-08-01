package com.mist3s.nopecall.core.screening

import com.mist3s.nopecall.core.facts.CallDetailsReader
import com.mist3s.nopecall.core.facts.CallFactsBuilder
import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.engine.Budget
import com.mist3s.nopecall.engine.CallFacts
import com.mist3s.nopecall.engine.Decision
import com.mist3s.nopecall.engine.DecisionReason
import com.mist3s.nopecall.engine.DecisionTrace
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
    data class Outcome(
        val decision: Decision,
        val facts: CallFacts?,
        /** Правила, до которых дошёл проход. Пусто, если трассу не запрашивали. */
        val checkedRuleIds: List<Long> = emptyList(),
        val checkedTruncated: Boolean = false,
    )

    fun decide(
        details: CallDetailsReader,
        budget: Budget,
        coldStart: Boolean,
        /**
         * Собирать список проверенных правил (ТЗ §7.7.1).
         *
         * Флагом, а не всегда: трасса собирается внутри прохода по правилам и потому тратит
         * бюджет решения. При включённом режиме наблюдения эта плата оправдана — без списка
         * проверенных правил на вопрос «почему не сработало» ответить нечем.
         */
        collectTrace: Boolean = false,
    ): Outcome {
        var flags = 0
        if (coldStart) flags = flags or Degradation.COLD_START.bit
        if (directBoot()) flags = flags or Degradation.DIRECT_BOOT.bit

        // Исходящий звонок — до всего остального, даже до снимка правил.
        //
        // `CallScreeningService` вызывается и для исходящих: платформа отдаёт их тому же
        // сервису, а `Call.Details.getCallDirection()` существует именно чтобы их различать.
        // Без этой ветки блокирующее правило совпало бы на номере, который пользователь набрал
        // сам, — и в журнале появилась бы запись «отклонён» о звонке, который он сделал.
        // Приложение блокирует входящие; набранный номер не его дело (ТЗ §1.1).
        //
        // `null` направления (часть прошивок Android 10) трактуется как входящий: иначе
        // неизвестное направление отключало бы блокировку целиком.
        if (details.callDirection == CALL_DIRECTION_OUTGOING) {
            return Outcome(
                Decision.allow(DecisionReason.OUTGOING_CALL).withDegradations(flags),
                facts = null,
            )
        }

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
                Decision.allow(DecisionReason.FACTS_FAILED).withDegradations(flags),
                facts = null,
            )
        }

        val trace = if (collectTrace) DecisionTrace(TRACE_LIMIT) else null
        val decision = try {
            RuleEngine.decide(facts, snapshot, budget, trace)
        } catch (t: Throwable) {
            // Движок не должен бросать наружу; если всё же бросил — разрешаем.
            Decision.allow(DecisionReason.ENGINE_FAILED)
        }

        return Outcome(
            decision = decision.withDegradations(flags),
            facts = facts,
            checkedRuleIds = trace?.steps?.map { it.rule.id }.orEmpty(),
            checkedTruncated = trace?.truncated == true,
        )
    }

    internal companion object {
        /**
         * Предел трассы. При промахе по всем правилам их могут быть тысячи, и складывать
         * каждое в список — плата за диагностику из бюджета звонка.
         */
        const val TRACE_LIMIT = 200

        /**
         * `Call.Details.DIRECTION_OUTGOING`. Числом, а не ссылкой на константу Telecom:
         * значение зафиксировано платформой, а горячий путь не должен зависеть от классов,
         * которые до разблокировки экрана могут быть недоступны.
         */
        const val CALL_DIRECTION_OUTGOING = 1

        /** Бюджет прохода по правилам, архитектура §4.5. */
        const val ENGINE_BUDGET_MS = 200L

        /**
         * Бюджет, с которым движок идёт по правилам.
         *
         * Это **не** бюджет сторожа: сторож — последний рубеж, а проход обязан выйти сам,
         * аккуратно и с причиной `ENGINE_BUDGET_EXCEEDED`, чтобы событие и наблюдение
         * записались нормально. Минимум нужен на подходе к системному дедлайну: там бюджет
         * сторожа уже меньше 200 мс, и брать 200 значило бы отдать решение сторожу.
         *
         * Вынесено в конвейер, а не оставлено в сервисе, ровно чтобы проверяться тестом:
         * на самом сервисе тестов нет — он привязан к Telecom.
         */
        fun engineBudgetMs(watchdogBudgetMs: Long): Long =
            minOf(ENGINE_BUDGET_MS, watchdogBudgetMs)
    }
}

/** Добавляет уже собранную маску деградаций, не теряя выставленные движком. */
internal fun Decision.withDegradations(mask: Int): Decision =
    if (mask == 0) this else copy(degradations = degradations or mask)
