package com.mist3s.nopecall.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Откуда берётся `latest.json` (ТЗ §15.5: «при недоступности — GitHub Releases API»).
 *
 * Проверяется не транспорт, а решения: какой релиз считать последним и когда идти в API.
 * Ошибка здесь означает предложенный пользователю черновик или предвыпуск, которого он не просил.
 */
class GitHubUpdateSourceTest {

    private val repo = GitHubUpdateSource.DEFAULT_REPO
    private val directUrl = "https://github.com/$repo/releases/latest/download/latest.json"
    private val apiUrl = "https://api.github.com/repos/$repo/releases?per_page=10"

    private fun releasesJson(vararg releases: String): String = "[${releases.joinToString(",")}]"

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        withManifest: Boolean = true,
    ): String {
        val assets = if (withManifest) {
            """[{"name":"nope-call-$tag-universal.apk","browser_download_url":"https://github.com/$repo/releases/download/$tag/apk"},
                {"name":"latest.json","browser_download_url":"https://github.com/$repo/releases/download/$tag/latest.json"}]"""
        } else {
            """[{"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/$repo/releases/download/$tag/sums"}]"""
        }
        return """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,"assets":$assets}"""
    }

    @Test
    fun `прямая ссылка используется первой и в API не ходим`() {
        // Прямая ссылка отдаётся с CDN и не тратит лимит API (60 запросов в час на IP).
        // Тест ловит обратный порядок: он работал бы, но у пользователей с автопроверкой
        // лимит выбирался бы на ровном месте.
        val transport = FakeTransport(texts = linkedMapOf(directUrl to Fixtures.manifestJson()))
        val manifest = GitHubUpdateSource(transport).fetchManifest(allowPrerelease = false)

        assertEquals("1.2.3", manifest.version, "манифест прочитан")
        assertEquals(listOf(directUrl), transport.textRequests, "запрошена только прямая ссылка")
    }

    @Test
    fun `при недоступности прямой ссылки идём в Releases API`() {
        // Требование ТЗ §15.5. Прямая ссылка отдаёт 404, пока в релизе нет latest.json —
        // например, у релизов, выпущенных до появления апдейтера.
        val transport = FakeTransport(
            texts = linkedMapOf(
                apiUrl to releasesJson(release("v1.2.3")),
                "https://github.com/$repo/releases/download/v1.2.3/latest.json" to Fixtures.manifestJson(),
            ),
        )
        val manifest = GitHubUpdateSource(transport).fetchManifest(allowPrerelease = false)

        assertEquals("1.2.3", manifest.version, "манифест взят из API")
        assertEquals(directUrl, transport.textRequests.first(), "сначала всё равно прямая ссылка")
        assertTrue(transport.textRequests.contains(apiUrl), "затем API: ${transport.textRequests}")
    }

    @Test
    fun `с включёнными предвыпусками сразу идём в API`() {
        // Ссылка releases/latest у GitHub означает «последний НЕ предвыпуск» — по ней предвыпуск
        // не найти никогда, сколько бы раз её ни запрашивали.
        val transport = FakeTransport(
            texts = linkedMapOf(
                apiUrl to releasesJson(release("v1.3.0-rc1", prerelease = true), release("v1.2.3")),
                "https://github.com/$repo/releases/download/v1.3.0-rc1/latest.json" to
                    Fixtures.manifestJson(version = "1.3.0-rc1", build = 46, prerelease = true),
            ),
        )
        val manifest = GitHubUpdateSource(transport).fetchManifest(allowPrerelease = true)

        assertEquals("1.3.0-rc1", manifest.version, "предвыпуск найден")
        assertEquals(listOf(apiUrl, "https://github.com/$repo/releases/download/v1.3.0-rc1/latest.json"),
            transport.textRequests, "прямая ссылка не запрашивалась")
    }

    @Test
    fun `битый манифест в последнем релизе не обходится через API`() {
        // Обход означал бы, что дефектный релиз предлагается пользователям как предыдущий,
        // и о дефекте никто не узнает. Ошибка формата обязана быть видна.
        val transport = FakeTransport(
            texts = linkedMapOf(
                directUrl to "это не json",
                apiUrl to releasesJson(release("v1.2.3")),
            ),
        )
        assertFailsWith<MalformedManifestException>("битый манифест обязан всплыть наружу") {
            GitHubUpdateSource(transport).fetchManifest(allowPrerelease = false)
        }
        assertEquals(listOf(directUrl), transport.textRequests, "в API не ходили")
    }

    @Test
    fun `когда недоступны оба пути, наружу идёт ошибка первого`() {
        // Причина ближе к делу: «GitHub ответил HTTP 404» по релизу понятнее, чем
        // «api.github.com тоже недоступен».
        val transport = FakeTransport()
        val failure = assertFailsWith<HttpFailure>("сети нет") {
            GitHubUpdateSource(transport).fetchManifest(allowPrerelease = false)
        }
        assertTrue(failure.message!!.contains(directUrl), "в причине первый адрес: ${failure.message}")
    }

    // --- выбор релиза из ответа API -------------------------------------------------------------

    @Test
    fun `выбирается релиз с наибольшей версией, а не первый в ответе`() {
        // Порядок в ответе API — по дате публикации. Исправление старой ветки, выпущенное позже,
        // не должно выглядеть как обновление: 1.1.5 не новее 1.2.3.
        val releases = GitHubReleases.parse(releasesJson(release("v1.1.5"), release("v1.2.3")))
        assertEquals("v1.2.3", GitHubReleases.pick(releases, allowPrerelease = false)?.tag, "выбрана 1.2.3")
    }

    @Test
    fun `предвыпуск не выбирается без галочки`() {
        val releases = GitHubReleases.parse(
            releasesJson(release("v1.3.0-rc1", prerelease = true), release("v1.2.3")),
        )
        assertEquals(
            "v1.2.3",
            GitHubReleases.pick(releases, allowPrerelease = false)?.tag,
            "без галочки берётся обычный релиз",
        )
        assertEquals(
            "v1.3.0-rc1",
            GitHubReleases.pick(releases, allowPrerelease = true)?.tag,
            "с галочкой берётся предвыпуск",
        )
    }

    @Test
    fun `черновик и релиз без latest_json пропускаются`() {
        // Файлы черновика недоступны без токена: предложить такой релиз — гарантированный
        // отказ на скачивании. Релиз без манифеста просто нечем проверить.
        val releases = GitHubReleases.parse(
            releasesJson(
                release("v1.4.0", draft = true),
                release("v1.3.0", withManifest = false),
                release("v1.2.3"),
            ),
        )
        assertEquals("v1.2.3", GitHubReleases.pick(releases, allowPrerelease = false)?.tag, "выбран пригодный релиз")
    }

    @Test
    fun `тег не по semver не побеждает нормальные версии`() {
        // nullsFirst в сравнении: иначе один тег вида `nightly` мог бы стать «последним релизом».
        val releases = GitHubReleases.parse(releasesJson(release("nightly"), release("v1.2.3")))
        assertEquals("v1.2.3", GitHubReleases.pick(releases, allowPrerelease = false)?.tag, "выбрана версия")
    }

    @Test
    fun `пустой список релизов не даёт выбора`() {
        assertNull(GitHubReleases.pick(GitHubReleases.parse("[]"), allowPrerelease = false), "релизов нет")
    }
}
