package com.mist3s.nopecall.core.screening

import android.os.UserManager
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.mist3s.nopecall.core.CoreGraph
import com.mist3s.nopecall.core.facts.TelecomCallDetails
import com.mist3s.nopecall.core.observe.CallObservation
import com.mist3s.nopecall.core.observe.NetworkContext
import com.mist3s.nopecall.core.storage.ScreeningDiagnostics
import com.mist3s.nopecall.core.storage.ScreeningRecord
import com.mist3s.nopecall.engine.Budget
import com.mist3s.nopecall.engine.Decision
import com.mist3s.nopecall.engine.DecisionReason
import com.mist3s.nopecall.engine.Degradation
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

    // Реестра сессий здесь нет сознательно.
    //
    // Архитектура §4.2 приводила `ConcurrentHashMap<Call.Details, CallSession>`, и он был
    // написан — но не читался ни разу: состояние ответа и так принадлежит звонку, потому что
    // `CallSession` передаётся и сторожу, и `answerOnce`. Карта, которую никто не читает, —
    // это не подстраховка, а вид подстраховки; хуже, она обещала «вытеснение по возрасту»,
    // которого не было. Требование §4.2 держится на том, что флаг «уже ответили» лежит
    // в сессии звонка, а не на сервисе.

    override fun onScreenCall(callDetails: Call.Details) {
        val startedAt = System.nanoTime()
        val coldStart = FIRST_CALL.compareAndSet(true, false)

        val observationEnabled = runCatching { CoreGraph.observationStore.config().enabled }
            .getOrDefault(false)

        val session = CallSession(callDetails, budgetMs = budgetFor(callDetails))

        // Сторож на отдельном потоке: если движок встанет (катастрофический regex, залипший
        // провайдер), платформенный поток занят и сам себя не спасёт — отвечает сторож.
        session.watchdog = WATCHDOG.schedule(
            { answerOnce(session, Decision.allow(DecisionReason.WATCHDOG_ANSWERED, Degradation.WATCHDOG_ANSWERED)) },
            session.budgetMs,
            TimeUnit.MILLISECONDS,
        )

        val reader = TelecomCallDetails(callDetails)
        val outcome = try {
            pipeline().decide(
                details = reader,
                budget = Budget.wallClock(
                    // Бюджет прохода — из §4.5, а не бюджет сторожа. Раньше сюда уходило
                    // значение сторожа (250–1500 мс), и `ENGINE_BUDGET_EXCEEDED` не наступал
                    // практически никогда: вместо аккуратного выхода с причиной звонок доезжал
                    // до сторожа и получал более грубую деградацию. Минимум со сторожем нужен,
                    // потому что на подходе к системному дедлайну бюджет сторожа меньше 200 мс.
                    totalNanos = ScreeningPipeline.engineBudgetMs(session.budgetMs) * 1_000_000,
                    perRuleNanos = PER_RULE_BUDGET_NANOS,
                ),
                coldStart = coldStart,
                // Список проверенных правил нужен режиму наблюдения: без него на вопрос
                // «почему не сработало» ответить нечем (ТЗ §7.7.1).
                collectTrace = observationEnabled,
            )
        } catch (t: Throwable) {
            // Единая воронка отказов: любой сбой означает разрешить звонок (ТЗ §1.1).
            //
            // Деградации собираются здесь же: `Outcome` строится в обход конвейера, и без них
            // запись о самом тяжёлом отказе теряла бы ровно те два флага, которые его объясняют.
            Log.w(TAG, "сбой при принятии решения, звонок разрешён", t)
            var flags = 0
            if (coldStart) flags = flags or Degradation.COLD_START.bit
            if (getSystemService(UserManager::class.java)?.isUserUnlocked == false) {
                flags = flags or Degradation.DIRECT_BOOT.bit
            }
            ScreeningPipeline.Outcome(
                Decision.allow(DecisionReason.ENGINE_FAILED).withDegradations(flags),
                facts = null,
            )
        }

        val decision = outcome.decision.copy(elapsedNanos = System.nanoTime() - startedAt)
        val directBoot = getSystemService(UserManager::class.java)?.isUserUnlocked == false
        answerOnce(session, decision)

        // Ответ отправлен — задержки звонка больше не существует по определению (архитектура §4.6).
        //
        // Синхронная допись одной строки, НЕ выходя из onScreenCall. Это не оптимизация:
        // сразу после ответа Telecom отвязывается, и при закрытом интерфейсе процесс становится
        // кэшированным. На прошивках, агрессивно завершающих процессы, асинхронная запись
        // систематически теряла бы именно те события, ради которых существует режим наблюдения.
        val occurredAt = System.currentTimeMillis()
        // Контекст сети собирается ЗДЕСЬ, а не до решения: от сети решение не зависит,
        // а вызовы TelephonyManager на холодном старте стоят миллисекунды бюджета звонка.
        // При этом наличие операторской подписи от сети и VoLTE зависит прямо (ТЗ §6.3.1),
        // и без этого контекста ответить «почему подписи не было» нечем.
        val extras = runCatching { reader.extrasDump() }.getOrDefault(emptyList())
        val intentExtras = runCatching { reader.intentExtrasDump() }.getOrDefault(emptyList())
        val network = runCatching { CoreGraph.networkContext.read() }
            .getOrDefault(NetworkContext.UNKNOWN)
        val diagnostics = ScreeningDiagnostics(
            coldStart = coldStart,
            directBoot = directBoot,
            networkType = network.networkType,
            volte = network.volte,
            operatorName = network.operatorName,
            roaming = network.roaming,
            extrasKeys = extras.map { it.key } + intentExtras.map { it.key },
            verificationStatus = runCatching { reader.verificationStatus }.getOrNull(),
        )

        // Исходящий звонок в журнал проверок не пишется.
        //
        // Иначе он попал бы и в «Проверок за период», и в знаменатели показателей подписи,
        // и в перцентили задержки — то есть измерения оказались бы про звонки, к которым
        // приложение отношения не имеет. Плюс в журнале появилась бы вторая запись об одном
        // звонке: строку зеркала об исходящем сшивка не берёт по направлению. Сам факт вызова
        // сервиса на исходящем виден в техническом логе — там он и нужен.
        val outgoing = decision.reason == DecisionReason.OUTGOING_CALL
        if (!outgoing) {
            try {
                CoreGraph.eventSpool.append(
                    ScreeningRecord(
                        occurredAt = occurredAt,
                        facts = outcome.facts,
                        decision = decision,
                        matchedRuleTitle = null,
                        budgetMs = session.budgetMs.toInt(),
                        diagnostics = diagnostics,
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "не удалось записать событие", t)
            }
        }

        // Режим наблюдения (ТЗ §7.7.1). Тоже синхронно и по той же причине: сразу после ответа
        // Telecom отвязывается, процесс становится кэшированным, и отложенная запись
        // систематически теряла бы именно те события, ради которых режим существует.
        //
        // Исходящие пишутся и здесь: направление в записи есть (`call_direction`), а сам факт
        // вызова сервиса на исходящем звонке — наблюдение, ради которого режим и существует.
        // В показатели он не идёт: они считаются по журналу проверок, куда исходящий не попал.
        try {
            CoreGraph.observation.observeCall(
                CallObservation(
                    at = occurredAt,
                    handleScheme = reader.handleScheme,
                    handleValue = reader.handleValue,
                    handlePresentation = reader.handlePresentation,
                    // Подпись — дословно, без канонизации: предмет исследования именно сырой вид.
                    displayNameRaw = reader.callerDisplayName,
                    displayNamePresentation = reader.callerDisplayNamePresentation,
                    verificationStatus = diagnostics.verificationStatus,
                    creationTimeMillis = callDetails.creationTimeMillis,
                    callDirection = reader.callDirection,
                    connectTimeMillis = reader.connectTimeMillis,
                    accountHandle = reader.accountHandle,
                    extras = extras,
                    intentExtras = intentExtras,
                    digits = outcome.facts?.number?.let { it.canonicalDigits.ifEmpty { it.digits } },
                    e164 = outcome.facts?.number?.e164,
                    nameNorm = outcome.facts?.name?.whole?.norm,
                    nameTokens = outcome.facts?.name?.whole?.tokens?.joinToString(" "),
                    nameFold = outcome.facts?.name?.whole?.fold,
                    orgFold = outcome.facts?.name?.org?.fold,
                    categoryFold = outcome.facts?.name?.category?.fold,
                    nameSource = outcome.facts?.nameSource?.name,
                    inContacts = outcome.facts?.inContacts,
                    action = decision.action.name,
                    reason = decision.reason.name,
                    degradations = decision.degradations,
                    matchedRuleId = decision.matchedRuleId,
                    checkedRuleIds = outcome.checkedRuleIds,
                    checkedTruncated = outcome.checkedTruncated,
                    latencyMs = (decision.elapsedNanos / 1_000_000).toInt(),
                    budgetMs = session.budgetMs.toInt(),
                    coldStart = coldStart,
                    directBoot = directBoot,
                    watchdogFired = decision.reason == DecisionReason.WATCHDOG_ANSWERED,
                    network = network,
                    device = CoreGraph.deviceContext,
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "не удалось записать наблюдение", t)
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
