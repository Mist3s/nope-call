package com.mist3s.nopecall.core.calllog

import com.mist3s.nopecall.core.storage.NopeCallDatabase
import com.mist3s.nopecall.engine.NameCanonizer
import com.mist3s.nopecall.engine.PhoneNumberNormalizer
import com.mist3s.nopecall.engine.RuleSnapshot

/** Итог синхронизации. Числа нужны диагностике, а не только логам. */
public data class SyncResult(
    val fetched: Int,
    val stitched: Int,
    val lateNames: Int,
    val available: Boolean,
) {
    public companion object {
        public val UNAVAILABLE: SyncResult = SyncResult(0, 0, 0, available = false)
    }
}

/**
 * Синхронизация зеркала системного журнала и сшивка со своими событиями (ТЗ §7.2, §7.3).
 *
 * Зеркало нужно потому, что `CallScreeningService` вызывается один раз, **до** звонка: он не
 * узнаёт ни исхода, ни длительности, а исходящие в него не приходят вообще. Всё это есть только
 * в системном журнале.
 *
 * Синхронизация **постоянная, а не однократный импорт**: система дописывает запись после звонка —
 * длительность появляется по завершении, имя может заполниться позже, тип может измениться.
 * Поэтому вставка идёт через `ON CONFLICT DO UPDATE`, а окно перекрывается на сутки.
 */
public class CallLogSyncer(
    private val db: NopeCallDatabase,
    private val source: CallLogSource,
    private val normalizer: PhoneNumberNormalizer,
    private val region: String = "RU",
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Наблюдатель поздних имён (ТЗ §7.7.1).
     *
     * Лямбдой, а не прямой ссылкой на журнал наблюдения: сшивка — самая тонкая часть журнала,
     * и её тесты не должны тащить за собой файловую систему.
     */
    private val onLateName: (occurredAt: Long, digits: String, nameRaw: String, nameFold: String) -> Unit =
        { _, _, _, _ -> },
) {
    public suspend fun sync(pageSize: Int = PAGE_SIZE, maxPages: Int = MAX_PAGES): SyncResult {
        if (!source.isAvailable()) return SyncResult.UNAVAILABLE

        val watermark = db.mirror().watermark()
        // Перекрытие в сутки: запись, дописанная системой после звонка, обязана попасть
        // в зеркало снова, иначе длительность и позднее имя не появятся никогда.
        val since = if (watermark == null) 0L else watermark - OVERLAP_MS

        var fetched = 0
        var cursor: Long? = null
        val syncedAt = now()

        repeat(maxPages) {
            val page = source.query(since, cursor, pageSize)
            if (page.isEmpty()) return@repeat
            for (row in page) {
                upsert(row, syncedAt)
                fetched++
            }
            cursor = page.last().dateMillis
            if (page.size < pageSize) return@repeat
        }

        val stitch = stitch()
        return SyncResult(fetched, stitch.first, stitch.second, available = true)
    }

    private suspend fun upsert(row: CallLogRow, syncedAt: Long) {
        val forms = normalizer.normalize(row.number, region)
        val name = row.cachedName?.takeIf { it.isNotBlank() }
        db.mirror().upsert(
            systemId = row.systemId,
            startedAt = row.dateMillis,
            rawNumber = row.number.orEmpty(),
            // Каноническая форма, а не «как пришло»: сшивка и фильтры сравнивают её
            // с канонизированным шаблоном, а системный журнал и Call.Details могут отдать
            // один и тот же номер в разных видах (ТЗ §6.2.1).
            digits = forms.canonicalDigits.ifEmpty { forms.digits },
            e164 = forms.e164,
            name = name,
            nameFold = name?.let { NameCanonizer.canonize(it).whole.fold.ifEmpty { null } },
            type = CallType.fromSystem(row.type),
            durationSeconds = row.durationSeconds,
            phoneAccountId = row.phoneAccountId,
            syncedAt = syncedAt,
            canonVersion = RuleSnapshot.CURRENT_CANON_VERSION,
        )
    }

    /**
     * Сшивка событий проверки с записями зеркала.
     *
     * @return пара «сшито, дописано поздних имён»
     *
     * Позднее имя — это не мелочь, а **главный измеряемый показатель** проекта: если подпись
     * появилась в системном журнале, но её не было в момент проверки, значит оператор досылает
     * её после решения, и правила по названию на таких звонках работать не могут (ТЗ §21 п. 4).
     */
    private suspend fun stitch(): Pair<Int, Int> {
        var stitched = 0
        var lateNames = 0

        // Сшиваем только свежие события: у старых запись зеркала либо уже нашлась, либо
        // не появится никогда, и перебирать их на каждой синхронизации незачем.
        for (event in db.events().recent(STITCH_WINDOW)) {
            if (event.matchedSystemId != null) continue
            if (event.digits.isEmpty()) continue // скрытый номер сшивать нечем (ТЗ §7.3)

            val match = db.mirror().findForStitching(
                digits = event.digits,
                occurredAt = event.occurredAt,
                windowMs = STITCH_WINDOW_MS,
            ) ?: continue

            db.events().attachSystemId(event.id, match.systemId)
            stitched++

            // Название, ставшее известным позже. Дописывается только если своего не было:
            // операторская подпись, полученная в момент проверки, ценнее системного имени.
            val name = match.name
            if (name != null && event.nameRaw.isNullOrEmpty()) {
                val fold = NameCanonizer.canonize(name).whole.fold
                db.events().attachLateName(eventId = event.id, nameRaw = name, nameFold = fold)
                lateNames++
                // Связанная запись в поток A: прямое доказательство того, что подпись
                // досылается после решения, — то есть ответ на главный вопрос §21 п. 4.
                runCatching { onLateName(event.occurredAt, event.digits, name, fold) }
            }
        }
        return stitched to lateNames
    }

    public companion object {
        public const val PAGE_SIZE: Int = 500

        /** Предел страниц за один проход: первичная выгрузка не должна работать бесконечно. */
        public const val MAX_PAGES: Int = 40

        /** Перекрытие окна: система дописывает запись после звонка (ТЗ §7.2). */
        public const val OVERLAP_MS: Long = 24L * 60 * 60 * 1000

        /** Окно сшивки по времени (ТЗ §7.3). */
        public const val STITCH_WINDOW_MS: Long = 20_000

        /** Сколько последних событий проверять на сшивку. */
        public const val STITCH_WINDOW: Int = 200
    }
}
