package com.mist3s.nopecall.core

import com.mist3s.nopecall.core.contacts.ContactIndex
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Индекс контактов (ТЗ §6.4, архитектура §5.3).
 *
 * Главное, что проверяется: **отсутствие индекса даёт `null`, а не `false`**. Разница
 * существенная. `false` означает «точно не в контактах» и позволяет широкому правилу сработать;
 * `null` означает «не знаю» и помечает решение флагом. Молчаливое `false` выглядело бы
 * как достоверное знание и могло бы заблокировать звонок от контакта.
 */
class ContactIndexTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val normalizer = RuFastPathNormalizer()

    private fun index(name: String) = ContactIndex(temp.newFolder(name), normalizer)

    @Test
    fun `без индекса ответ неизвестен, а не отрицателен`() {
        val idx = index("empty")
        assertNull(idx.contains("+79991234567"))
        assertFalse(idx.isAvailable())
    }

    @Test
    fun `пустой номер не спрашивается`() {
        val idx = index("null-key")
        assertNull(idx.contains(null))
        assertNull(idx.contains(""))
    }

    @Test
    fun `повреждённый файл индекса даёт неизвестно`() {
        val dir = temp.newFolder("broken")
        // Файл не кратен 8 байтам — значит это не массив хешей.
        File(dir, ContactIndex.FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))
        val idx = ContactIndex(dir, normalizer)
        assertNull(idx.contains("+79991234567"))
        assertFalse(idx.isAvailable())
    }

    @Test
    fun `записанный индекс читается и отвечает по существу`() {
        // Пишем файл в том же формате, что и rebuild: отсортированный массив 8-байтных хешей.
        val dir = temp.newFolder("filled")
        val known = "+79991234567"
        val hash = truncatedSha256(known)
        File(dir, ContactIndex.FILE_NAME).writeBytes(longToBytes(hash))

        val idx = ContactIndex(dir, normalizer)
        assertTrue(idx.isAvailable())
        assertEquals(1, idx.size())
        assertEquals(true, idx.contains(known))
        assertEquals(false, idx.contains("+79990000000"))
    }

    @Test
    fun `сброс кэша заставляет перечитать файл`() {
        val dir = temp.newFolder("reload")
        val idx = ContactIndex(dir, normalizer)
        assertNull(idx.contains("+79991234567"))

        File(dir, ContactIndex.FILE_NAME)
            .writeBytes(longToBytes(truncatedSha256("+79991234567")))
        // Пока кэш не сброшен, старый ответ сохраняется: файл в горячем пути не перечитывается.
        assertNull(idx.contains("+79991234567"))

        idx.invalidate()
        assertEquals(true, idx.contains("+79991234567"))
    }

    private fun truncatedSha256(key: String): Long {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (digest[i].toLong() and 0xFF)
        return v
    }

    private fun longToBytes(value: Long): ByteArray {
        val bytes = ByteArray(8)
        var v = value
        for (b in 7 downTo 0) {
            bytes[b] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return bytes
    }
}
