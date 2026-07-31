package com.mist3s.nopecall.updater

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build

/**
 * Чтение сведений об установленной копии из системы (ТЗ §15.5).
 *
 * Читается **при каждой проверке**, а не один раз при создании: после самообновления процесс
 * переживает замену APK (`MY_PACKAGE_REPLACED`, ТЗ §15.5), и закэшированная версия сделала бы
 * предложение установить только что установленное.
 */
public object AndroidInstalledApp {

    public fun read(context: Context): InstalledApp {
        val packageName = context.packageName
        val pm = context.packageManager

        // Каждое обращение в runCatching: на вендорских прошивках getPackageInfo с флагом подписей
        // встречается падающим, а обновление — не та функция, ради которой можно уронить процесс.
        // Провал даёт пустой список отпечатков, а он трактуется как отказ от установки.
        val info = runCatching {
            @Suppress("DEPRECATION") // GET_SIGNING_CERTIFICATES: PackageInfoFlags только с API 33
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()

        return InstalledApp(
            versionName = info?.versionName ?: "",
            versionCode = info?.longVersionCode ?: 0L,
            signingCertsSha256 = signatures(info?.signingInfo?.let(::allSignatures)),
            deviceSdkInt = Build.VERSION.SDK_INT,
            // Список копируется: массивы Build мутабельны, а решение по ABI должно быть
            // неизменяемым снимком, как и всё остальное в InstalledApp.
            supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
        )
    }

    /**
     * Все сертификаты, которыми наша копия может быть законно подписана.
     *
     * `hasMultipleSigners` и `signingCertificateHistory` — не украшение: при ротации ключа (v3)
     * установленная копия подписана новым ключом, а релиз мог быть выпущен ещё старым, и наоборот.
     * Сравнение только с `apkContentsSigners` сделало бы обновление невозможным ровно в тот
     * момент, когда ключ пришлось менять.
     */
    private fun allSignatures(signingInfo: android.content.pm.SigningInfo): Array<Signature> =
        if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners ?: emptyArray()
        } else {
            signingInfo.signingCertificateHistory ?: emptyArray()
        }

    private fun signatures(signatures: Array<Signature>?): List<String> =
        signatures.orEmpty().mapNotNull { signature ->
            runCatching { Digests.sha256Hex(signature.toByteArray()) }.getOrNull()
        }
}
