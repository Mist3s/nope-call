package com.mist3s.nopecall.core

import android.app.Application
import android.content.Context
import android.os.UserManager
import android.system.Os
import android.util.Log
import com.mist3s.nopecall.core.calllog.AndroidCallLogSource
import com.mist3s.nopecall.core.calllog.CallLogSyncer
import com.mist3s.nopecall.core.contacts.AndroidContactNumberSource
import com.mist3s.nopecall.core.contacts.ContactIndex
import com.mist3s.nopecall.core.contacts.ContactNumberSource
import com.mist3s.nopecall.core.diag.DiagnosticsRepository
import com.mist3s.nopecall.core.facts.CallFactsBuilder
import com.mist3s.nopecall.core.facts.ContactMembership
import com.mist3s.nopecall.core.facts.EmergencyNumbers
import com.mist3s.nopecall.core.notify.BlockedCallNotifier
import com.mist3s.nopecall.core.notify.NotifyStore
import com.mist3s.nopecall.core.observe.AndroidNetworkContext
import com.mist3s.nopecall.core.observe.DeviceContext
import com.mist3s.nopecall.core.observe.LogExporter
import com.mist3s.nopecall.core.observe.ObservationLog
import com.mist3s.nopecall.core.observe.ObservationReporter
import com.mist3s.nopecall.core.observe.ObservationStore
import com.mist3s.nopecall.core.role.RoleController
import com.mist3s.nopecall.core.sim.AndroidSimLabels
import com.mist3s.nopecall.core.sim.SimLabels
import com.mist3s.nopecall.core.snapshot.DirectorySync
import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.core.storage.EventRecorder
import com.mist3s.nopecall.core.storage.JournalCsv
import com.mist3s.nopecall.core.storage.JournalRepository
import com.mist3s.nopecall.core.storage.RulesTransfer
import com.mist3s.nopecall.core.storage.EventSpool
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.core.storage.RulesRepository
import com.mist3s.nopecall.engine.PhoneNumberNormalizer
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import java.io.File

/**
 * Двухфазная инициализация (архитектура §3.1).
 *
 * `onCreate` выполняется перед ЛЮБОЙ точкой входа, включая сервис проверки, и в том числе
 * до первой разблокировки экрана. Обычная инициализация Flutter-приложения в этот момент
 * либо упадёт на недоступном хранилище, либо съест бюджет звонка — поэтому она разделена.
 *
 * Фаза 1 обязана быть дешёвой и безопасной в Direct Boot: никакого Room, никаких
 * `SharedPreferences` из CE-хранилища, никакого FlutterEngine, никаких обращений
 * к `ContentProvider`. Целевая стоимость — единицы миллисекунд.
 */
public class NopeCallApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val startedAt = System.nanoTime()

        CoreGraph.initDeviceEncrypted(createDeviceProtectedStorageContext())

        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        Log.d(TAG, "фаза 1 за ${(System.nanoTime() - startedAt) / 1_000} мкс, разблокировано=$unlocked")

        if (unlocked) initUnlocked()
    }

    /**
     * Фаза 2: Room, прогрев нормализатора номеров, синхронизация зеркала журнала,
     * планировщик, индекс контактов, слив очереди событий из Direct Boot.
     *
     * Идемпотентна: вызывается отсюда, из `BOOT_COMPLETED`, при запуске UI и из любого
     * воркера. На `ACTION_USER_UNLOCKED` полагаться нельзя — неявные рассылки из манифеста
     * ограничены с Android 8, поэтому он регистрируется в рантайме только как ускоритель.
     */
    public fun initUnlocked() {
        if (!CoreGraph.markUnlockedInitStarted()) return
        Log.d(TAG, "фаза 2 запущена")
        CoreGraph.initCredentialEncrypted(this)
        // Перенос очереди событий и пересборка снимка — вне главного потока: и то и другое
        // трогает диск и базу. Запускается на выделенном потоке, а не на пуле корутин,
        // чтобы не тянуть диспетчер ради двух операций при старте.
        Thread({ CoreGraph.runUnlockedTasks() }, "nope-call-init").apply { isDaemon = true }.start()
    }

    /**
     * Разрешения выданы уже после старта — перестроить то, что от них зависит.
     *
     * Индекс контактов и зеркало журнала строятся в фазе 2, а она к моменту выдачи разрешения
     * давно прошла. Без этого вызова пользователь увидел бы разницу только после перезапуска
     * приложения, то есть счёл бы, что доступ ничего не дал.
     */
    public fun refreshAfterPermissions() {
        Thread({ CoreGraph.refreshPermissionDependent() }, "nope-call-perms")
            .apply { isDaemon = true }
            .start()
    }

    private companion object {
        const val TAG = "NopeCallApp"
    }
}

/**
 * Граф зависимостей горячего пути. Ссылки ленивые: в фазе 1 ничего тяжёлого создавать нельзя.
 */
public object CoreGraph {

    @Volatile
    private var deContext: Context? = null

    private val unlockedInitStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Контекст Device Protected Storage: единственное, что доступно до разблокировки. */
    public val deviceEncrypted: Context
        get() = deContext ?: error("CoreGraph не инициализирован: фаза 1 не выполнилась")

    /**
     * Снимок правил. Создаётся лениво и живёт в Device Protected Storage — иначе после
     * перезагрузки до первой разблокировки правил не будет вообще (архитектура §5).
     */
    public val snapshots: SnapshotStore by lazy {
        SnapshotStore(
            dir = File(deviceEncrypted.filesDir, "snapshot"),
            directorySync = AndroidDirectorySync,
        )
    }

    /**
     * Нормализация номера. Быстрый путь для РФ, без метаданных и без `Context`, поэтому
     * годится и в фазе 1 (архитектура §6.3). Резерв на `libphonenumber` подключается здесь же,
     * когда появится, — интерфейс для этого и введён.
     */
    public val normalizer: PhoneNumberNormalizer by lazy { RuFastPathNormalizer() }

    /**
     * Индекс контактов. В Device Protected Storage лежат только усечённые хеши номеров,
     * без имён: адресная книга чувствительнее правил (архитектура §5.1, §5.3).
     */
    public val contactIndex: ContactIndex by lazy {
        ContactIndex(File(deviceEncrypted.filesDir, "contacts"), normalizer)
    }

    /**
     * Экстренные номера через систему. `TelephonyManager.isEmergencyNumber` может требовать
     * `READ_PHONE_STATE`, которое опционально, поэтому отказ здесь не страшен: движок всё равно
     * сверяется с резервным списком в настройках снимка (ТЗ §5.4).
     */
    public val emergencyNumbers: EmergencyNumbers by lazy {
        EmergencyNumbers { digits ->
            runCatching {
                deviceEncrypted.getSystemService(android.telephony.TelephonyManager::class.java)
                    ?.isEmergencyNumber(digits) == true
            }.getOrDefault(false)
        }
    }

    /**
     * Уведомления. Создаётся на DE-контексте: уведомление о блокировке отправляется сразу
     * после ответа системе, в том числе до первой разблокировки экрана.
     */
    public val notifier: BlockedCallNotifier by lazy {
        BlockedCallNotifier(deviceEncrypted, notifyStore)
    }

    public val callFactsBuilder: CallFactsBuilder by lazy {
        CallFactsBuilder(
            normalizer = normalizer,
            contacts = contactIndex,
            emergency = emergencyNumbers,
        )
    }

    /**
     * Очередь событий в Device Protected Storage: до первой разблокировки Room недоступен,
     * а событие всё равно надо сохранить (архитектура §9.2).
     */
    public val eventSpool: EventSpool by lazy {
        EventSpool(File(deviceEncrypted.filesDir, "spool"))
    }

    /**
     * Настройки режима наблюдения в DE-хранилище.
     *
     * Не в Room: писатель работает и до первой разблокировки экрана, а до неё база недоступна.
     * Room остаётся источником истины для интерфейса и зеркалит значения сюда.
     */
    public val observationStore: ObservationStore by lazy { ObservationStore(deviceEncrypted) }

    /**
     * Режим наблюдения (ТЗ §7.7). Логи лежат в DE-хранилище по той же причине: событие звонка
     * должно записаться и до разблокировки, иначе самые интересные случаи не попадут в лог.
     */
    public val observation: ObservationLog by lazy {
        val dir = File(deviceEncrypted.filesDir, "observe")
        ObservationLog(
            dir = dir,
            configProvider = { observationStore.config() },
            freeSpace = { dir.usableSpace },
        )
    }

    /** Контекст сети для записи наблюдения. Собирается после ответа системе (ТЗ §7.7.1). */
    internal val networkContext: AndroidNetworkContext by lazy {
        AndroidNetworkContext(deviceEncrypted)
    }

    /** Контекст устройства. Один и тот же для всех записей процесса — считается один раз. */
    public val deviceContext: DeviceContext by lazy {
        DeviceContext(
            manufacturer = android.os.Build.MANUFACTURER.orEmpty(),
            model = android.os.Build.MODEL.orEmpty(),
            androidRelease = android.os.Build.VERSION.RELEASE.orEmpty(),
            sdkInt = android.os.Build.VERSION.SDK_INT,
            buildFingerprint = android.os.Build.FINGERPRINT.orEmpty(),
            appVersion = runCatching {
                val pm = deviceEncrypted.packageManager
                val info = pm.getPackageInfo(deviceEncrypted.packageName, 0)
                "${info.versionName}+${info.longVersionCode}"
            }.getOrDefault("unknown"),
            installId = observationStore.installId(),
        )
    }

    public val observationReporter: ObservationReporter
        get() = ObservationReporter(database, observation)

    /** Диагностика (ТЗ §9.7). Тестовый прогон идёт через настоящий снимок и настоящий движок. */
    public val diagnostics: DiagnosticsRepository
        get() = DiagnosticsRepository(database, snapshots, normalizer, eventSpool)

    /**
     * Выгрузка логов. Архив собирается в обычный (CE) кэш, а не в DE-хранилище: `FileProvider`
     * умеет отдавать только пути обычного хранилища, а выгрузку всё равно запускает интерфейс,
     * то есть экран уже разблокирован.
     */
    public val logExporter: LogExporter
        get() = LogExporter(observation, File((credentialContext ?: deviceEncrypted).cacheDir, "logs"))

    @Volatile
    private var credentialContext: Context? = null

    /**
     * База данных. Доступна **только после разблокировки**: обращение отсюда из фазы 1
     * или из горячего пути — ошибка (архитектура §3.1, §5).
     */
    public val database: NopeCallDatabase
        get() = NopeCallDatabase.get(
            credentialContext ?: error("база недоступна: фаза 2 не выполнилась")
        )

    public val rules: RulesRepository
        get() = RulesRepository(database, snapshots, normalizer)

    public val eventRecorder: EventRecorder
        get() = EventRecorder(database)

    /**
     * Журнал. Отдаётся графом, а не через [database], чтобы модуль `:app` не знал про Room:
     * иначе типы Room протекли бы в мост, и границу пришлось бы ослаблять.
     */
    public val journal: JournalRepository
        get() = JournalRepository(database)

    /**
     * Номера телефонной книги для предпросмотра правила (ТЗ §18 п. 16).
     *
     * Отдельно от [contactIndex]: индекс хранит только усечённые хеши, и по хешам нельзя
     * проверить правило «начинается с». Здесь книга читается разово, в момент показа
     * предпросмотра, и нигде не сохраняется. Обращение к `ContentProvider` тут допустимо —
     * это интерфейс, а не горячий путь.
     */
    public val contactNumbers: ContactNumberSource
        get() = AndroidContactNumberSource(credentialContext ?: deviceEncrypted, normalizer)

    /** Экспорт и импорт правил (ТЗ §15.8). Валидацию берёт из репозитория, а не дублирует. */
    public val rulesTransfer: RulesTransfer
        get() = RulesTransfer(rules, appVersion = deviceContext.appVersion)

    /** Настройки уведомлений в DE-хранилище: уведомитель работает и до разблокировки. */
    public val notifyStore: NotifyStore by lazy { NotifyStore(deviceEncrypted) }

    /**
     * Метки SIM для фильтра журнала (ТЗ §7.4).
     *
     * Через `get()`, а не `by lazy`: имя оператора читается заново при каждом показе фильтра —
     * карту могли вынуть, а устаревшая метка врёт молча.
     */
    public val simLabels: SimLabels
        get() = AndroidSimLabels(credentialContext ?: deviceEncrypted)

    /** Выгрузка журнала в CSV (ТЗ §7.6). */
    public val journalCsv: JournalCsv
        get() = JournalCsv(journal)

    /**
     * Синхронизация зеркала системного журнала. Требует `READ_CALL_LOG`; без него зеркало
     * остаётся пустым, и раздел «Журнал» показывает только собственные проверки (ТЗ §7.2).
     */
    public val callLogSyncer: CallLogSyncer
        get() = CallLogSyncer(
            db = database,
            source = AndroidCallLogSource(credentialContext ?: deviceEncrypted),
            normalizer = normalizer,
            onLateName = { occurredAt, digits, nameRaw, nameFold ->
                observation.observeLateName(occurredAt, digits, nameRaw, nameFold)
            },
            // Индекс контактов отличает имя из книги от названия от сети. Хешей для этого
            // достаточно: здесь нужна принадлежность конкретного номера, а не сопоставление
            // шаблона. Без индекса ответ «не знаю», и источник останется неустановленным.
            contacts = contactIndex,
        )

    /** Когда сервис проверки вызывался последний раз. Нужно интерфейсу, чтобы отличить
     *  «роль выдана, но звонков не было» от «роль выдана, а сервис не вызывается» (ТЗ §4.4). */
    public suspend fun lastScreeningAt(): Long? = database.events().lastEventAt()

    public fun initDeviceEncrypted(context: Context) {
        deContext = context
    }

    internal fun initCredentialEncrypted(context: Context) {
        credentialContext = context
    }

    internal fun markUnlockedInitStarted(): Boolean = unlockedInitStarted.compareAndSet(false, true)

    /**
     * Сигнал «событие дописано в очередь». Из горячего пути Room трогать нельзя, поэтому здесь
     * только флаг: переносом занимается фаза 2 или чтение журнала.
     */
    public fun onEventRecorded() {
        pendingDrain.set(true)
    }

    internal val pendingDrain = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Переносит накопленные события в Room. Вызывается **перед чтением журнала**.
     *
     * Без этого события, записанные в уже запущенном процессе, попадали бы в базу только при
     * следующем старте приложения: сервис проверки не имеет права трогать Room, а фаза 2
     * выполняется один раз. Пользователь при этом видел бы пустой журнал сразу после звонка —
     * то есть приложение выглядело бы неработающим.
     *
     * Пытается всегда, а не только по флагу: процесс мог умереть, не успев его выставить.
     * Пустая очередь стоит одного `File.isFile`.
     */
    public suspend fun drainPending(): Int {
        if (credentialContext == null) return 0
        pendingDrain.set(false)
        return runCatching { eventRecorder.drain(eventSpool) }.getOrDefault(0)
    }

    /**
     * Обслуживание журнала: ретеншен по сроку и по числу записей (ТЗ §7.6).
     *
     * Не чаще раза в сутки, метка хранится в настройках. Отдельного планировщика нет
     * намеренно: WorkManager ради одного `DELETE` — лишняя зависимость в горячем модуле,
     * а фаза 2 выполняется и при старте интерфейса, и после перезагрузки. Отступление
     * зафиксировано в архитектуре §15.
     */
    private suspend fun housekeeping() {
        val now = System.currentTimeMillis()
        val last = rules.getSetting(RulesRepository.KEY_HOUSEKEEPING_AT)?.toLongOrNull() ?: 0L
        if (now - last < HOUSEKEEPING_INTERVAL_MS) return

        val days = rules.getSetting(RulesRepository.KEY_RETENTION_DAYS)?.toIntOrNull()
            ?: JournalRepository.RETENTION_DAYS
        val records = rules.getSetting(RulesRepository.KEY_RETENTION_RECORDS)?.toIntOrNull()
            ?: JournalRepository.RETENTION_RECORDS

        val removed = journal.applyRetention(now, keepDays = days, keepRecords = records)
        rules.putInternal(RulesRepository.KEY_HOUSEKEEPING_AT, now.toString())
        if (removed > 0) Log.d("NopeCallApp", "ретеншен журнала: удалено $removed")
    }

    private const val HOUSEKEEPING_INTERVAL_MS = 24L * 60 * 60 * 1000

    /**
     * Перестройка того, что зависит от необязательных разрешений. Идемпотентна и безопасна:
     * без разрешения обе операции честно возвращают «нечего делать».
     */
    internal fun refreshPermissionDependent() {
        runCatching {
            kotlinx.coroutines.runBlocking {
                val contacts = contactIndex.rebuild(deviceEncrypted)
                if (contacts >= 0) Log.d("NopeCallApp", "контактов в индексе: $contacts")
                val sync = callLogSyncer.sync()
                if (sync.available) {
                    Log.d("NopeCallApp", "зеркало после выдачи доступа: ${sync.fetched}")
                }
            }
        }.onFailure { Log.w("NopeCallApp", "перестройка после разрешений не удалась", it) }
    }

    /**
     * Задачи фазы 2. Обе идемпотентны и обе могут упасть без последствий для проверки звонков:
     * снимок останется прежним, очередь дождётся следующего запуска.
     */
    internal fun runUnlockedTasks() {
        runCatching {
            kotlinx.coroutines.runBlocking {
                val moved = eventRecorder.drain(eventSpool)
                pendingDrain.set(false)
                if (moved > 0) Log.d("NopeCallApp", "перенесено событий: $moved")
                // Снимок пересобирается при старте: правила могли измениться, пока процесса
                // не было, а после обновления приложения формат мог остаться прежним.
                rules.rebuildSnapshot()

                // Индекс контактов перестраивается здесь же. Без разрешения вернёт -1 —
                // и это не ошибка: система тогда и так не присылает звонки от контактов.
                val contacts = contactIndex.rebuild(deviceEncrypted)
                if (contacts >= 0) Log.d("NopeCallApp", "контактов в индексе: $contacts")

                notifier.ensureChannels()

                // Зеркало системного журнала: постоянная синхронизация, а не однократный
                // импорт — система дописывает записи после звонка (ТЗ §7.2).
                val sync = callLogSyncer.sync()
                if (sync.available) {
                    Log.d(
                        "NopeCallApp",
                        "зеркало: получено ${sync.fetched}, сшито ${sync.stitched}, " +
                            "поздних имён ${sync.lateNames}",
                    )
                }

                housekeeping()

                // Роль могли отозвать, пока процесса не было: назначили другое приложение или
                // прошивка сбросила. Отказ невидим — сервис просто перестаёт вызываться,
                // поэтому о нём надо сказать явно (ТЗ §4.4).
                if (!RoleController(deviceEncrypted).hasRole() && rules.enabledCount() > 0) {
                    notifier.notifyRoleLost()
                }
            }
        }.onFailure { Log.w("NopeCallApp", "задачи фазы 2 не выполнены", it) }
    }
}

/**
 * `fsync` каталога после `rename`.
 *
 * Без него `rename` не durable: данные файла могут дойти до диска, а новая ссылка в каталоге —
 * нет, и после потери питания снимок окажется отсутствующим (архитектура §5.2). В Java нет
 * переносимого способа синхронизировать каталог, поэтому используется `android.system.Os`.
 */
internal object AndroidDirectorySync : DirectorySync {
    override fun sync(dir: File) {
        try {
            val fd = Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (t: Throwable) {
            // Потеря durability хуже, чем её отсутствие в логах, но падать здесь нельзя:
            // снимок уже записан и переименован, и он работоспособен.
            Log.w("NopeCallApp", "не удалось синхронизировать каталог снимка", t)
        }
    }
}
