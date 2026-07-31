package com.mist3s.nopecall.updater

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Контрольные суммы и отпечатки (ТЗ §15.5).
 */
class DigestsTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `sha256 файла совпадает с посчитанной сторонним средством`() {
        // Ожидаемое значение посчитано `sha256sum`, а не этим же кодом: иначе тест утверждал бы
        // лишь «функция стабильна», и опечатка в шестнадцатеричном выводе осталась бы незамеченной.
        val file = File(temp.root, "apk").apply { writeText(Fixtures.APK_BYTES) }
        assertEquals(Fixtures.APK_SHA256, Digests.sha256(file), "sha256 файла")
    }

    @Test
    fun `sha256 считается порциями и не зависит от размера файла`() {
        // Файл больше буфера в 64 КиБ: ловит чтение только первой порции — самый неприятный
        // вариант дефекта, потому что на маленьких файлах в тестах всё сходится.
        val big = File(temp.root, "big.apk")
        val chunk = "n".repeat(1024)
        big.outputStream().use { out -> repeat(200) { out.write(chunk.toByteArray()) } }

        val expected = Digests.sha256Hex("n".repeat(200 * 1024).toByteArray())
        assertEquals(expected, Digests.sha256(big), "sha256 файла на 200 КиБ")
    }

    @Test
    fun `отпечаток из latest_json приводится к виду из PackageManager`() {
        // Ключевая мелочь: в манифесте отпечаток записан как AB:CD:… (так его печатает apksigner
        // и так он попадает в release notes), а система отдаёт байты. Без приведения сравнение
        // не совпало бы ни разу, и обновление молча никогда бы не устанавливалось.
        assertEquals(
            Fixtures.CERT_INSTALLED,
            Digests.normalizeFingerprint(Fixtures.CERT_PRETTY),
            "двоеточия убраны, регистр нижний",
        )
        assertEquals(
            Fixtures.CERT_INSTALLED,
            Digests.normalizeFingerprint(" ${Fixtures.CERT_INSTALLED.uppercase()}\n"),
            "пробелы и перевод строки не мешают",
        )
    }

    @Test
    fun `непонятный отпечаток не считается совпавшим`() {
        // null означает отказ: отпечаток, который мы не поняли, нельзя приравнять ни к чему.
        assertNull(Digests.normalizeFingerprint(null), "нет значения")
        assertNull(Digests.normalizeFingerprint(""), "пустая строка")
        assertNull(Digests.normalizeFingerprint("AB:CD"), "слишком короткий")
        assertNull(Digests.normalizeFingerprint("z".repeat(64)), "не шестнадцатеричный")
        assertNull(Digests.normalizeFingerprint("0".repeat(65)), "слишком длинный")
    }
}
