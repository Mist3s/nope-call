package com.mist3s.nopecall.updater

/**
 * Откуда берётся `latest.json` (ТЗ §15.5).
 *
 * Интерфейс нужен не ради «расширяемости», а ради тестов: подменив источник, можно проверить
 * весь путь принятия решения и скачивания без сети. Реализация — [GitHubUpdateSource].
 */
public interface UpdateSource {

    /**
     * @param allowPrerelease включена ли галочка «предварительные версии» (ТЗ §15.5).
     *   Влияет и на то, **где** искать манифест: ссылка `releases/latest` у GitHub предвыпуски
     *   пропускает по определению, поэтому с включённой галочкой идти нужно в API.
     * @throws HttpFailure сеть недоступна или GitHub ответил не 200
     * @throws MalformedManifestException манифест есть, но разобрать его нельзя
     */
    public fun fetchManifest(allowPrerelease: Boolean): UpdateManifest
}

/**
 * Один релиз в ответе GitHub Releases API — только те поля, что нужны для выбора.
 *
 * @property manifestUrl ссылка на приложенный `latest.json`; `null`, если его в релизе нет
 */
internal data class GitHubRelease(
    val tag: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val manifestUrl: String?,
)

/**
 * Разбор и выбор релиза из ответа GitHub Releases API (ТЗ §15.5).
 *
 * Вынесено отдельно от сети и покрыто тестами: «какой релиз считать последним» — это решение,
 * а не транспорт. Ошибиться здесь означает предложить пользователю черновик или предвыпуск,
 * которого он не просил.
 */
internal object GitHubReleases {

    /** Имя приложенного к релизу манифеста (ТЗ §15.4). */
    const val MANIFEST_ASSET = "latest.json"

    fun parse(json: String): List<GitHubRelease> {
        val releases = MiniJson.parse(json).asJsonArray("ответ GitHub Releases API")
        return releases.mapIndexed { index, raw ->
            val release = raw.asJsonObject("релиз $index")
            val assets = (release["assets"] ?: emptyList<Any?>()).asJsonArray("assets релиза $index")
            val manifestUrl = assets
                .map { it.asJsonObject("файл релиза $index") }
                .firstOrNull { MANIFEST_ASSET.equals(it.jsonStringOrNull("name"), ignoreCase = true) }
                ?.jsonStringOrNull("browser_download_url")
            GitHubRelease(
                tag = release.jsonStringOrNull("tag_name") ?: "",
                prerelease = release.jsonBoolean("prerelease", default = false),
                draft = release.jsonBoolean("draft", default = false),
                manifestUrl = manifestUrl,
            )
        }
    }

    /**
     * Последний подходящий релиз.
     *
     * Порядок API («сначала новые») не используется как истина: он про дату публикации, а
     * решение принимается по версии. Релиз, выпущенный позже с меньшей версией (исправление
     * старой ветки), не должен выглядеть как обновление.
     *
     * Черновики отбрасываются всегда: их файлы недоступны без токена, и предложить такой
     * релиз — значит гарантированно упасть на скачивании.
     */
    fun pick(releases: List<GitHubRelease>, allowPrerelease: Boolean): GitHubRelease? = releases
        .filter { !it.draft && it.manifestUrl != null }
        .filter { allowPrerelease || !it.prerelease }
        // nullsFirst: релиз с тегом не по semver считается самым младшим, а не «равным всем».
        // Иначе один тег вида `latest` мог бы победить нормальные версии.
        .maxWithOrNull(compareBy(nullsFirst<SemVer>()) { SemVer.parseOrNull(it.tag) })
}

/**
 * Манифест из релизов GitHub (ТЗ §15.5).
 *
 * Два пути, как требует ТЗ: сначала прямая ссылка на файл последнего релиза, при её
 * недоступности — Releases API. Прямая ссылка первой потому, что она отдаётся с CDN, не тратит
 * лимит запросов API (60 в час на IP для анонимных клиентов) и не раскрывает больше нужного.
 */
public class GitHubUpdateSource(
    private val transport: HttpTransport,
    private val repo: String = DEFAULT_REPO,
) : UpdateSource {

    override fun fetchManifest(allowPrerelease: Boolean): UpdateManifest {
        // С включёнными предвыпусками прямой путь бесполезен: `releases/latest` у GitHub
        // означает «последний НЕ предвыпуск», и по нему предвыпуск не найти никогда.
        var directFailure: Exception? = null
        if (!allowPrerelease) {
            try {
                return UpdateManifest.parse(transport.getText(directManifestUrl(), MANIFEST_MAX_BYTES))
            } catch (e: HttpFailure) {
                directFailure = e
            } catch (e: MalformedManifestException) {
                // Битый манифест в последнем релизе — не повод искать в API «релиз получше»:
                // это дефект релиза, и о нём нужно сказать, а не обойти его молча.
                throw e
            }
        }

        val listJson = try {
            transport.getText(releasesApiUrl(), RELEASES_MAX_BYTES)
        } catch (e: HttpFailure) {
            // Наружу отдаётся ошибка первого пути: она ближе к причине и понятнее человеку,
            // чем «api.github.com тоже не ответил».
            throw directFailure ?: e
        }

        val release = GitHubReleases.pick(GitHubReleases.parse(listJson), allowPrerelease)
            ?: throw HttpFailure(
                if (allowPrerelease) "в релизах GitHub нет ${GitHubReleases.MANIFEST_ASSET}"
                else "в релизах GitHub нет ${GitHubReleases.MANIFEST_ASSET} без пометки «предвыпуск»",
            )
        return UpdateManifest.parse(transport.getText(release.manifestUrl!!, MANIFEST_MAX_BYTES))
    }

    private fun directManifestUrl(): String =
        "https://github.com/$repo/releases/latest/download/${GitHubReleases.MANIFEST_ASSET}"

    /**
     * Список релизов. `per_page` ограничен сознательно: искать обновление среди сотни старых
     * релизов бессмысленно, а ответ на 100 релизов — это уже сотни килобайт JSON на телефоне.
     */
    private fun releasesApiUrl(): String = "https://api.github.com/repos/$repo/releases?per_page=$PER_PAGE"

    public companion object {
        /** Репозиторий проекта (ТЗ §15.5). */
        public const val DEFAULT_REPO: String = "Mist3s/nope-call"

        /** Манифест — единицы килобайт. Предел на порядки больше и всё равно защищает память. */
        internal const val MANIFEST_MAX_BYTES: Long = 256L * 1024

        internal const val RELEASES_MAX_BYTES: Long = 2L * 1024 * 1024
        private const val PER_PAGE = 10
    }
}
