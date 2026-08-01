package com.mist3s.nopecall.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.mist3s.nopecall.R
import com.mist3s.nopecall.core.CoreGraph
import com.mist3s.nopecall.core.notify.NotifyConfig
import com.mist3s.nopecall.core.observe.LogExporter
import com.mist3s.nopecall.core.observe.ObservationConfig
import com.mist3s.nopecall.core.role.RoleController
import com.mist3s.nopecall.core.storage.JournalCursor
import com.mist3s.nopecall.core.storage.JournalFilter
import com.mist3s.nopecall.core.storage.ImportMode
import com.mist3s.nopecall.core.storage.JournalRepository
import com.mist3s.nopecall.core.storage.ImportResult
import com.mist3s.nopecall.core.storage.RulesRepository
import com.mist3s.nopecall.core.storage.SaveResult
import com.mist3s.nopecall.updater.InstallResult
import com.mist3s.nopecall.updater.UpdateCheckResult
import com.mist3s.nopecall.updater.UpdateManager
import com.mist3s.nopecall.updater.Updater
import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.MatchType
import com.mist3s.nopecall.engine.PatternCheck
import com.mist3s.nopecall.engine.RegexField
import com.mist3s.nopecall.engine.RuleTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Реализации Pigeon-контракта (архитектура §8).
 *
 * Живут в `:app` — единственном модуле, который знает про Flutter. Горячий путь в `:core`
 * про мост не знает вообще, и это проверяется задачей `verifyModuleBoundaries`.
 *
 * Все вызовы приходят на платформенный поток и **немедленно уходят** на `Dispatchers.IO`:
 * длительная синхронная работа на платформенном потоке запрещена, потому что через тот же
 * поток доставляется `onScreenCall` (архитектура §4.4, §8.4).
 */
internal class BridgeScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

internal class StatusApiImpl(
    private val activityProvider: () -> Activity?,
) : StatusApi {

    override fun status(): SetupStatus {
        val activity = activityProvider()
        val context = activity ?: return unknownStatus()
        val role = RoleController(context)

        // Настройки и число правил читаются синхронно: обе операции — одна строка из Room,
        // и вызов приходит из UI, а не из горячего пути.
        val blockingEnabled = runCatchingBlocking {
            CoreGraph.rules.getSetting(RulesRepository.KEY_BLOCKING_ENABLED)?.toBooleanStrictOrNull()
        } ?: true
        // Действие по умолчанию нужно интерфейсу не как настройка, а как обещание:
        // главный экран не имеет права говорить «остальные звонки проходят», если
        // пользователь переключил его на блокировку.
        val defaultAction = runCatchingBlocking {
            CoreGraph.rules.getSetting(RulesRepository.KEY_DEFAULT_ACTION)
        } ?: "ALLOW"
        runCatchingBlocking { CoreGraph.drainPending() }
        val ruleCount = runCatchingBlocking { CoreGraph.rules.enabledCount() } ?: 0
        val lastScreening = runCatchingBlocking { CoreGraph.lastScreeningAt() }

        val state = role.state(blockingEnabled, ruleCount, lastScreening)
        return SetupStatus(
            hasRole = state.hasRole,
            hasCallLog = state.hasCallLog,
            hasContacts = state.hasContacts,
            hasNotifications = state.hasNotifications,
            blockingEnabled = state.blockingEnabled,
            blockingActive = state.blockingActive,
            enabledRuleCount = state.enabledRuleCount.toLong(),
            problems = state.problems.map { it.name },
            defaultAction = defaultAction ?: "ALLOW",
            lastScreeningAt = state.lastScreeningAt,
        )
    }

    override fun requestRole(callback: (Result<Boolean>) -> Unit) {
        val activity = activityProvider()
        if (activity == null) {
            callback(Result.success(false))
            return
        }
        val intent = RoleController(activity).requestIntent()
        if (intent == null) {
            // Роль недоступна на устройстве — обещать блокировку нельзя (ТЗ §4.1).
            callback(Result.success(false))
            return
        }
        activity.startActivityForResult(intent, RoleController.REQUEST_CODE)
        callback(Result.success(true))
    }

    /**
     * Запрос необязательных разрешений одним диалогом.
     *
     * Возвращает `true`, если диалог показан, а не «разрешения выданы»: результат приходит
     * в `onRequestPermissionsResult`, а интерфейс всё равно перечитывает состояние сам —
     * разрешение могли отозвать и мимо приложения.
     */
    override fun requestPermissions(callback: (Result<Boolean>) -> Unit) {
        val activity = activityProvider()
        if (activity == null) {
            callback(Result.success(false))
            return
        }
        val wanted = buildList {
            add(android.Manifest.permission.READ_CALL_LOG)
            add(android.Manifest.permission.READ_CONTACTS)
            // Необязательное по ТЗ §10, но без него недостижимы три вещи: имя оператора
            // и слот у SIM в фильтре журнала (§7.4), тип сети и VoLTE в наблюдении (§7.7.1),
            // системная проверка экстренного номера (§5.4). Оно было объявлено в манифесте
            // и не запрашивалось — то есть пользователь видел разрешение «Телефон» в списке,
            // а приложение им не пользовалось. Отказ по нему ничего не ломает.
            add(android.Manifest.permission.READ_PHONE_STATE)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filterNot { RoleController(activity).hasPermission(it) }

        if (wanted.isEmpty()) {
            callback(Result.success(false))
            return
        }
        activity.requestPermissions(wanted.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        callback(Result.success(true))
    }

    override fun openAppSettings() {
        val activity = activityProvider() ?: return
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
        )
    }

    /**
     * Системные настройки уведомлений приложения.
     *
     * Звук и важность — свойства канала, и Android не даёт менять их после создания: попытка
     * пересоздать канал с другой важностью просто игнорируется. Поэтому переключателей звука
     * в приложении нет, а есть переход туда, где это действительно настраивается.
     */
    override fun openNotificationSettings() {
        val activity = activityProvider() ?: return
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        // Резерв — общие настройки приложения: на части прошивок экрана каналов нет.
        val opened = runCatching { activity.startActivity(intent) }.isSuccess
        if (!opened) openAppSettings()
    }

    private fun unknownStatus() = SetupStatus(
        hasRole = false,
        hasCallLog = false,
        hasContacts = false,
        hasNotifications = false,
        blockingEnabled = false,
        blockingActive = false,
        enabledRuleCount = 0,
        problems = listOf("NO_ROLE"),
        defaultAction = "ALLOW",
        lastScreeningAt = null,
    )

    private fun <T> runCatchingBlocking(block: suspend () -> T): T? = try {
        kotlinx.coroutines.runBlocking { block() }
    } catch (_: Throwable) {
        null
    }

    internal companion object {
        const val PERMISSIONS_REQUEST_CODE: Int = 4292
    }
}

internal class RulesApiImpl(
    private val bridge: BridgeScope,
    /** Нужен для экспорта и импорта: и то и другое идёт через системные диалоги (ТЗ §15.8). */
    private val activityProvider: () -> Activity?,
) : RulesApi {

    override fun list(callback: (Result<List<RuleDto>>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                CoreGraph.rules.all().map { r ->
                    RuleDto(
                        id = r.id,
                        title = r.title,
                        targetType = r.targetType,
                        matchType = r.matchType,
                        pattern = r.pattern,
                        patternCanonical = r.patternCanonical,
                        action = r.action,
                        orderIndex = r.orderIndex.toLong(),
                        isEnabled = r.isEnabled,
                        translitVariants = r.translitVariants,
                        matchCount = r.matchCount,
                        regexField = r.regexField,
                        comment = r.comment,
                        lastMatchedAt = r.lastMatchedAt,
                    )
                }
            }
            callback(result)
        }
    }

    override fun save(
        id: Long?,
        title: String,
        targetType: String,
        matchType: String,
        pattern: String,
        action: String,
        enabled: Boolean,
        regexField: String?,
        translitVariants: Boolean?,
        comment: String?,
        callback: (Result<SaveRuleResult>) -> Unit,
    ) {
        bridge.scope.launch {
            val result = runCatching {
                val target = enumOf<RuleTarget>(targetType)
                    ?: return@runCatching rejected("неизвестный источник проверки: $targetType")
                val match = enumOf<MatchType>(matchType)
                    ?: return@runCatching rejected("неизвестный тип сопоставления: $matchType")
                val act = enumOf<CallAction>(action)
                    ?: return@runCatching rejected("неизвестное действие: $action")

                when (
                    val saved = CoreGraph.rules.save(
                        id = id,
                        title = title,
                        target = target,
                        matchType = match,
                        pattern = pattern,
                        action = act,
                        enabled = enabled,
                        regexField = regexField?.let { enumOf<RegexField>(it) },
                        translitVariants = translitVariants,
                        comment = comment,
                    )
                ) {
                    is SaveResult.Saved -> SaveRuleResult(
                        saved = true,
                        id = saved.id,
                        variants = saved.variants,
                        variantsTruncated = saved.variantsTruncated,
                        error = null,
                    )

                    is SaveResult.Rejected -> rejected(saved.reason)
                }
            }
            callback(result)
        }
    }

    override fun setEnabled(id: Long, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        bridge.scope.launch { callback(runCatching { CoreGraph.rules.setEnabled(id, enabled) }) }
    }

    override fun delete(id: Long, callback: (Result<Unit>) -> Unit) {
        bridge.scope.launch { callback(runCatching { CoreGraph.rules.delete(id) }) }
    }

    override fun reorder(idsInOrder: List<Long>, callback: (Result<Unit>) -> Unit) {
        bridge.scope.launch { callback(runCatching { CoreGraph.rules.reorder(idsInOrder) }) }
    }

    /**
     * Проверка шаблона синхронна намеренно: она чистая, без обращений к базе, и вызывается
     * на каждое изменение поля в редакторе. Уход в корутину дал бы мигание подсказки.
     */
    override fun checkPattern(
        targetType: String,
        matchType: String,
        pattern: String,
    ): PatternCheckResult {
        val target = enumOf<RuleTarget>(targetType)
        val match = enumOf<MatchType>(matchType)
        if (target == null || match == null) {
            return PatternCheckResult(false, "", emptyList(), false, emptyList(), "неизвестный тип правила")
        }
        return when (val check = CoreGraph.rules.validate(target, match, pattern)) {
            is PatternCheck.Ok -> PatternCheckResult(
                valid = true,
                canonical = check.canonical,
                variants = check.variants,
                variantsTruncated = check.variantsTruncated,
                parts = check.parts,
                error = null,
            )

            is PatternCheck.Invalid ->
                PatternCheckResult(false, "", emptyList(), false, emptyList(), check.reason)

            is PatternCheck.TooExpensive ->
                PatternCheckResult(false, "", emptyList(), false, emptyList(), check.reason)
        }
    }

    override fun preview(
        targetType: String,
        matchType: String,
        pattern: String,
        callback: (Result<PreviewDto>) -> Unit,
    ) {
        bridge.scope.launch {
            val result = runCatching {
                val target = enumOf<RuleTarget>(targetType) ?: return@runCatching emptyPreview()
                val match = enumOf<MatchType>(matchType) ?: return@runCatching emptyPreview()
                val check = CoreGraph.rules.validate(target, match, pattern)
                val canonical = (check as? PatternCheck.Ok)?.canonical
                    ?: return@runCatching emptyPreview()

                val preview = CoreGraph.journal.previewMatches(
                    target = targetType,
                    matchType = matchType,
                    canonicalPattern = canonical,
                    // Весь набор, а не только канонический: у правила по категории здесь
                    // перечисленные категории, у правила по названию — варианты написания.
                    variants = (check as? PatternCheck.Ok)?.variants.orEmpty(),
                    // Книга читается здесь, а не в горячем пути: предпросмотр рисуется
                    // в редакторе, и обращение к ContentProvider тут допустимо (ТЗ §18 п. 16).
                    contacts = CoreGraph.contactNumbers,
                )
                PreviewDto(
                    count = preview.count.toLong(),
                    truncated = preview.truncated,
                    contactsTruncated = preview.contactsTruncated,
                    allowRulesCovered = preview.allowRulesCovered?.toLong(),
                    contactsCovered = preview.contactsCovered?.toLong(),
                    contactsState = preview.contactsState.name,
                )
            }
            callback(result)
        }
    }

    /** Пустой предпросмотр: шаблон ещё не разобрался, считать нечего. */
    private fun emptyPreview() = PreviewDto(
        count = 0,
        truncated = false,
        contactsTruncated = false,
        contactsState = JournalRepository.ContactsState.NOT_APPLICABLE.name,
        allowRulesCovered = null,
        contactsCovered = null,
    )

    /**
     * Экспорт правил (ТЗ §15.8): файл собирается в кэш и отдаётся через системный выбор.
     *
     * Тем же путём, что архив логов: `FileProvider` и `ACTION_SEND`. Приложение само никуда
     * ничего не отправляет — куда именно уйдёт файл, решает пользователь.
     */
    override fun exportRules(callback: (Result<Boolean>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                val json = CoreGraph.rulesTransfer.exportJson()
                val activity = activityProvider() ?: return@runCatching false
                val dir = java.io.File(activity.cacheDir, "logs").apply { mkdirs() }
                val file = java.io.File(dir, "nope-call-rules.json")
                file.writeText(json)

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.logs",
                    file,
                )
                activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, file.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        activity.getString(R.string.share_rules_title),
                    )
                )
                true
            }
            callback(result)
        }
    }

    /**
     * Импорт правил (ТЗ §15.8).
     *
     * Ответ приходит **после** выбора файла: обратный вызов Pigeon сохраняется, открывается
     * системный выбор документа, и вызов завершается уже в `onActivityResult`. Отмена — это
     * тоже ответ: молча оставить интерфейс в ожидании нельзя.
     */
    override fun importRules(replaceAll: Boolean, callback: (Result<ImportReportDto>) -> Unit) {
        val activity = activityProvider()
        if (activity == null) {
            callback(Result.success(cancelledImport("приложение не на переднем плане")))
            return
        }
        pendingImport = PendingImport(replaceAll, callback)
        runCatching {
            activity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // Не только application/json: файловые менеджеры и облака отдают
                    // выгруженный файл с самыми разными типами, вплоть до octet-stream.
                    type = "*/*"
                },
                IMPORT_REQUEST_CODE,
            )
        }.onFailure {
            pendingImport = null
            callback(Result.success(cancelledImport("на устройстве нет выбора файлов")))
        }
    }

    /** Вызывается хостом из `onActivityResult`: только он знает про результат выбора. */
    internal fun onImportPicked(uri: Uri?) {
        val pending = pendingImport ?: return
        pendingImport = null
        if (uri == null) {
            pending.callback(Result.success(cancelledImport("отменено")))
            return
        }
        bridge.scope.launch {
            val result = runCatching {
                val activity = activityProvider()
                val text = activity?.contentResolver?.openInputStream(uri)?.use {
                    it.readBytes().decodeToString()
                }
                if (text == null) {
                    cancelledImport("файл не удалось прочитать")
                } else {
                    val mode = if (pending.replaceAll) {
                        ImportMode.REPLACE_ALL
                    } else {
                        ImportMode.ADD_MISSING
                    }
                    when (val imported = CoreGraph.rulesTransfer.importJson(text, mode)) {
                        is ImportResult.Done -> ImportReportDto(
                            ok = true,
                            added = imported.report.added.toLong(),
                            updated = imported.report.updated.toLong(),
                            duplicates = imported.report.duplicates.toLong(),
                            removed = imported.report.removed,
                            rejected = imported.report.rejected.map {
                                "строка ${it.index + 1}" +
                                    (it.title?.let { t -> " «$t»" } ?: "") +
                                    ": ${it.reason}"
                            },
                            snapshotRebuilt = imported.report.snapshotRebuilt,
                            error = null,
                        )

                        is ImportResult.Failed -> cancelledImport(imported.reason)
                    }
                }
            }
            pending.callback(result)
        }
    }

    private fun cancelledImport(reason: String) = ImportReportDto(
        ok = false,
        added = 0,
        updated = 0,
        duplicates = 0,
        removed = emptyList(),
        rejected = emptyList(),
        snapshotRebuilt = false,
        error = reason,
    )

    private class PendingImport(
        val replaceAll: Boolean,
        val callback: (Result<ImportReportDto>) -> Unit,
    )

    private var pendingImport: PendingImport? = null

    private fun rejected(reason: String) =
        SaveRuleResult(saved = false, id = 0, variants = emptyList(), variantsTruncated = false, error = reason)

    private inline fun <reified T : Enum<T>> enumOf(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    internal companion object {
        const val IMPORT_REQUEST_CODE: Int = 4293
    }
}

internal class JournalApiImpl(
    private val bridge: BridgeScope,
    /** Нужен для выгрузки CSV: файл отдаётся через системный выбор приложения (ТЗ §7.6). */
    private val activityProvider: () -> Activity?,
) : JournalApi {

    override fun page(
        filter: JournalFilterDto,
        cursor: JournalCursorDto?,
        limit: Long,
        callback: (Result<JournalPageDto>) -> Unit,
    ) {
        bridge.scope.launch {
            val result = runCatching {
                // Сначала переносим события из очереди: иначе только что заблокированный
                // звонок не появился бы в журнале до перезапуска приложения.
                CoreGraph.drainPending()
                val page = CoreGraph.journal.page(
                    cursor = cursor?.let {
                        JournalCursor(it.at, it.sourceRank.toInt(), it.id)
                    },
                    filter = JournalFilter(
                        kind = filter.kind,
                        digitsQuery = filter.digitsQuery,
                        nameQuery = filter.nameQuery,
                        hadSignature = filter.hadSignature,
                        fromAt = filter.fromAt,
                        toAt = filter.toAt,
                        ruleId = filter.ruleId,
                        sim = filter.sim,
                    ),
                    limit = limit.toInt(),
                )
                JournalPageDto(
                    items = page.items.map { item ->
                        JournalItemDto(
                            id = item.id,
                            sourceRank = item.sourceRank.toLong(),
                            occurredAt = item.occurredAt,
                            kind = item.kind,
                            rawNumber = item.rawNumber,
                            nameSource = item.nameSource,
                            blockedByUs = item.blockedByUs,
                            hadSignature = item.hadSignature,
                            nameLate = item.nameLate,
                            action = item.action,
                            reason = item.reason,
                            latencyMs = item.latencyMs?.toLong(),
                            durationSeconds = item.durationSeconds?.toLong(),
                            e164 = item.e164,
                            nameRaw = item.nameRaw,
                            matchedRuleId = item.matchedRuleId,
                            matchedRuleTitle = item.matchedRuleTitle,
                            eventId = item.eventId,
                            systemId = item.systemId,
                            phoneAccountId = item.phoneAccountId,
                        )
                    },
                    hasMore = page.hasMore,
                    next = page.next?.let {
                        JournalCursorDto(at = it.at, sourceRank = it.sourceRank.toLong(), id = it.id)
                    },
                )
            }
            callback(result)
        }
    }

    override fun summary(callback: (Result<SummaryDto>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                CoreGraph.drainPending()
                val s = CoreGraph.journal.summary(System.currentTimeMillis())
                SummaryDto(
                    blockedToday = s.blockedToday.toLong(),
                    totalEvents = s.totalEvents.toLong(),
                    withSignatureLast100 = s.withSignatureLast100.toLong(),
                    checkedLast100 = s.checkedLast100.toLong(),
                    mirrorRecords = s.mirrorRecords.toLong(),
                    lastEventAt = s.lastEventAt,
                )
            }
            callback(result)
        }
    }

    override fun sims(callback: (Result<List<SimDto>>) -> Unit) {
        bridge.scope.launch {
            callback(
                runCatching {
                    CoreGraph.drainPending()
                    // Метки читаются здесь, а не в `:core`-запросе: в журнале хранится
                    // `phoneAccountId`, а имя оператора живёт в telephony и требует разрешения.
                    val labels = CoreGraph.simLabels
                    CoreGraph.journal.sims().map { id ->
                        val label = labels.labelFor(id)
                        SimDto(id = id, label = label.text, nameKnown = label.known)
                    }
                }
            )
        }
    }

    override fun hide(systemId: Long, callback: (Result<Unit>) -> Unit) {
        bridge.scope.launch { callback(runCatching { CoreGraph.journal.hide(systemId) }) }
    }

    override fun clear(callback: (Result<Long>) -> Unit) {
        bridge.scope.launch {
            callback(
                runCatching {
                    // Слив очереди перед очисткой: иначе события из Direct Boot, ещё не
                    // перенесённые в Room, всплыли бы в журнале уже после того, как
                    // пользователь его очистил.
                    CoreGraph.drainPending()
                    CoreGraph.journal.clear().toLong()
                }
            )
        }
    }

    /**
     * Выгрузка журнала в CSV (ТЗ §7.6) и передача файла наружу.
     *
     * Пишется потоково в файл в кэше, а не собирается в память: журнал бывает на десятки
     * тысяч записей. Отдаётся тем же путём, что архив логов, — через `FileProvider`.
     */
    override fun exportCsv(fromAt: Long?, toAt: Long?, callback: (Result<Long>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                CoreGraph.drainPending()
                val activity = activityProvider() ?: return@runCatching 0L
                val dir = java.io.File(activity.cacheDir, "logs").apply { mkdirs() }
                val file = java.io.File(dir, "nope-call-journal.csv")

                val rows = file.outputStream().use { out ->
                    CoreGraph.journalCsv.writeTo(
                        out = out,
                        filter = JournalFilter(fromAt = fromAt, toAt = toAt),
                    )
                }
                if (rows == 0) {
                    file.delete()
                    return@runCatching 0L
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.logs",
                    file,
                )
                activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, file.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        activity.getString(R.string.share_journal_title),
                    )
                )
                rows.toLong()
            }
            callback(result)
        }
    }

    override fun syncCallLog(callback: (Result<SyncResultDto>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                val sync = CoreGraph.callLogSyncer.sync()
                SyncResultDto(
                    available = sync.available,
                    fetched = sync.fetched.toLong(),
                    stitched = sync.stitched.toLong(),
                    lateNames = sync.lateNames.toLong(),
                )
            }
            callback(result)
        }
    }
}

/**
 * Режим наблюдения (ТЗ §7.7).
 *
 * Настройки живут в двух местах сознательно: источник истины — Room, но писатель обращается
 * к DE-хранилищу, потому что работает и до первой разблокировки экрана. Поэтому каждое
 * изменение зеркалится, а не читается из Room на месте.
 */
internal class ObservationApiImpl(
    private val bridge: BridgeScope,
    private val activityProvider: () -> Activity?,
) : ObservationApi {

    override fun status(callback: (Result<ObservationStatusDto>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                val config = CoreGraph.observationStore.config()
                val stats = CoreGraph.observation.stats()
                ObservationStatusDto(
                    enabled = config.enabled,
                    techEnabled = config.techEnabled,
                    techVerbose = config.techVerbose,
                    callsRetentionDays = config.callsRetentionDays.toLong(),
                    callsMaxMb = config.callsMaxMb.toLong(),
                    techRetentionDays = config.techRetentionDays.toLong(),
                    techMaxMb = config.techMaxMb.toLong(),
                    maskByDefault = config.maskByDefault,
                    callsBytes = stats.callsBytes,
                    techBytes = stats.techBytes,
                    dailyBytesEstimate = stats.dailyBytesEstimate,
                    droppedTechLines = CoreGraph.observation.droppedTechLines(),
                    installId = CoreGraph.observationStore.installId(),
                    oldestAt = stats.oldestAt,
                )
            }
            callback(result)
        }
    }

    override fun report(periodDays: Long, callback: (Result<ObservationReportDto>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                // Сводка считается по журналу — значит очередь событий надо сначала слить,
                // иначе только что заблокированный звонок в неё не попадёт.
                CoreGraph.drainPending()
                val report = CoreGraph.observationReporter.report(periodDays.toInt())
                ObservationReportDto(
                    periodDays = report.periodDays.toLong(),
                    checks = report.checks.toLong(),
                    withSignature = report.withSignature.toLong(),
                    withoutName = report.withoutName.toLong(),
                    lateNames = report.lateNames.toLong(),
                    lateSignatures = report.lateSignatures.toLong(),
                    namesAtDecision = report.namesAtDecision.toLong(),
                    hiddenNumbers = report.hiddenNumbers.toLong(),
                    coldStarts = report.coldStarts.toLong(),
                    watchdogFired = report.watchdogFired.toLong(),
                    latencyP50 = report.latencyP50.toLong(),
                    latencyP95 = report.latencyP95.toLong(),
                    latencyMax = report.latencyMax.toLong(),
                    nameSources = report.nameSources.map { BucketDto(it.bucket, it.total.toLong()) },
                    networkTypes = report.networkTypes.map { BucketDto(it.bucket, it.total.toLong()) },
                    volte = report.volte.map { BucketDto(it.bucket, it.total.toLong()) },
                    extrasKeys = report.extrasKeys.map { BucketDto(it.bucket, it.total.toLong()) },
                    signatures = report.signatures.map {
                        SignatureDto(
                            raw = it.raw,
                            total = it.total.toLong(),
                            lastAt = it.lastAt,
                            fold = it.fold,
                        )
                    },
                )
            }
            callback(result)
        }
    }

    override fun setConfig(
        enabled: Boolean,
        techEnabled: Boolean,
        techVerbose: Boolean,
        callsRetentionDays: Long,
        callsMaxMb: Long,
        techRetentionDays: Long,
        techMaxMb: Long,
        maskByDefault: Boolean,
        callback: (Result<Unit>) -> Unit,
    ) {
        bridge.scope.launch {
            val result = runCatching {
                val config = ObservationConfig(
                    enabled = enabled,
                    techEnabled = techEnabled,
                    techVerbose = techVerbose,
                    callsRetentionDays = callsRetentionDays.toInt(),
                    callsMaxMb = callsMaxMb.toInt(),
                    techRetentionDays = techRetentionDays.toInt(),
                    techMaxMb = techMaxMb.toInt(),
                    maskByDefault = maskByDefault,
                )
                // Сначала в DE-хранилище — им пользуется писатель, и он должен увидеть
                // новое значение немедленно, даже если Room недоступен.
                CoreGraph.observationStore.save(config)
                config.toMap().forEach { (key, value) -> CoreGraph.rules.putInternal(key, value) }
            }
            callback(result)
        }
    }

    override fun estimate(fromAt: Long, toAt: Long, callback: (Result<ExportEstimateDto>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                val estimate = CoreGraph.logExporter.estimate(fromAt, toAt)
                ExportEstimateDto(
                    callLines = estimate.callLines.toLong(),
                    archiveBytes = estimate.archiveBytesEstimate,
                )
            }
            callback(result)
        }
    }

    override fun share(
        fromAt: Long,
        toAt: Long,
        mask: Boolean,
        periodLabel: String,
        callback: (Result<Boolean>) -> Unit,
    ) {
        bridge.scope.launch {
            val result = runCatching {
                CoreGraph.drainPending()
                // Сводка строится по окну выгрузки, а не за фиксированные 30 суток:
                // иначе `summary.txt` и `manifest.json` в одном архиве говорят разное.
                val report = CoreGraph.observationReporter.report(since = fromAt)
                val config = CoreGraph.observationStore.config()
                val exported = CoreGraph.logExporter.export(
                    LogExporter.Request(
                        fromAt = fromAt,
                        toAt = toAt,
                        mask = mask,
                        installId = CoreGraph.observationStore.installId(),
                        periodLabel = periodLabel,
                        config = config,
                        summary = report.toText(),
                        manifestExtra = mapOf(
                            "model" to CoreGraph.deviceContext.model,
                            "manufacturer" to CoreGraph.deviceContext.manufacturer,
                            "android" to CoreGraph.deviceContext.androidRelease,
                            "app" to CoreGraph.deviceContext.appVersion,
                            "operator" to report.networkTypes.joinToString(",") { it.bucket },
                        ),
                    )
                )

                val activity = activityProvider() ?: return@runCatching false
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.logs",
                    exported.file,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, exported.file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Выбор приложения делает пользователь: отправить логи само приложение
                // не может и не должно (ТЗ §7.7.3 п. 4).
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_logs_title)))
                true
            }
            callback(result)
        }
    }

    override fun deleteLogs(callback: (Result<Long>) -> Unit) {
        bridge.scope.launch {
            callback(runCatching { CoreGraph.observation.deleteAll().toLong() })
        }
    }

}

/**
 * Диагностика (ТЗ §9.7).
 *
 * Отчёт собирается целиком на стороне Kotlin, включая готовый текст для копирования: собирать
 * его в Dart значило бы держать в интерфейсе вторую версию того, что считается важным, и она
 * начала бы расходиться с первой.
 */
internal class DiagnosticsApiImpl(
    private val bridge: BridgeScope,
    private val activityProvider: () -> Activity?,
) : DiagnosticsApi {

    override fun report(callback: (Result<DiagnosticsDto>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                CoreGraph.drainPending()
                val report = CoreGraph.diagnostics.report()
                val context = activityProvider() ?: CoreGraph.deviceEncrypted
                val role = RoleController(context)
                val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                    "Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.SDK_INT}), " +
                    "прошивка ${android.os.Build.DISPLAY}"

                val permissions = listOfNotNull(
                    "журнал звонков: ${yesNo(role.hasPermission(android.Manifest.permission.READ_CALL_LOG))}",
                    "контакты: ${yesNo(role.hasPermission(android.Manifest.permission.READ_CONTACTS))}",
                    "телефон: ${yesNo(role.hasPermission(android.Manifest.permission.READ_PHONE_STATE))}",
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        "уведомления: ${yesNo(role.hasPermission(android.Manifest.permission.POST_NOTIFICATIONS))}"
                    } else {
                        null
                    },
                ).joinToString(", ")

                DiagnosticsDto(
                    droppedPendingEvents = report.droppedPendingEvents,
                    checksLast7Days = report.checksLast7Days.toLong(),
                    latencyP50 = report.latencyP50.toLong(),
                    latencyP95 = report.latencyP95.toLong(),
                    latencyMax = report.latencyMax.toLong(),
                    degradedCounts = report.degradedCounts.map { BucketDto(it.bucket, it.total.toLong()) },
                    ruleErrors = report.ruleErrors.map {
                        RuleErrorDto(it.title, it.errorCount.toLong(), it.lastError)
                    },
                    lastEvents = report.lastEvents.map {
                        EventLineDto(
                            occurredAt = it.occurredAt,
                            number = it.number,
                            action = it.action,
                            reason = it.reason,
                            latencyMs = it.latencyMs.toLong(),
                            coldStart = it.coldStart,
                        )
                    },
                    nameSources = report.nameSources.map { BucketDto(it.bucket, it.total.toLong()) },
                    withSignatureLast100 = report.withSignatureLast100.toLong(),
                    checkedLast100 = report.checkedLast100.toLong(),
                    volte = report.volte.map { BucketDto(it.bucket, it.total.toLong()) },
                    signatureLooksUnavailable = report.signatureLooksUnavailable,
                    device = device,
                    reportText = report.toText(
                        device = device,
                        role = yesNo(role.hasRole()),
                        permissions = permissions,
                    ),
                    snapshotFormatVersion = report.snapshotFormatVersion?.toLong(),
                    snapshotCanonVersion = report.snapshotCanonVersion?.toLong(),
                    snapshotRuleCount = report.snapshotRuleCount?.toLong(),
                    snapshotBuiltAt = report.snapshotBuiltAt,
                    snapshotError = report.snapshotError,
                    batteryUnrestricted = batteryUnrestricted(context),
                )
            }
            callback(result)
        }
    }

    override fun testRun(number: String, name: String?, callback: (Result<TestRunDto>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                val run = CoreGraph.diagnostics.testRun(number, name)
                TestRunDto(
                    digits = run.digits,
                    candidates = run.candidates,
                    nameNorm = run.nameNorm,
                    nameFold = run.nameFold,
                    orgFold = run.orgFold,
                    action = run.action,
                    reason = run.reason,
                    elapsedMicros = run.elapsedMicros,
                    steps = run.steps.map {
                        TraceStepDto(
                            ruleId = it.ruleId,
                            title = it.title,
                            target = it.target,
                            matchType = it.matchType,
                            canonical = it.canonical,
                            patterns = it.patterns,
                            matched = it.matched,
                            skippedReason = it.skippedReason,
                        )
                    },
                    snapshotMissing = run.snapshotMissing,
                    e164 = run.e164,
                    categoryFold = run.categoryFold,
                    matchedRuleId = run.matchedRuleId,
                    matchedRuleTitle = run.matchedRuleTitle,
                )
            }
            callback(result)
        }
    }

    override fun openBatterySettings() {
        val activity = activityProvider() ?: return
        // Сначала общий экран оптимизации батареи, а не запрос исключения для себя: запрос
        // исключения без явного повода — путь к отклонению приложения и к раздражению.
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
            )
        }
    }

    private fun yesNo(value: Boolean) = if (value) "есть" else "нет"

    private fun batteryUnrestricted(context: android.content.Context): Boolean? = runCatching {
        context.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull()
}

/**
 * Обновление приложения (ТЗ §15.5).
 *
 * Живёт в мосте, а не в `:core`: модуль обновления — единственный с сетью, и у него нет доступа
 * ни к правилам, ни к журналу. Здесь только перевод результатов в контракт интерфейса.
 *
 * Ошибки возвращаются значением и **никогда** не показываются всплывающим окном: автопроверка
 * при запуске обязана быть тихой, а статус виден только на своём экране (ТЗ §15.5).
 */
internal class UpdaterApiImpl(
    private val bridge: BridgeScope,
    private val activityProvider: () -> Activity?,
) : UpdaterApi {

    override fun check(
        allowPrerelease: Boolean,
        silent: Boolean,
        callback: (Result<UpdateStatusDto>) -> Unit,
    ) {
        bridge.scope.launch {
            val result = runCatching {
                val manager = manager() ?: return@runCatching failure("нет контекста приложения")
                when (val checked = manager.check(allowPrerelease)) {
                    is UpdateCheckResult.Available -> UpdateStatusDto(
                        state = "AVAILABLE",
                        currentVersion = currentVersion(),
                        version = checked.manifest.version,
                        build = checked.manifest.build.toLong(),
                        notesUrl = checked.manifest.notesUrl,
                        error = null,
                        sizeBytes = checked.asset.size,
                    )

                    UpdateCheckResult.UpToDate -> UpdateStatusDto(
                        state = "UP_TO_DATE",
                        currentVersion = currentVersion(),
                    )

                    is UpdateCheckResult.Failure -> UpdateStatusDto(
                        state = "FAILURE",
                        currentVersion = currentVersion(),
                        notesUrl = checked.notesUrl,
                        // Даже при тихой автопроверке причина возвращается: показывать её
                        // или нет, решает интерфейс, а не мост.
                        error = checked.reason,
                    )
                }
            }
            callback(result)
        }
    }

    override fun install(allowPrerelease: Boolean, callback: (Result<String?>) -> Unit) {
        bridge.scope.launch {
            val result = runCatching {
                val manager = manager() ?: return@runCatching "нет контекста приложения"
                when (val checked = manager.check(allowPrerelease)) {
                    is UpdateCheckResult.Available -> when (
                        val installed = manager.downloadAndInstall(checked)
                    ) {
                        InstallResult.Started -> null
                        is InstallResult.Failure -> installed.reason
                    }

                    UpdateCheckResult.UpToDate -> "обновление не требуется"
                    is UpdateCheckResult.Failure -> checked.reason
                }
            }
            callback(result)
        }
    }

    override fun openReleasePage(url: String) {
        val activity = activityProvider() ?: return
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun manager(): UpdateManager? {
        val context = activityProvider() ?: return null
        return Updater.create(context, statusAction = INSTALL_STATUS_ACTION)
    }

    private fun currentVersion(): String =
        runCatching { CoreGraph.deviceContext.appVersion }.getOrDefault("unknown")

    private fun failure(reason: String) = UpdateStatusDto(
        state = "FAILURE",
        currentVersion = currentVersion(),
        error = reason,
    )

    internal companion object {
        /** По этому действию система сообщит исход установки; приёмник объявляет `:app`. */
        const val INSTALL_STATUS_ACTION: String = "com.mist3s.nopecall.INSTALL_STATUS"
    }
}

internal class SettingsApiImpl(private val bridge: BridgeScope) : SettingsApi {

    override fun all(callback: (Result<Map<String, String>>) -> Unit) {
        bridge.scope.launch {
            callback(runCatching { CoreGraph.rules.allSettings() })
        }
    }

    override fun put(key: String, value: String, callback: (Result<Unit>) -> Unit) {
        bridge.scope.launch {
            callback(
                runCatching {
                    if (key in NotifyConfig.KEYS) {
                        // Настройки уведомлений на решение не влияют: снимок не пересобирается.
                        // Зато зеркалятся в DE-хранилище — уведомитель работает и до первой
                        // разблокировки экрана, когда Room недоступен.
                        CoreGraph.rules.putInternal(key, value)
                        val stored = CoreGraph.rules.allSettings()
                        CoreGraph.notifyStore.save(NotifyConfig.fromMap(stored))
                    } else {
                        // Настройка меняет решение по звонку, поэтому пересборка снимка
                        // обязательна — она внутри putSetting.
                        CoreGraph.rules.putSetting(key, value)
                    }
                }
            )
        }
    }
}
