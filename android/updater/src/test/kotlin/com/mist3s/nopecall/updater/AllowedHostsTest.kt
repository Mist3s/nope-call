package com.mist3s.nopecall.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Куда апдейтеру разрешено обращаться (ТЗ §15.6: «обращения только к GitHub»).
 *
 * Проверка нужна потому, что адреса файлов приходят из `latest.json`, то есть из сети.
 * Заявление «в сеть уходит только номер версии в GitHub» держится в том числе на этом списке.
 */
class AllowedHostsTest {

    @Test
    fun `адреса GitHub разрешены`() {
        assertTrue(
            AllowedHosts.isAllowed("https://github.com/Mist3s/nope-call/releases/latest/download/latest.json"),
            "прямая ссылка на файл релиза",
        )
        assertTrue(AllowedHosts.isAllowed("https://api.github.com/repos/Mist3s/nope-call/releases"), "Releases API")
        // Ссылка на файл релиза отдаёт 302 на objects.githubusercontent.com — без этого хоста
        // скачивание падало бы на каждом релизе.
        assertTrue(
            AllowedHosts.isAllowed("https://objects.githubusercontent.com/github-production-release-asset/x"),
            "хост выдачи файлов",
        )
    }

    @Test
    fun `чужой хост запрещён`() {
        // Подменённый или собранный с опечаткой манифест не должен уводить скачивание в сторону:
        // сумма в таком манифесте тоже подменена, и на неё полагаться нельзя.
        assertFalse(AllowedHosts.isAllowed("https://evil.example.com/nope-call.apk"), "чужой сервер")
        // Ловит проверку через `contains`: имя ниже содержит «github.com», но принадлежит другому.
        assertFalse(AllowedHosts.isAllowed("https://github.com.evil.example.com/a.apk"), "хост-обманка")
        assertFalse(AllowedHosts.isAllowed("https://notgithub.com/a.apk"), "похожее имя без точки")
    }

    @Test
    fun `http и обманный userinfo запрещены`() {
        // http запрещён и настройкой usesCleartextTraffic, но полагаться на манифест хоста
        // модуль не может: проверка обязана быть в самом апдейтере.
        assertFalse(AllowedHosts.isAllowed("http://github.com/a.apk"), "открытый http")
        // user@host: человек в списке ассетов видит github.com, обращение идёт к evil.example.com.
        assertFalse(AllowedHosts.isAllowed("https://github.com@evil.example.com/a.apk"), "userinfo в адресе")
        assertFalse(AllowedHosts.isAllowed("не адрес"), "мусор вместо адреса")
        assertFalse(AllowedHosts.isAllowed(""), "пустой адрес")
    }
}
