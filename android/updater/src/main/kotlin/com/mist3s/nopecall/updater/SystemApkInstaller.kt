package com.mist3s.nopecall.updater

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Установка через `PackageInstaller` (ТЗ §15.5).
 *
 * Приложение не может подменить себя молча: сессия только передаёт файл системе, а решение
 * принимает пользователь в системном диалоге. Ни `INSTALL_PACKAGES`, ни root, ни `pm install`
 * здесь не используются и использоваться не могут — это осознанное свойство доставки, а не
 * ограничение реализации.
 *
 * @param statusIntentSender куда система пришлёт исход установки. Передаёт хост: приёмник живёт
 *   в `:app` (после `MY_PACKAGE_REPLACED` нужно перепроверить роль и пересобрать снимок, ТЗ §15.5),
 *   а `:updater` про `:app` не знает и знать не должен.
 */
public class SystemApkInstaller(
    private val context: Context,
    private val statusIntentSender: (sessionId: Int) -> IntentSender,
) : ApkInstaller {

    override fun install(apk: File): InstallResult {
        if (!apk.isFile || apk.length() == 0L) {
            return InstallResult.Failure(
                UpdateFailureKind.STORAGE,
                "файл обновления исчез до установки",
            )
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // Имя пакета задаётся явно: система тогда сразу показывает диалог обновления
            // нашего приложения, а не «установить неизвестное приложение».
            setAppPackageName(context.packageName)
        }

        var sessionId = -1
        var session: PackageInstaller.Session? = null
        return try {
            sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            writeApk(session, apk)
            session.commit(statusIntentSender(sessionId))
            // Сессия закрывается, но не отменяется: она живёт до ответа пользователя в диалоге.
            session.close()
            session = null
            InstallResult.Started
        } catch (e: IOException) {
            // Самая частая причина — нет места под копию APK внутри сессии: система копирует
            // файл к себе, то есть на время установки нужно вдвое больше свободного места.
            abandon(installer, sessionId)
            InstallResult.Failure(
                UpdateFailureKind.STORAGE,
                "не удалось передать файл установщику: ${e.message ?: e.javaClass.simpleName}",
            )
        } catch (e: SecurityException) {
            abandon(installer, sessionId)
            InstallResult.Failure(
                UpdateFailureKind.INSTALL,
                "система не разрешила установку: ${e.message ?: "нужно разрешить установку из этого источника"}",
            )
        } finally {
            runCatching { session?.close() }
        }
    }

    private fun writeApk(session: PackageInstaller.Session, apk: File) {
        // Размер передаётся явно: без него система не может заранее сказать, хватит ли места,
        // и отказ приходит уже посреди копирования.
        session.openWrite(APK_ENTRY, 0, apk.length()).use { out ->
            apk.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            // fsync сессии: без него часть байт остаётся в буфере, и система видит обрезанный
            // APK — отказ «package parse error» вместо диалога установки.
            session.fsync(out)
        }
    }

    /** Брошенная сессия занимает место в системном хранилище до перезагрузки. */
    private fun abandon(installer: PackageInstaller, sessionId: Int) {
        if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
    }

    public companion object {
        private const val APK_ENTRY = "nope-call.apk"

        /**
         * Готовый `IntentSender` на широковещательный приёмник хоста.
         *
         * `FLAG_MUTABLE` обязателен с Android 12: система дописывает в интент результат
         * установки (`EXTRA_STATUS`, и при `STATUS_PENDING_USER_ACTION` — интент подтверждения).
         * С неизменяемым `PendingIntent` приёмник получил бы пустой интент, и установка
         * выглядела бы как «ничего не произошло». До Android 12 флага не существует, а интент
         * изменяем по умолчанию — поэтому проверка версии, а не безусловная константа.
         *
         * Интент **явный** — с компонентом приёмника, а не только с именем пакета. Причина
         * не в стиле: с Android 14 (`targetSdk` ≥ 34) изменяемый `PendingIntent` с неявным
         * интентом запрещён и бросает `IllegalArgumentException`, а `setPackage` неявность
         * не снимает. То есть на современных прошивках обновление отказывало бы ещё до
         * создания сессии. Заодно явный интент невозможно перехватить чужим приёмником.
         *
         * @param receiver приёмник хоста. `:updater` не знает про `:app` и получает его извне.
         */
        public fun broadcastStatusSender(
            context: Context,
            receiver: ComponentName,
            action: String,
        ): (Int) -> IntentSender = { sessionId ->
            val mutable =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(action).setComponent(receiver),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            ).intentSender
        }
    }
}
