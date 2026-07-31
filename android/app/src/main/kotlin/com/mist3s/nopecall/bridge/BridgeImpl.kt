package com.mist3s.nopecall.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.mist3s.nopecall.core.CoreGraph
import com.mist3s.nopecall.core.role.RoleController
import com.mist3s.nopecall.core.storage.RulesRepository
import com.mist3s.nopecall.core.storage.SaveResult
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

    override fun openAppSettings() {
        val activity = activityProvider() ?: return
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
        )
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
        lastScreeningAt = null,
    )

    private fun <T> runCatchingBlocking(block: suspend () -> T): T? = try {
        kotlinx.coroutines.runBlocking { block() }
    } catch (_: Throwable) {
        null
    }
}

internal class RulesApiImpl(private val bridge: BridgeScope) : RulesApi {

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
            return PatternCheckResult(false, "", emptyList(), false, "неизвестный тип правила")
        }
        return when (val check = CoreGraph.rules.validate(target, match, pattern)) {
            is PatternCheck.Ok -> PatternCheckResult(
                valid = true,
                canonical = check.canonical,
                variants = check.variants,
                variantsTruncated = check.variantsTruncated,
                error = null,
            )

            is PatternCheck.Invalid ->
                PatternCheckResult(false, "", emptyList(), false, check.reason)

            is PatternCheck.TooExpensive ->
                PatternCheckResult(false, "", emptyList(), false, check.reason)
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
                val target = enumOf<RuleTarget>(targetType) ?: return@runCatching PreviewDto(0, false)
                val match = enumOf<MatchType>(matchType) ?: return@runCatching PreviewDto(0, false)
                val check = CoreGraph.rules.validate(target, match, pattern)
                val canonical = (check as? PatternCheck.Ok)?.canonical
                    ?: return@runCatching PreviewDto(0, false)

                val preview = CoreGraph.journal.previewMatches(targetType, matchType, canonical)
                PreviewDto(preview.count.toLong(), preview.truncated)
            }
            callback(result)
        }
    }

    private fun rejected(reason: String) =
        SaveRuleResult(saved = false, id = 0, variants = emptyList(), variantsTruncated = false, error = reason)

    private inline fun <reified T : Enum<T>> enumOf(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}

internal class JournalApiImpl(private val bridge: BridgeScope) : JournalApi {

    override fun page(
        beforeTime: Long?,
        beforeId: Long?,
        limit: Long,
        callback: (Result<JournalPageDto>) -> Unit,
    ) {
        bridge.scope.launch {
            val result = runCatching {
                // Сначала переносим события из очереди: иначе только что заблокированный
                // звонок не появился бы в журнале до перезапуска приложения.
                CoreGraph.drainPending()
                val page = CoreGraph.journal.page(beforeTime, beforeId, limit.toInt())
                JournalPageDto(
                    items = page.items.map { item ->
                        JournalItemDto(
                            id = item.id,
                            occurredAt = item.occurredAt,
                            rawNumber = item.rawNumber,
                            nameSource = item.nameSource,
                            action = item.action,
                            reason = item.reason,
                            latencyMs = item.latencyMs.toLong(),
                            blockedByUs = item.blockedByUs,
                            hadSignature = item.hadSignature,
                            e164 = item.e164,
                            nameRaw = item.nameRaw,
                            matchedRuleId = item.matchedRuleId,
                            matchedRuleTitle = item.matchedRuleTitle,
                        )
                    },
                    hasMore = page.hasMore,
                    nextBeforeTime = page.nextBeforeTime,
                    nextBeforeId = page.nextBeforeId,
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
                    lastEventAt = s.lastEventAt,
                )
            }
            callback(result)
        }
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
            // Настройка меняет решение по звонку, поэтому пересборка снимка обязательна —
            // она внутри putSetting.
            callback(runCatching { CoreGraph.rules.putSetting(key, value) })
        }
    }
}
