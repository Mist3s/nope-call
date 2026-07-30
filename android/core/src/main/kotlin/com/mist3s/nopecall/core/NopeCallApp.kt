package com.mist3s.nopecall.core

import android.app.Application
import android.content.Context
import android.os.UserManager
import android.system.Os
import android.util.Log
import com.mist3s.nopecall.core.contacts.ContactIndex
import com.mist3s.nopecall.core.facts.CallFactsBuilder
import com.mist3s.nopecall.core.facts.ContactMembership
import com.mist3s.nopecall.core.facts.EmergencyNumbers
import com.mist3s.nopecall.core.notify.BlockedCallNotifier
import com.mist3s.nopecall.core.role.RoleController
import com.mist3s.nopecall.core.snapshot.DirectorySync
import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.core.storage.EventRecorder
import com.mist3s.nopecall.core.storage.JournalRepository
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
     * Построение фактов. Индекс контактов и проверка экстренных номеров пока не подключены:
     * в обоих случаях «не знаю» — безопасный ответ. Промах индекса помечается флагом
     * `CONTACT_INDEX_STALE`, а экстренные номера всё равно проверяются резервным списком
     * в настройках снимка (ТЗ §5.4).
     */
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
    public val notifier: BlockedCallNotifier by lazy { BlockedCallNotifier(deviceEncrypted) }

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
