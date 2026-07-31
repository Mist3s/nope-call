package com.mist3s.nopecall.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Решение «предлагать обновление или нет» (ТЗ §15.5, §15.2).
 *
 * Тесты фиксируют границы, на которых легко ошибиться: равные версии, откат, требование
 * более нового Android, предвыпуски и смещение `versionCode` по ABI.
 */
class UpdatePolicyTest {

    private fun decide(
        manifest: UpdateManifest = UpdateManifest.parse(Fixtures.manifestJson()),
        installed: InstalledApp = Fixtures.installed(),
        allowPrerelease: Boolean = false,
    ): UpdateCheckResult = UpdatePolicy.decide(manifest, installed, allowPrerelease)

    @Test
    fun `новая версия предлагается с файлом под ABI устройства`() {
        val result = decide()
        val available = assertIs<UpdateCheckResult.Available>(result, "1.2.3 новее 1.2.2")
        assertEquals("1.2.3", available.manifest.version, "предложена версия из манифеста")
        assertEquals("arm64-v8a", available.asset.abi, "выбран файл под ABI устройства")
    }

    @Test
    fun `та же версия не предлагается`() {
        // Ловит сравнение «не равно» вместо «строго новее»: одна и та же версия предлагалась бы
        // к установке бесконечно, а система отказала бы по одинаковому versionCode.
        assertEquals(
            UpdateCheckResult.UpToDate,
            decide(installed = Fixtures.installed(versionName = "1.2.3", versionCode = 45)),
            "установлена та же версия",
        )
    }

    @Test
    fun `откат на предыдущую версию не предлагается`() {
        // Требование ТЗ §15.2: откат по versionCode невозможен в принципе, Android не даст
        // установить сборку с меньшим значением. Предложить такое — гарантированный отказ
        // системы и непонятная пользователю ошибка.
        assertEquals(
            UpdateCheckResult.UpToDate,
            decide(installed = Fixtures.installed(versionName = "1.3.0", versionCode = 50)),
            "установлена версия новее релиза",
        )
    }

    @Test
    fun `предвыпуск без галочки не предлагается`() {
        // Требование ТЗ §15.5. И это «актуальная версия», а не ошибка: пользователь, который
        // не просил предвыпуски, не должен видеть никаких сообщений.
        val manifest = UpdateManifest.parse(Fixtures.manifestJson(version = "1.3.0-rc1", build = 46))
        assertEquals(UpdateCheckResult.UpToDate, decide(manifest = manifest), "галочка выключена")
    }

    @Test
    fun `предвыпуск с галочкой предлагается`() {
        val manifest = UpdateManifest.parse(
            Fixtures.manifestJson(version = "1.3.0-rc1", build = 46, prerelease = true),
        )
        assertIs<UpdateCheckResult.Available>(
            decide(manifest = manifest, allowPrerelease = true),
            "галочка включена — предвыпуск виден",
        )
    }

    @Test
    fun `предвыпуск не предлагается поверх соответствующего релиза`() {
        // Ловит сравнение только по числовой части: 1.2.3-rc1 младше 1.2.3 (semver 11.3),
        // и предлагать его тому, у кого стоит 1.2.3, значит предлагать откат.
        val manifest = UpdateManifest.parse(
            Fixtures.manifestJson(version = "1.2.3-rc1", build = 46, prerelease = true),
        )
        assertEquals(
            UpdateCheckResult.UpToDate,
            decide(
                manifest = manifest,
                installed = Fixtures.installed(versionName = "1.2.3", versionCode = 45),
                allowPrerelease = true,
            ),
            "релиз новее своего предвыпуска",
        )
    }

    @Test
    fun `смещение versionCode по ABI не мешает обновлению`() {
        // Ключевой случай ТЗ §15.2: при --split-per-abi Flutter добавляет к versionCode
        // 1000 × индекс ABI, поэтому у установленной копии 2044 там, где в манифесте build 45.
        // Прямое сравнение чисел дало бы 45 <= 2044, то есть «обновлений нет» — навсегда.
        val installed = Fixtures.installed(versionName = "1.2.2", versionCode = 2044)
        assertEquals(44L, installed.baseVersionCode, "смещение по ABI снято")
        assertIs<UpdateCheckResult.Available>(decide(installed = installed), "обновление обязано найтись")
    }

    @Test
    fun `версия выше а номер сборки не выше — это дефект релиза`() {
        // Такую сборку система не установит (versionCode не возрастает), поэтому предлагать её
        // нельзя. И это отказ с текстом, а не «актуальная версия»: чинит его человек, выпуская
        // релиз заново, и он должен об этом узнать.
        val manifest = UpdateManifest.parse(Fixtures.manifestJson(version = "1.3.0", build = 44))
        val failure = assertIs<UpdateCheckResult.Failure>(
            decide(manifest = manifest, installed = Fixtures.installed(versionName = "1.2.2", versionCode = 44)),
            "номер сборки не возрос",
        )
        assertEquals(UpdateFailureKind.FORMAT, failure.kind, "род отказа")
        assertTrue(failure.reason.contains("44"), "в причине виден номер сборки: ${failure.reason}")
    }

    @Test
    fun `сборка для более нового Android не предлагается`() {
        // Требование задания: minAndroidSdk выше версии устройства — установки быть не должно.
        // Отказ с текстом, а не молчание: обновление существует, и пользователь должен понимать,
        // почему оно не приходит именно на этот телефон.
        val manifest = UpdateManifest.parse(Fixtures.manifestJson(version = "2.0.0", build = 60, minAndroidSdk = 34))
        val failure = assertIs<UpdateCheckResult.Failure>(
            decide(manifest = manifest, installed = Fixtures.installed(sdkInt = 29)),
            "устройство на Android 10",
        )
        assertEquals(UpdateFailureKind.INCOMPATIBLE, failure.kind, "род отказа")
        assertTrue(failure.reason.contains("34"), "в причине виден требуемый SDK: ${failure.reason}")
        assertEquals(
            "https://github.com/Mist3s/nope-call/releases/tag/v2.0.0",
            failure.notesUrl,
            "ссылка на страницу релиза для установки вручную (ТЗ §15.5)",
        )
    }

    @Test
    fun `подходящий minAndroidSdk равный версии устройства проходит`() {
        // Граница: 29 на устройстве с SDK 29 — это «подходит», а не «выше».
        val manifest = UpdateManifest.parse(Fixtures.manifestJson(minAndroidSdk = 29))
        assertIs<UpdateCheckResult.Available>(
            decide(manifest = manifest, installed = Fixtures.installed(sdkInt = 29)),
            "равный SDK подходит",
        )
    }

    @Test
    fun `нет файла под ABI устройства — отказ с причиной`() {
        val manifest = UpdateManifest.parse(
            Fixtures.manifestJson(
                assets = """{ "abi": "x86_64", "url": "https://github.com/a.apk", "size": 1, "sha256": "s" }""",
            ),
        )
        val failure = assertIs<UpdateCheckResult.Failure>(
            decide(manifest = manifest),
            "в релизе только x86_64",
        )
        assertEquals(UpdateFailureKind.INCOMPATIBLE, failure.kind, "род отказа")
    }

    @Test
    fun `нечитаемая версия установленной копии не даёт предложить обновление`() {
        // Пустой versionName встречается, когда getPackageInfo провалился. Считать в этом случае
        // «версия 0, обновляемся» нельзя: неизвестно, что установлено, значит уверенности нет.
        val failure = assertIs<UpdateCheckResult.Failure>(
            decide(installed = Fixtures.installed(versionName = "")),
            "версия установленной копии неизвестна",
        )
        assertEquals(UpdateFailureKind.FORMAT, failure.kind, "род отказа")
    }

    @Test
    fun `версия релиза не по semver — отказ, а не предложение`() {
        val manifest = UpdateManifest.parse(Fixtures.manifestJson(version = "latest", build = 99))
        val failure = assertIs<UpdateCheckResult.Failure>(decide(manifest = manifest), "тег не версия")
        assertEquals(UpdateFailureKind.FORMAT, failure.kind, "род отказа")
    }
}
