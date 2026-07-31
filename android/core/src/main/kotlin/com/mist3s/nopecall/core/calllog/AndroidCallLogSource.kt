package com.mist3s.nopecall.core.calllog

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log

/**
 * Системный журнал звонков через `ContentResolver`.
 *
 * Единственное место, где живёт `CallLog`. Вся логика синхронизации и сшивки — в [CallLogSyncer]
 * и потому проверяется без устройства (архитектура §12.1).
 *
 * Вызывается **не** из горячего пути: обращение к `ContentProvider` во время звонка запрещено.
 */
internal class AndroidCallLogSource(private val context: Context) : CallLogSource {

    override fun isAvailable(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    override fun query(sinceMillis: Long, afterDate: Long?, limit: Int): List<CallLogRow> {
        if (!isAvailable()) return emptyList()

        val selection = StringBuilder("${CallLog.Calls.DATE} >= ?")
        val args = mutableListOf(sinceMillis.toString())
        if (afterDate != null) {
            selection.append(" AND ${CallLog.Calls.DATE} > ?")
            args += afterDate.toString()
        }

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.DATE,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                ),
                selection.toString(),
                args.toTypedArray(),
                // Только порядок, БЕЗ `LIMIT`. Классический приём «дописать LIMIT в sortOrder»
                // провайдер журнала звонков отвергает: он разбирает запрос в строгом режиме
                // и бросает `IllegalArgumentException: Invalid token LIMIT`. Отказ при этом
                // выглядел как «в системном журнале ничего нет» — зеркало молча оставалось
                // пустым при выданном разрешении.
                //
                // Ограничение накладывается чтением: курсор наполняется провайдером порциями,
                // поэтому выход из цикла на нужной строке не тянет весь журнал в память.
                "${CallLog.Calls.DATE} ASC",
            )?.use { cursor ->
                buildList(minOf(cursor.count, limit)) {
                    while (size < limit && cursor.moveToNext()) {
                        add(
                            CallLogRow(
                                systemId = cursor.getLong(0),
                                dateMillis = cursor.getLong(1),
                                number = cursor.getString(2),
                                cachedName = cursor.getString(3),
                                type = cursor.getInt(4),
                                durationSeconds = cursor.getInt(5),
                                phoneAccountId = cursor.getString(6),
                            )
                        )
                    }
                }
            } ?: emptyList()
        } catch (t: Throwable) {
            // Отказ провайдера не должен ронять приложение: журнал просто останется
            // без слоя зеркала, и интерфейс об этом честно скажет.
            Log.w(TAG, "не удалось прочитать системный журнал", t)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "NopeCallCallLog"
    }
}
