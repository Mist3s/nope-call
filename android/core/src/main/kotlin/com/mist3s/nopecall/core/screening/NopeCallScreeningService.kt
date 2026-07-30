package com.mist3s.nopecall.core.screening

import android.os.UserManager
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.mist3s.nopecall.core.CoreGraph
import com.mist3s.nopecall.core.facts.TelecomCallDetails
import com.mist3s.nopecall.core.storage.ScreeningRecord
import com.mist3s.nopecall.engine.Budget
import com.mist3s.nopecall.engine.Decision
import com.mist3s.nopecall.engine.DecisionReason
import com.mist3s.nopecall.engine.Degradation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Проверка входящего звонка (архитектура §4).
 *
 * Сервис содержит только то, что нельзя проверить без устройства: привязку Telecom, сторожевой
 * таймер и отправку ответа. Само решение принимает [ScreeningPipeline], и потому весь путь
 * «данные системы → факты → снимок → решение» покрыт обычными unit-тестами.
 *
 * Три свойства, которые здесь важнее самой блокировки:
 *
 *  1. **Состояние ответа принадлежит звонку, а не сервису.** Telecom может отдать через один
 *     экземпляр несколько звонков (ожидание вызова, две SIM) — поэтому `respondToCall`
 *     и принимает `details`. Флаг «уже ответили» на уровне сервиса означал бы, что второй
 *     звонок не получит ответа вообще и провиснет до системного дедлайна.
 *  2. **Дедлайн отсчитывается от создания звонка**, а не от входа в метод: системные 5 секунд
 *     включают холодный старт процесса и время доставки колбэка.
 *  3. **Ответ отправляется ровно один раз** — сторож и движок соревнуются за это право.
 */
internal class NopeCallScreeningService : CallScreeningService() {

    private val sessions = ConcurrentHashMap<Call.Details, CallSession>()

    override fun onScreenCall(callDetails: Call.Details) {
        val startedAt = System.nanoTime()
        val coldStart = FIRST_CALL.compareAndSet(true, false)

        val session = CallSession(callDetails, budgetMs = budgetFor(callDetails))
        sessions[callDetails] = session

        // Сторож на отдельном потоке: если движок встанет (катастрофический regex, залипший
        // провайдер), платформенный поток занят и сам себя не спасёт — отвечает сторож.
        session.watchdog = WATCHDOG.schedule(
            { answerOnce(session, Decision.allow(DecisionReason.WATCHDOG_ANSWERED, Degradation.WATCHDOG_ANSWERED)) },
            session.budgetMs,
            TimeUnit.MILLISECONDS,
        )

        val outcome = try {
            pipeline().decide(
                details = TelecomCallDetails(callDetails),
                budget = Budget.wallClock(
                    totalNanos = session.budgetMs * 1_000_000,
                    perRuleNanos = PER_RULE_BUDGET_NANOS,
                ),
                coldStart = coldStart,
            )
        } catch (t: Throwable) {
            // Единая воронка отказов: любой сбой означает разрешить звонок (ТЗ §1.1).
            Log.w(TAG, "сбой при принятии решения, звонок разрешён", t)
            ScreeningPipeline.Outcome(Decision.allow(DecisionReason.SNAPSHOT_UNAVAILABLE), facts = null)
        }

        val decision = outcome.decision.copy(elapsedNanos = System.nanoTime() - startedAt)
        answerOnce(session, decision)

        // Ответ отправлен — задержки звонка больше не существует по определению (архитектура §4.6).
        //
        // Синхронная допись одной строки, НЕ выходя из onScreenCall. Это не оптимизация:
        // сразу после ответа Telecom отвязывается, и при закрытом интерфейсе процесс становится
        // кэшированным. На прошивках, агрессивно завершающих процессы, асинхронная запись
        // систематически теряла бы именно те события, ради которых существует режим наблюдения.
        try {
            CoreGraph.eventSpool.append(
                ScreeningRecord(
                    occurredAt = System.currentTimeMillis(),
                    facts = outcome.facts,
                    decision = decision,
                    matchedRuleTitle = null,
                    budgetMs = session.budgetMs.toInt(),
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "не удалось записать событие", t)
        }

        // Уведомление — только о фактической блокировке: сообщать «звонок прошёл» незачем.
        if (decision.action.blocks) {
            runCatching {
                CoreGraph.notifier.notifyBlocked(
                    who = outcome.facts?.name?.whole?.raw?.takeIf { it.isNotEmpty() }
                        ?: outcome.facts?.number?.raw.orEmpty(),
                    ruleId = decision.matchedRuleId,
                    ruleTitle = null,
                )
            }
        }

        // Room и сегменты режима наблюдения — асинхронно, вне этого метода.
        CoreGraph.onEventRecorded()
    }

    private fun pipeline(): ScreeningPipeline = ScreeningPipeline(
        snapshots = CoreGraph.snapshots,
        factsBuilder = CoreGraph.callFactsBuilder,
        directBoot = { getSystemService(UserManager::class.java)?.isUserUnlocked == false },
    )

    /**
     * Бюджет на решение, отсчитанный от момента создания звонка в Telecom.
     *
     * `creationTimeMillis` — та точка, от которой система считает свой дедлайн. К моменту
     * входа сюда часть его уже израсходована холодным стартом; насколько большая — величина
     * не ограниченная сверху, поэтому её надо вычитать, а не игнорировать (архитектура §4.3).
     */
    private fun budgetFor(details: Call.Details): Long {
        val elapsed = (System.currentTimeMillis() - details.creationTimeMillis).coerceAtLeast(0)
        return (SYSTEM_DEADLINE_MS - SAFETY_MS - elapsed)
            .coerceAtMost(SELF_BUDGET_MS)
            .coerceAtLeast(MIN_BUDGET_MS)
    }

    private fun answerOnce(session: CallSession, decision: Decision) {
        if (!session.answered.compareAndSet(false, true)) return
        session.watchdog?.cancel(false)
        sessions.remove(session.details)
        try {
            respondToCall(session.details, decision.toCallResponse())
        } catch (t: Throwable) {
            // Ответить уже нечем: система либо получила ответ, либо истёк её дедлайн.
            Log.w(TAG, "не удалось ответить системе", t)
        }
        Log.d(
            TAG,
            "решение ${decision.action} (${decision.reason}) за ${decision.elapsedNanos / 1_000_000} мс, " +
                "бюджет был ${session.budgetMs} мс, деградации ${Degradation.describe(decision.degradations)}",
        )
    }

    private companion object {
        const val TAG = "NopeCallScreening"

        /** Значение по умолчанию у Telecom; переопределяется прошивкой (ТЗ §21 п. 2). */
        const val SYSTEM_DEADLINE_MS = 5_000L

        /** Запас на дорогу ответа до Telecom. */
        const val SAFETY_MS = 500L

        /** Свой предел: дольше держать звонок незачем даже при большом остатке. */
        const val SELF_BUDGET_MS = 1_500L

        /** Лучше плохой ответ, чем никакой. */
        const val MIN_BUDGET_MS = 250L

        /** Бюджет на одно regex-правило, ТЗ §6.5. */
        const val PER_RULE_BUDGET_NANOS = 10_000_000L

        val WATCHDOG = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "nope-call-watchdog").apply { isDaemon = true }
        }

        /** Первый звонок после старта процесса — для флага COLD_START в диагностике. */
        val FIRST_CALL = AtomicBoolean(true)
    }
}

/**
 * Состояние одной проверки. Живёт столько, сколько идёт решение по конкретному звонку.
 */
internal class CallSession(
    val details: Call.Details,
    val budgetMs: Long,
) {
    val answered = AtomicBoolean(false)

    @Volatile
    var watchdog: ScheduledFuture<*>? = null
}
