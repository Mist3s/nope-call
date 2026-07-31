package com.mist3s.nopecall.updater

/**
 * Общие данные для тестов апдейтера.
 *
 * `latest.json` собран из примера в ТЗ §15.5 буквально, включая формат отпечатка `AB:CD:…`:
 * тесты обязаны падать, если формат манифеста разойдётся с документом.
 */
internal object Fixtures {

    /** Отпечаток сертификата в том виде, в каком его печатает `apksigner` (ТЗ §15.4). */
    const val CERT_PRETTY: String =
        "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:" +
            "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"

    /** Тот же отпечаток в виде, в котором его отдаёт `PackageManager`. */
    const val CERT_INSTALLED: String =
        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

    /** Sha256 от [APK_BYTES], посчитанный сторонним `sha256sum`, а не нашим кодом. */
    const val APK_SHA256: String = "a26d7e40b4a722657e217a576f4ee5845fad3e316767723c068a5900df3aa66b"

    const val APK_BYTES: String = "nope-call-apk-bytes"

    /**
     * Подменённое содержимое **той же длины**, что и [APK_BYTES].
     *
     * Длина совпадает намеренно: иначе отказ давала бы проверка размера, а проверка sha256
     * так и осталась бы непроверенной. Подмена файла с сохранением размера — это ровно тот
     * случай, для которого контрольная сумма и нужна.
     */
    const val APK_BYTES_TAMPERED: String = "nope-call-apk-bytez"

    fun manifestJson(
        version: String = "1.2.3",
        build: Long = 45,
        prerelease: Boolean = false,
        minAndroidSdk: Int = 29,
        cert: String = CERT_PRETTY,
        assets: String = ASSETS,
    ): String = """
        {
          "version": "$version",
          "build": $build,
          "prerelease": $prerelease,
          "min_android_sdk": $minAndroidSdk,
          "notes_url": "https://github.com/Mist3s/nope-call/releases/tag/v$version",
          "signing_cert_sha256": "$cert",
          "assets": [$assets]
        }
    """.trimIndent()

    private const val ASSETS = """
        { "abi": "arm64-v8a",   "url": "https://github.com/Mist3s/nope-call/releases/download/v1.2.3/nope-call-1.2.3-arm64-v8a.apk",   "size": 19, "sha256": "$APK_SHA256" },
        { "abi": "armeabi-v7a", "url": "https://github.com/Mist3s/nope-call/releases/download/v1.2.3/nope-call-1.2.3-armeabi-v7a.apk", "size": 19, "sha256": "$APK_SHA256" },
        { "abi": "universal",   "url": "https://github.com/Mist3s/nope-call/releases/download/v1.2.3/nope-call-1.2.3-universal.apk",   "size": 19, "sha256": "$APK_SHA256" }
    """

    fun installed(
        versionName: String = "1.2.2",
        versionCode: Long = 44,
        certs: List<String> = listOf(CERT_INSTALLED),
        sdkInt: Int = 29,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    ): InstalledApp = InstalledApp(
        versionName = versionName,
        versionCode = versionCode,
        signingCertsSha256 = certs,
        deviceSdkInt = sdkInt,
        supportedAbis = abis,
    )
}
