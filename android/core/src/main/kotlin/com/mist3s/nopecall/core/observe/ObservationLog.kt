package com.mist3s.nopecall.core.observe

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Режим наблюдения: два независимых потока с разными объёмами и сроками (ТЗ §7.7.1).
 *
 * **Поток A — события звонков.** Пишется синхронно, сразу после ответа системе, не выходя
 * из `onScreenCall`. Это не оптимизация наоборот: после ответа Telecom отвязывается, и при
 * закрытом интерфейсе процесс становится кэшированным. На прошивках, агрессивно завершающих
 * процессы, отложенная запись систематически теряла бы именно те события, ради которых режим
 * существует. Задержки звонка при этом нет по определению — ответ уже отправлен.
 *
 * **Поток B — технический лог.** Через ограниченную очередь и отдельный поток: он пишется
 * часто, из любого места, и его потеря ничего не стоит. Очередь ограничена намеренно —
 * переполнение отбрасывает записи и **никогда** не блокирует вызывающего.
 */
public class ObservationLog(
    dir: File,
    private val configProvider: () -> ObservationConfig,
    private val now: () -> Long = System::currentTimeMillis,
    freeSpace: () -> Long = { dir.usableSpace },
) {

    private val calls = SegmentStore(
        dir = File(dir, DIR_CALLS),
        prefix = PREFIX_CALLS,
        extension = EXT_CALLS,
        now = now,
        freeSpace = freeSpace,
    )

    private val tech = SegmentStore(
        dir = File(dir, DIR_TECH),
        prefix = PREFIX_TECH,
        extension = EXT_TECH,
        now = now,
        freeSpace = freeSpace,
    )

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private val dropped = AtomicLong(0)

    @Volatile
    private var writer: Thread? = null

    /**
     * Событие звонка (поток A). Синхронно и без исключений.
     *
     * @return `true`, если строка записана; `false` — режим выключен или сработал предохранитель
     */
    public fun observeCall(observation: CallObservation): Boolean {
        val config = config() ?: return false
        if (!config.enabled) return false
        return calls.append(observation.toJsonLine(), config.callsLimits)
    }

    /**
     * Запись «название стало известно позже» (ТЗ §7.7.1).
     *
     * Прямое доказательство того, приходит подпись к моменту проверки или досылается, — то есть
     * ответ на главный вопрос §21 п. 4. Поэтому это отдельная связанная запись, а не правка
     * исходной: исходная должна остаться в том виде, в каком мы её тогда увидели.
     */
    public fun observeLateName(occurredAt: Long, digits: String, nameRaw: String, nameFold: String) {
        val config = config() ?: return
        if (!config.enabled) return
        val line = Json.line {
            put("at", now())
            put("kind", "late_name")
            put("event_at", occurredAt)
            put("number", digits)
            put("display_name", nameRaw)
            putObject("canon") { put("name_fold", nameFold) }
        }
        calls.append(line, config.callsLimits)
    }

    /** Технический лог (поток B). Не блокирует: при переполнении запись отбрасывается. */
    public fun tech(level: String, message: String) {
        val config = config() ?: return
        if (!config.enabled || !config.techEnabled) return
        if (level == LEVEL_TRACE && !config.techVerbose) return

        val line = "${now()}\t$level\t${message.replace('\n', ' ')}"
        if (!queue.offer(line)) {
            dropped.incrementAndGet()
            return
        }
        ensureWriter()
    }

    /** Сколько записей потока B потеряно из-за переполнения. Показывается на экране режима. */
    public fun droppedTechLines(): Long = dropped.get()

    public fun stats(): Stats {
        val callsStats = calls.stats()
        val techStats = tech.stats()
        val days = callsStats.oldestAt?.let {
            ((now() - it) / SegmentStore.DAY_MS).toInt().coerceAtLeast(1)
        } ?: 0
        return Stats(
            callsBytes = callsStats.bytes,
            techBytes = techStats.bytes,
            callsSegments = callsStats.segments,
            techSegments = techStats.segments,
            oldestAt = callsStats.oldestAt,
            // Оценка прироста в сутки (§7.7.2): без неё «100 МБ» ни о чём не говорит.
            dailyBytesEstimate = if (days > 0) callsStats.bytes / days else callsStats.bytes,
        )
    }

    public data class Stats(
        val callsBytes: Long,
        val techBytes: Long,
        val callsSegments: Int,
        val techSegments: Int,
        val oldestAt: Long?,
        val dailyBytesEstimate: Long,
    ) {
        val totalBytes: Long get() = callsBytes + techBytes
    }

    /**
     * Удаляет накопленное. Только по явной команде: выключение режима **не** удаляет данные
     * автоматически — как раз их обычно и собирались отправить (ТЗ §7.7.2).
     */
    public fun deleteAll(): Int = calls.deleteAll() + tech.deleteAll()

    internal fun callSegments(fromAt: Long, toAt: Long) = calls.segmentsIn(fromAt, toAt)
    internal fun techSegments(fromAt: Long, toAt: Long) = tech.segmentsIn(fromAt, toAt)

    private fun config(): ObservationConfig? = runCatching { configProvider() }.getOrNull()

    /**
     * Поток-писатель создаётся лениво и только один раз.
     *
     * Демон: он не должен мешать процессу завершиться. Потеря нескольких строк технического
     * лога при завершении процесса допустима, а вот удержание процесса живым — нет.
     */
    private fun ensureWriter() {
        if (writer != null) return
        synchronized(this) {
            if (writer != null) return
            writer = Thread({
                while (true) {
                    val line = try {
                        queue.take()
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    val config = config() ?: continue
                    tech.append(line, config.techLimits)
                }
            }, "nope-call-observe").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    public companion object {
        public const val LEVEL_TRACE: String = "TRACE"
        public const val LEVEL_INFO: String = "INFO"
        public const val LEVEL_WARN: String = "WARN"

        internal const val DIR_CALLS = "calls"
        internal const val DIR_TECH = "tech"
        internal const val PREFIX_CALLS = "calls"
        internal const val PREFIX_TECH = "tech"
        internal const val EXT_CALLS = "jsonl"
        internal const val EXT_TECH = "log"

        /**
         * Ёмкость очереди потока B. Небольшая сознательно: технический лог обязан быть
         * дешёвым, а не полным. При переполнении записи отбрасываются, и это видно счётчиком.
         */
        private const val QUEUE_CAPACITY = 512
    }
}
