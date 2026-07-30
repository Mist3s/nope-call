package com.mist3s.nopecall.core.screening

import android.telecom.CallScreeningService.CallResponse
import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.Decision

/**
 * Отображение действия правила в ответ системе — таблица ТЗ §5.2.
 *
 * | действие  | disallow | reject | silence | что слышит звонящий              |
 * |-----------|----------|--------|---------|----------------------------------|
 * | REJECT    | true     | true   | —       | сброс сразу                      |
 * | DROP      | true     | false  | —       | гудки до таймаута/голосовой почты|
 * | SILENCE   | false    | false  | true    | обычные гудки, телефон молчит    |
 * | ALLOW     | false    | false  | false   | обычно                           |
 *
 * `setSkipCallLog` не используется: подавление системной записи доступно не всякому
 * приложению проверки, а «заблокированный звонок остаётся в системной истории» — целевое
 * поведение (ТЗ §4.2). Заблокированные звонки система пишет типом `BLOCKED_TYPE`.
 *
 * `setSkipNotification` включается для блокирующих действий: уведомление о пропущенном от
 * заблокированного номера пользователю не нужно, вместо него приходит наше — если включено.
 */
internal fun Decision.toCallResponse(): CallResponse {
    val builder = CallResponse.Builder()
    when (action) {
        CallAction.REJECT -> builder
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipNotification(true)

        CallAction.DROP -> builder
            .setDisallowCall(true)
            .setRejectCall(false)
            .setSkipNotification(true)

        CallAction.SILENCE -> builder
            .setSilenceCall(true)

        CallAction.ALLOW -> builder
    }
    return builder.build()
}
