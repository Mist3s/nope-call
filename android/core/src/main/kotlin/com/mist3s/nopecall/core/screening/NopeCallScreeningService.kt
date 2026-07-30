package com.mist3s.nopecall.core.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
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
 * Текущее состояние: сервис отвечает `ALLOW` на всё и фиксирует тайминги. Это не заглушка,
 * а корректное поведение первого шага — по ТЗ §1.1 приложение блокирует только при
 * совпадении явного правила, а правил и снимка ещё нет. Подключение движка — следующий шаг.
 *
 * Три свойства, которые здесь важнее самой блокировки и потому реализованы сразу:
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

        val decision = try {
            decide(callDetails)
        } catch (t: Throwable) {
            // Единая воронка отказов: любой сбой означает разрешить звонок (ТЗ §1.1).
            Log.w(TAG, "сбой при принятии решения, звонок разрешён", t)
            Decision.allow(DecisionReason.SNAPSHOT_UNAVAILABLE)
        }.let { if (coldStart) it.withDegradation(Degradation.COLD_START) else it }

        answerOnce(session, decision.copy(elapsedNanos = System.nanoTime() - startedAt))
    }

    /**
     * Пока движок не подключён — всегда разрешаем. Причина именно `DEFAULT_ACTION`:
     * проход по правилам завершён честно, просто правил нет.
     */
    private fun decide(details: Call.Details): Decision {
        Log.d(TAG, "проверка: presentation=${details.handlePresentation} handle=${details.handle != null}")
        return Decision.allow(DecisionReason.DEFAULT_ACTION)
    }

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
