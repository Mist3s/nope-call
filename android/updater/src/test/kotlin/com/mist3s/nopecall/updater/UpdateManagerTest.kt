package com.mist3s.nopecall.updater

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Скачивание, проверки и передача установщику (ТЗ §15.5).
 *
 * Главное, что здесь утверждается: при несовпадении контрольной суммы или отпечатка сертификата
 * файл удаляется, установщик **не зовётся** и причина возвращается текстом. Ошибки не показываются
 * никакими всплывающими окнами — модуль их только возвращает.
 *
 * JUnit4 нужен ради `TemporaryFolder`; проверки — `kotlin.test`, где сообщение идёт последним
 * аргументом (см. грабли в CLAUDE.md §5).
 */
class UpdateManagerTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val manifest = UpdateManifest.parse(Fixtures.manifestJson())
    private val asset = manifest.assets.first()

    private val transport = FakeTransport(
        files = linkedMapOf(asset.url to Fixtures.APK_BYTES),
    )
    private val source = FakeSource(manifest)
    private val installer = FakeInstaller()

    private fun manager(
        installed: InstalledApp = Fixtures.installed(),
        dir: File = File(temp.root, "updates"),
    ) = UpdateManager(
        source = source,
        transport = transport,
        installedApp = { installed },
        downloadDir = dir,
        installer = installer,
    )

    private fun apkFiles(dir: File = File(temp.root, "updates")): List<File> =
        dir.listFiles()?.toList() ?: emptyList()

    // --- проверка обновления -------------------------------------------------------------------

    @Test
    fun `проверка отдаёт обновление, настройку предвыпусков и передаёт её источнику`() {
        // Настройка обязана доходить до источника: ссылка releases/latest у GitHub предвыпуски
        // пропускает, и без передачи флага галочка «предварительные версии» ничего не делала бы.
        val result = manager().check(allowPrerelease = true)
        assertIs<UpdateCheckResult.Available>(result, "1.2.3 новее установленной 1.2.2")
        assertEquals(true, source.lastAllowPrerelease, "флаг предвыпусков дошёл до источника")
    }

    @Test
    fun `нет сети — отказ рода NETWORK и ни одного исключения наружу`() {
        // Тихая автопроверка при запуске (ТЗ §15.5) обязана возвращать значение, а не бросать:
        // исключение из фоновой проверки в лучшем случае попало бы в лог, в худшем — уронило поток.
        source.failure = HttpFailure("сеть недоступна")
        val failure = assertIs<UpdateCheckResult.Failure>(manager().check(false), "сети нет")
        assertEquals(UpdateFailureKind.NETWORK, failure.kind, "род отказа")
        assertTrue(failure.reason.isNotBlank(), "причина текстом")
    }

    @Test
    fun `битый манифест — отказ рода FORMAT`() {
        // Разные рода нужны интерфейсу: «нет сети» в тихой проверке не показывается вовсе,
        // а «битый манифест» — это дефект релиза, о котором надо сказать.
        source.failure = MalformedManifestException("после JSON остались лишние данные")
        val failure = assertIs<UpdateCheckResult.Failure>(manager().check(false), "манифест битый")
        assertEquals(UpdateFailureKind.FORMAT, failure.kind, "род отказа")
    }

    // --- скачивание и проверки ------------------------------------------------------------------

    @Test
    fun `совпавшая сумма и отпечаток — файл готов и передан установщику`() {
        val ready = assertIs<DownloadResult.Ready>(manager().download(manifest, asset), "проверки пройдены")
        assertTrue(ready.apk.isFile, "файл на месте")
        assertEquals(Fixtures.APK_BYTES, ready.apk.readText(), "содержимое файла")

        assertEquals(InstallResult.Started, manager().install(ready), "установка начата")
        assertEquals(listOf(ready.apk), installer.installed, "установщик получил именно этот файл")
    }

    @Test
    fun `не совпала sha256 — файл удалён, установка не начата`() {
        // Требование ТЗ §15.5. Ловит порядок «сначала установить, потом проверить» и оставленный
        // на диске непроверенный APK: через день по такому файлу нельзя сказать, проверяли его.
        transport.files[asset.url] = Fixtures.APK_BYTES_TAMPERED

        val failure = assertIs<DownloadResult.Failure>(manager().download(manifest, asset), "сумма не та")
        assertEquals(UpdateFailureKind.VERIFICATION, failure.kind, "род отказа")
        assertTrue(failure.reason.contains(Fixtures.APK_SHA256), "в причине видна ожидаемая сумма: ${failure.reason}")
        assertEquals(emptyList(), apkFiles(), "файл удалён")
        assertEquals(emptyList(), installer.installed, "установщик не вызван")
    }

    @Test
    fun `не совпал отпечаток сертификата — установки нет и скачивания тоже`() {
        // Требование ТЗ §15.5. Отпечаток сравнивается до скачивания: результат тот же, но
        // на несовпадении не тратятся десятки мегабайт трафика.
        val alien = Fixtures.installed(certs = listOf("00".repeat(32)))

        val failure = assertIs<DownloadResult.Failure>(
            manager(installed = alien).download(manifest, asset),
            "релиз подписан другим ключом",
        )
        assertEquals(UpdateFailureKind.VERIFICATION, failure.kind, "род отказа")
        assertEquals(emptyList(), transport.downloads, "скачивание не начиналось")
        assertEquals(emptyList(), installer.installed, "установщик не вызван")
        assertEquals(manifest.notesUrl, failure.notesUrl, "дана ссылка на страницу релиза")
    }

    @Test
    fun `отпечаток установленной копии не прочитан — установки нет`() {
        // Пустой список отпечатков означает, что getPackageInfo провалился. Считать «проверять
        // нечем, значит всё в порядке» нельзя: это ровно та неуверенность, при которой действие
        // не выполняется (ТЗ §1.1).
        val unknown = Fixtures.installed(certs = emptyList())
        val failure = assertIs<DownloadResult.Failure>(
            manager(installed = unknown).download(manifest, asset),
            "подпись установленной копии неизвестна",
        )
        assertEquals(UpdateFailureKind.VERIFICATION, failure.kind, "род отказа")
        assertEquals(emptyList(), transport.downloads, "скачивание не начиналось")
    }

    @Test
    fun `файл релиза не на GitHub — не скачивается вовсе`() {
        // `latest.json` — обычный текстовый файл: подменённый или собранный с опечаткой манифест
        // может указать на чужой сервер. Тогда проверки суммы и отпечатка не спасают: сумма
        // взята из того же подменённого манифеста.
        val alienManifest = UpdateManifest.parse(
            Fixtures.manifestJson(
                assets = """{ "abi": "arm64-v8a", "url": "https://evil.example.com/a.apk", "size": 19, "sha256": "${Fixtures.APK_SHA256}" }""",
            ),
        )
        val failure = assertIs<DownloadResult.Failure>(
            manager().download(alienManifest, alienManifest.assets.first()),
            "адрес не на GitHub",
        )
        assertEquals(UpdateFailureKind.VERIFICATION, failure.kind, "род отказа")
        assertEquals(emptyList(), transport.downloads, "скачивание не начиналось")
    }

    @Test
    fun `размер не совпал — отказ с понятной причиной, файл удалён`() {
        // Отдельная проверка размера нужна ради текста: «файл скачан не полностью» и
        // «контрольная сумма не совпала» чинятся по-разному, а несовпадение суммы при обрыве
        // выглядело бы как подмена файла.
        transport.files[asset.url] = Fixtures.APK_BYTES + "хвост"
        val failure = assertIs<DownloadResult.Failure>(manager().download(manifest, asset), "размер не тот")
        assertEquals(UpdateFailureKind.VERIFICATION, failure.kind, "род отказа")
        assertTrue(failure.reason.contains("не полностью"), "причина про неполный файл: ${failure.reason}")
        assertEquals(emptyList(), apkFiles(), "файл удалён")
    }

    @Test
    fun `обрыв скачивания — отказ рода NETWORK и недокачанный файл удалён`() {
        // Ловит оставленный на диске огрызок APK: он занимает место, а при повторной попытке
        // мог бы быть принят за готовый файл.
        transport.breakAfterBytes = 5
        val failure = assertIs<DownloadResult.Failure>(manager().download(manifest, asset), "обрыв связи")
        assertEquals(UpdateFailureKind.NETWORK, failure.kind, "род отказа")
        assertEquals(emptyList(), apkFiles(), "недокачанный файл удалён")
        assertEquals(emptyList(), installer.installed, "установщик не вызван")
    }

    @Test
    fun `каталог загрузки очищается перед новым скачиванием`() {
        // APK около 25 МБ. Без очистки в приватном хранилище копятся файлы всех версий, а
        // очистить его пользователь может только целиком — вместе с правилами и журналом.
        val dir = File(temp.root, "updates")
        dir.mkdirs()
        val stale = File(dir, "nope-call-1.0.0-arm64-v8a.apk").apply { writeText("старая сборка") }

        assertIs<DownloadResult.Ready>(manager(dir = dir).download(manifest, asset), "проверки пройдены")
        assertFalse(stale.exists(), "старый файл удалён")
        assertEquals(1, apkFiles(dir).size, "в каталоге ровно один файл")
    }

    @Test
    fun `установщик отказал — файл удалён и дана ссылка на страницу релиза`() {
        // ТЗ §15.5: при отказе установки нужно объяснение и путь установки вручную. Файл
        // удаляется, чтобы повторная попытка началась со скачивания и всех проверок заново.
        installer.result = InstallResult.Failure(UpdateFailureKind.INSTALL, "нет разрешения на установку")
        val ready = assertIs<DownloadResult.Ready>(manager().download(manifest, asset), "проверки пройдены")

        val failure = assertIs<InstallResult.Failure>(manager().install(ready), "установщик отказал")
        assertEquals(UpdateFailureKind.INSTALL, failure.kind, "род отказа")
        assertEquals(manifest.notesUrl, failure.notesUrl, "ссылка на страницу релиза подставлена")
        assertEquals(emptyList(), apkFiles(), "файл удалён")
    }

    @Test
    fun `кнопка «Обновить» одним вызовом скачивает, проверяет и устанавливает`() {
        val available = assertIs<UpdateCheckResult.Available>(manager().check(false), "обновление есть")
        assertEquals(InstallResult.Started, manager().downloadAndInstall(available), "установка начата")
        assertEquals(1, installer.installed.size, "установщик вызван один раз")
    }

    @Test
    fun `при неудачных проверках кнопка «Обновить» до установщика не доходит`() {
        transport.files[asset.url] = Fixtures.APK_BYTES_TAMPERED
        val available = assertIs<UpdateCheckResult.Available>(manager().check(false), "обновление есть")

        val failure = assertIs<InstallResult.Failure>(
            manager().downloadAndInstall(available),
            "сумма не совпала",
        )
        assertEquals(UpdateFailureKind.VERIFICATION, failure.kind, "род отказа сохраняется")
        assertEquals(emptyList(), installer.installed, "установщик не вызван")
    }
}
