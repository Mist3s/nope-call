package com.mist3s.nopecall.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Разбор `latest.json` и выбор файла по ABI (ТЗ §15.5).
 *
 * Разбор проверяется в том числе на битом и обрезанном вводе: манифест приходит из сети, и
 * молчаливый разбор «как получилось» означал бы установку файла с непроверенной суммой.
 */
class UpdateManifestTest {

    @Test
    fun `манифест из ТЗ разбирается целиком`() {
        // Ловит расхождение имён полей с документом: snake_case в файле против camelCase в коде.
        // Такое расхождение не видно ни анализатором, ни сборкой — только разбором настоящего файла.
        val manifest = UpdateManifest.parse(Fixtures.manifestJson())

        assertEquals("1.2.3", manifest.version, "версия")
        assertEquals(45L, manifest.build, "номер сборки")
        assertFalse(manifest.prerelease, "признак предвыпуска")
        assertEquals(29, manifest.minAndroidSdk, "минимальный SDK")
        assertEquals(
            "https://github.com/Mist3s/nope-call/releases/tag/v1.2.3",
            manifest.notesUrl,
            "страница релиза",
        )
        assertEquals(Fixtures.CERT_PRETTY, manifest.signingCertSha256, "отпечаток как в манифесте")
        assertEquals(3, manifest.assets.size, "число файлов релиза")
        assertEquals("arm64-v8a", manifest.assets.first().abi, "порядок файлов сохраняется")
        assertEquals(19L, manifest.assets.first().size, "размер файла")
    }

    @Test
    fun `большой build не теряет точность`() {
        // Ловит разбор чисел через Double: 9007199254740993 (2^53+1) в Double не представимо,
        // и build молча сравнивался бы с округлённым значением.
        val manifest = UpdateManifest.parse(Fixtures.manifestJson(build = 9007199254740993L))
        assertEquals(9007199254740993L, manifest.build, "целое читается как Long")
    }

    @Test
    fun `битый JSON отвергается с причиной`() {
        // Ловит «мягкий» разбор, который на мусоре отдаёт объект с null-полями. Такой разбор
        // превратил бы подменённый ответ в «обновление без проверок».
        val broken = assertFailsWith<MalformedManifestException>("мусор не манифест") {
            UpdateManifest.parse("это не json")
        }
        assertTrue(broken.message!!.isNotBlank(), "причина обязана быть текстом: ${broken.message}")

        assertFailsWith<MalformedManifestException>("HTML страницы ошибки GitHub — не манифест") {
            UpdateManifest.parse("<html><body>404 not found</body></html>")
        }
        assertFailsWith<MalformedManifestException>("пустой ответ — не манифест") {
            UpdateManifest.parse("")
        }
        assertFailsWith<MalformedManifestException>("массив вместо объекта") {
            UpdateManifest.parse("[]")
        }
    }

    @Test
    fun `обрезанный JSON отвергается`() {
        // Ровно то, что приходит при обрыве соединения на середине ответа. Разбор обязан
        // отказать, а не вернуть манифест с частью полей: у него уже была бы «версия»,
        // но не было бы контрольной суммы.
        val full = Fixtures.manifestJson()
        assertFailsWith<MalformedManifestException>("обрезано по середине") {
            UpdateManifest.parse(full.substring(0, full.length / 2))
        }
        assertFailsWith<MalformedManifestException>("обрезано перед закрывающей скобкой") {
            UpdateManifest.parse(full.dropLast(1))
        }
        assertFailsWith<MalformedManifestException>("обрезано внутри строки") {
            UpdateManifest.parse("""{"version": "1.2.3""")
        }
    }

    @Test
    fun `манифест без обязательных полей отвергается`() {
        // Каждое из этих полей нужно для проверки перед установкой. Отсутствие любого
        // означает, что установку нельзя проверить, а значит нельзя и начинать (ТЗ §1.1).
        assertFailsWith<MalformedManifestException>("нет version") {
            UpdateManifest.parse("""{"build": 45, "min_android_sdk": 29, "signing_cert_sha256": "x", "assets": [{"abi":"universal","url":"u","sha256":"s"}]}""")
        }
        assertFailsWith<MalformedManifestException>("нет build") {
            UpdateManifest.parse("""{"version": "1.2.3", "min_android_sdk": 29, "signing_cert_sha256": "x", "assets": [{"abi":"universal","url":"u","sha256":"s"}]}""")
        }
        assertFailsWith<MalformedManifestException>("нет signing_cert_sha256") {
            UpdateManifest.parse("""{"version": "1.2.3", "build": 45, "min_android_sdk": 29, "assets": [{"abi":"universal","url":"u","sha256":"s"}]}""")
        }
        assertFailsWith<MalformedManifestException>("нет sha256 у файла") {
            UpdateManifest.parse("""{"version": "1.2.3", "build": 45, "min_android_sdk": 29, "signing_cert_sha256": "x", "assets": [{"abi":"universal","url":"u"}]}""")
        }
        assertFailsWith<MalformedManifestException>("пустой список файлов") {
            UpdateManifest.parse(Fixtures.manifestJson(assets = ""))
        }
    }

    @Test
    fun `дубликат ключа отвергается`() {
        // Дубликат — типичный приём подмены: человек в diff видит первую сумму, разбор берёт
        // последнюю. Отказ здесь дешевле, чем разбирательство «почему сумма другая».
        assertFailsWith<MalformedManifestException>("две суммы в одном файле") {
            UpdateManifest.parse(
                """{"version":"1.2.3","build":45,"min_android_sdk":29,"signing_cert_sha256":"x",
                   "assets":[{"abi":"universal","url":"u","sha256":"a","sha256":"b"}]}""",
            )
        }
    }

    @Test
    fun `неизвестные поля не мешают`() {
        // Обратная совместимость: манифест новой версии может нести поля, которых эта сборка
        // не знает. Отказ в этом случае означал бы, что старые копии перестают обновляться
        // ровно тогда, когда в манифест добавили что-то полезное.
        val manifest = UpdateManifest.parse(
            Fixtures.manifestJson().replace("\"version\":", "\"changelog_html\": \"…\", \"version\":"),
        )
        assertEquals("1.2.3", manifest.version, "версия прочитана несмотря на лишнее поле")
    }

    @Test
    fun `файл выбирается по первому ABI устройства`() {
        // Ловит выбор по порядку в манифесте: на arm64-устройстве, у которого в SUPPORTED_ABIS
        // есть и armeabi-v7a, установилась бы 32-битная сборка — работающая, но медленная,
        // и потом не обновляемая на 64-битную без переустановки.
        val manifest = UpdateManifest.parse(Fixtures.manifestJson())
        val chosen = manifest.assetFor(listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("arm64-v8a", chosen?.abi, "выбран ABI, предпочитаемый устройством")
    }

    @Test
    fun `при отсутствии точного ABI берётся universal`() {
        // Требование ТЗ §15.5. Ловит отказ на устройстве с x86_64 (эмулятор), для которого
        // в релизе может не быть отдельной сборки.
        val manifest = UpdateManifest.parse(Fixtures.manifestJson())
        assertEquals(
            UpdateManifest.UNIVERSAL_ABI,
            manifest.assetFor(listOf("x86_64"))?.abi,
            "падение на universal",
        )
    }

    @Test
    fun `без подходящего файла и без universal выбора нет`() {
        // null, а не «первый попавшийся»: установка сборки под чужой ABI закончится отказом
        // системы либо приложением, которое не запускается.
        val manifest = UpdateManifest.parse(
            Fixtures.manifestJson(
                assets = """{ "abi": "arm64-v8a", "url": "https://github.com/a.apk", "size": 1, "sha256": "s" }""",
            ),
        )
        assertNull(manifest.assetFor(listOf("x86_64")), "под x86_64 сборки нет")
    }

    @Test
    fun `ABI сравнивается без учёта регистра`() {
        // Регистр ABI в манифесте пишет человек, а Build.SUPPORTED_ABIS отдаёт нижний.
        val manifest = UpdateManifest.parse(
            Fixtures.manifestJson(
                assets = """{ "abi": "ARM64-V8A", "url": "https://github.com/a.apk", "size": 1, "sha256": "s" }""",
            ),
        )
        assertEquals("ARM64-V8A", manifest.assetFor(listOf("arm64-v8a"))?.abi, "регистр не важен")
    }
}
