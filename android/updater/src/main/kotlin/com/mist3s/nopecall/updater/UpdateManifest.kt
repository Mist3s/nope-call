package com.mist3s.nopecall.updater

/**
 * `latest.json` разобрать не удалось (ТЗ §15.5).
 *
 * Отдельный публичный тип, а не общий `IOException`: интерфейсу важно различать «сети нет»
 * и «релиз опубликован с битым манифестом» — первое проходит само, второе чинит человек.
 */
public class MalformedManifestException(message: String) : Exception(message)

/**
 * Файл APK одного ABI из релиза (ТЗ §15.5).
 *
 * @property abi ABI сборки либо `universal`
 * @property url прямая ссылка на файл релиза
 * @property size размер в байтах; сверяется с фактически скачанным
 * @property sha256 контрольная сумма файла в шестнадцатеричном виде
 */
public data class UpdateAsset(
    val abi: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

/**
 * Манифест последнего релиза, формат из ТЗ §15.5.
 *
 * Разбирается собственным читателем JSON (см. [MiniJson]) — без внешних библиотек, как требует
 * задание, и без `org.json`, чтобы разбор проверялся на голой JVM.
 *
 * @property version `versionName`, он же имя тега без `v`
 * @property build `versionCode` базовой сборки, до смещения по ABI (ТЗ §15.2)
 * @property prerelease предвыпуск: игнорируется, если пользователь не включил галочку
 * @property minAndroidSdk минимальный `SDK_INT`, на котором сборка устанавливается
 * @property notesUrl страница релиза; нужна и как запасной путь установки вручную
 * @property signingCertSha256 отпечаток сертификата подписи релиза
 * @property assets файлы релиза; пустым быть не может
 */
public data class UpdateManifest(
    val version: String,
    val build: Long,
    val prerelease: Boolean,
    val minAndroidSdk: Int,
    val notesUrl: String?,
    val signingCertSha256: String,
    val assets: List<UpdateAsset>,
) {

    /** Версия как semver либо `null`, если в релизе она записана не по правилам (ТЗ §15.2). */
    public val semVer: SemVer? get() = SemVer.parseOrNull(version)

    /**
     * Файл под ABI устройства с падением на `universal` (ТЗ §15.5).
     *
     * Порядок [supportedAbis] значим: `Build.SUPPORTED_ABIS` отдаёт ABI по убыванию
     * предпочтения, и на 64-битном устройстве там есть и 32-битный вариант. Брать первое
     * совпадение по списку устройства, а не первое совпадение по списку манифеста —
     * иначе на arm64 установилась бы armeabi-v7a сборка, если она в манифесте выше.
     *
     * @return `null`, если под устройство нет ни точной сборки, ни `universal`
     */
    public fun assetFor(supportedAbis: List<String>): UpdateAsset? {
        for (abi in supportedAbis) {
            assets.firstOrNull { it.abi.equals(abi, ignoreCase = true) }?.let { return it }
        }
        return assets.firstOrNull { it.abi.equals(UNIVERSAL_ABI, ignoreCase = true) }
    }

    public companion object {

        /** ABI сборки, которая ставится на любое устройство (ТЗ §15.4). */
        public const val UNIVERSAL_ABI: String = "universal"

        /**
         * @throws MalformedManifestException если JSON битый, обрезан или в нём нет
         *   обязательных полей. Обязательные — те, без которых обновление нельзя проверить:
         *   версия, `build`, отпечаток сертификата и хотя бы один файл с суммой.
         */
        public fun parse(text: String): UpdateManifest = try {
            val root = MiniJson.parse(text).asJsonObject("latest.json")
            val assets = root[ASSETS].asJsonArray("поле \"$ASSETS\"").mapIndexed { index, raw ->
                val asset = raw.asJsonObject("элемент $index в \"$ASSETS\"")
                UpdateAsset(
                    abi = asset.jsonString("abi"),
                    url = asset.jsonString("url"),
                    // size может отсутствовать: проверка суммы всё равно обязательна, а размер
                    // нужен лишь для индикатора прогресса и раннего отказа. 0 — «неизвестен».
                    size = if (asset.containsKey("size")) asset.jsonLong("size") else 0L,
                    sha256 = asset.jsonString("sha256"),
                )
            }
            if (assets.isEmpty()) throw JsonFormatException("в манифесте нет ни одного файла релиза")

            UpdateManifest(
                version = root.jsonString("version"),
                build = root.jsonLong("build"),
                prerelease = root.jsonBoolean("prerelease", default = false),
                minAndroidSdk = root.jsonInt("min_android_sdk"),
                notesUrl = root.jsonStringOrNull("notes_url"),
                signingCertSha256 = root.jsonString("signing_cert_sha256"),
                assets = assets,
            )
        } catch (e: JsonFormatException) {
            // Внутренний тип наружу не выпускается: наружу у модуля один типизированный
            // ответ на «манифест не годится», иначе мост обрабатывал бы два разных исключения.
            throw MalformedManifestException(e.message ?: "манифест не разобран")
        }

        private const val ASSETS = "assets"
    }
}
