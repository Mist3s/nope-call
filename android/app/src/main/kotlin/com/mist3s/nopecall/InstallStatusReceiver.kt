package com.mist3s.nopecall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Исход установки обновления от `PackageInstaller` (ТЗ §15.5).
 *
 * Без этого приёмника обновление выглядело как «ничего не произошло»: сессия коммитится,
 * система решает, что нужно подтверждение пользователя, и присылает `STATUS_PENDING_USER_ACTION`
 * вместе с интентом диалога — **в широковещательное сообщение**. Получателя не было, диалог
 * не показывался, сессия молча ждала, а интерфейс возвращал кнопку «Обновить», потому что
 * установщик честно ответил «начато».
 *
 * Живёт в `:app`, а не в `:updater`: после `MY_PACKAGE_REPLACED` надо перепроверить роль
 * и пересобрать снимок, а это про `:core`, о котором `:updater` не знает и знать не должен
 * (архитектура §2).
 */
internal class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Диалог подтверждения. `FLAG_ACTIVITY_NEW_TASK` обязателен: активность
                // запускается из приёмника, у которого своей задачи нет.
                val confirm = confirmationIntent(intent)
                if (confirm == null) {
                    Log.w(TAG, "система просит подтверждение, но интента диалога в сообщении нет")
                    InstallOutcome.set("система не передала диалог подтверждения установки")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val launched = runCatching { context.startActivity(confirm) }.isSuccess
                if (!launched) {
                    InstallOutcome.set("не удалось открыть диалог подтверждения установки")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // Дальше работает `MY_PACKAGE_REPLACED`: там пересборка снимка и проверка роли.
                Log.i(TAG, "обновление установлено")
                InstallOutcome.clear()
            }

            else -> {
                // Отказ обязан быть видимым: пользователь нажал «Обновить» и вправе узнать,
                // почему ничего не произошло. Текст системы сохраняется дословно.
                val reason = message?.takeIf { it.isNotBlank() } ?: kindOf(status)
                Log.w(TAG, "установка не удалась: status=$status, $reason")
                InstallOutcome.set(reason)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }

    /** Человекочитаемый род отказа, когда система не прислала текст. */
    private fun kindOf(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "установка отменена"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "установку заблокировала система"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "конфликт с уже установленной копией: подпись или версия не совпадают"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "сборка несовместима с этим устройством"
        PackageInstaller.STATUS_FAILURE_INVALID -> "файл обновления повреждён"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "не хватило места на устройстве"
        else -> "установка не удалась (код $status)"
    }

    private companion object {
        const val TAG = "NopeCallInstall"
    }
}

/**
 * Последний исход установки — чтобы отказ дошёл до экрана.
 *
 * В памяти процесса: приёмник и интерфейс живут в одном процессе, а при успешной установке
 * процесс всё равно перезапускается. Держать это в хранилище незачем — устаревшая запись
 * об отказе пугала бы после удачной установки.
 */
internal object InstallOutcome {
    @Volatile
    private var failure: String? = null

    fun set(reason: String) {
        failure = reason
    }

    fun clear() {
        failure = null
    }

    /** Забирает исход: показывается один раз, повторно всплывать не должен. */
    fun take(): String? {
        val current = failure
        failure = null
        return current
    }
}
