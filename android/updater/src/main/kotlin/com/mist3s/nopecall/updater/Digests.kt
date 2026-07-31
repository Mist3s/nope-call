package com.mist3s.nopecall.updater

import java.io.File
import java.security.MessageDigest

/**
 * Контрольные суммы и отпечатки (ТЗ §15.5).
 *
 * `MessageDigest` из JDK, а не из Android SDK: он настоящий и в unit-тестах на голой JVM,
 * поэтому проверка «не совпала сумма — файл удалён, установка отменена» тестируется без устройства.
 */
internal object Digests {

    /**
     * Sha256 файла потоково, порциями по 64 КиБ.
     *
     * Потоково, а не `readBytes()`: APK около 25 МБ, и загонять его целиком в память процесса,
     * который в этот момент держит ещё и Flutter Engine, незачем.
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Приведение отпечатка к сравнимому виду: без разделителей, в нижнем регистре.
     *
     * Нужно потому, что в `latest.json` отпечаток сертификата записан в виде `AB:CD:…`
     * (так его печатает `apksigner`, так он попадает в release notes), а `PackageManager`
     * даёт байты. Сравнение строк «как есть» не сработало бы никогда, и это была бы самая
     * неприятная разновидность дефекта: обновление молча не устанавливается ни разу.
     *
     * @return `null`, если это не 64 шестнадцатеричных знака. `null` означает отказ:
     *   отпечаток, который мы не поняли, не считается совпавшим.
     */
    fun normalizeFingerprint(value: String?): String? {
        if (value == null) return null
        val cleaned = buildString(value.length) {
            for (c in value) {
                when {
                    c == ':' || c == ' ' || c == '-' || c == '\n' || c == '\r' || c == '\t' -> Unit
                    else -> append(c.lowercaseChar())
                }
            }
        }
        if (cleaned.length != 64) return null
        if (cleaned.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return cleaned
    }

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val v = bytes[index].toInt() and 0xFF
            out[index * 2] = HEX[v shr 4]
            out[index * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
