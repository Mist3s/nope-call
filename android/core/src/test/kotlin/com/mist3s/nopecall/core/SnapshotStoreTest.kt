package com.mist3s.nopecall.core

import com.mist3s.nopecall.core.snapshot.DirectorySync
import com.mist3s.nopecall.core.snapshot.SnapshotStore
import com.mist3s.nopecall.engine.CallAction
import com.mist3s.nopecall.engine.DecisionSettings
import com.mist3s.nopecall.engine.MatchType
import com.mist3s.nopecall.engine.Rule as BlockRule
import com.mist3s.nopecall.engine.RuFastPathNormalizer
import com.mist3s.nopecall.engine.RuleTarget
import com.mist3s.nopecall.engine.SnapshotBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import kotlin.test.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Хранилище снимка: атомарность записи, устойчивость чтения, кэш в памяти.
 *
 * Тест на голой JVM — поэтому `fsync` каталога подменяется через шов [DirectorySync]. Проверяется
 * при этом не то, что синхронизация «работает», а то, что она **вызывается после переименования**:
 * без неё `rename` не durable, и после потери питания снимок может отсутствовать (архитектура §5.2,
 * находка ревью Су12).
 */
class SnapshotStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val builder = SnapshotBuilder(RuFastPathNormalizer())
    private val settings = DecisionSettings(categoryDictionary = setOf("reklam"))

    // Псевдоним нужен из-за org.junit.Rule: имена совпадают, а аннотация нужна для TemporaryFolder.
    private fun rules() = listOf(
        BlockRule(1, "префикс", RuleTarget.NUMBER, MatchType.PREFIX, "8495", CallAction.REJECT, 600),
        BlockRule(2, "точный", RuleTarget.NUMBER, MatchType.EXACT, "+79991234567", CallAction.ALLOW, 100),
    )

    private fun store(sync: DirectorySync = DirectorySync.NONE) =
        SnapshotStore(temp.newFolder("snapshot-${counter++}"), sync)

    private var counter = 0

    @Test
    fun `записанный снимок читается и даёт те же решения`() {
        val s = store()
        val snapshot = builder.build(rules(), settings)
        s.write(snapshot)

        s.invalidate() // заставляем прочитать файл, а не отдать кэш
        val restored = s.current(verifyChecksums = true)
        assertNotNull(restored)
        assertEquals(snapshot.ruleCount, restored!!.ruleCount)
        assertEquals(1, s.diskReads)
    }

    @Test
    fun `тёплый путь файл не перечитывает`() {
        val s = store()
        s.write(builder.build(rules(), settings))
        // Запись сразу публикует разобранный снимок: перечитывать только что записанное незачем.
        assertEquals(0, s.diskReads)
        repeat(10) { assertNotNull(s.current()) }
        assertEquals(0, s.diskReads)

        s.invalidate()
        repeat(10) { assertNotNull(s.current()) }
        assertEquals(1, s.diskReads, "после сброса кэша должно быть ровно одно чтение")
    }

    @Test
    fun `каталог синхронизируется после переименования`() {
        val synced = mutableListOf<File>()
        val dir = temp.newFolder("with-sync")
        val s = SnapshotStore(dir) { synced += it }

        s.write(builder.build(rules(), settings))

        assertEquals(1, synced.size)
        assertEquals(dir, synced.single())
    }

    @Test
    fun `временный файл не остаётся после записи`() {
        val dir = temp.newFolder("tmp-check")
        SnapshotStore(dir).write(builder.build(rules(), settings))
        assertTrue(File(dir, SnapshotStore.FILE_NAME).isFile)
        assertFalse(File(dir, "${SnapshotStore.FILE_NAME}.tmp").exists())
    }

    @Test
    fun `отсутствующий снимок не бросает, а даёт null`() {
        // Горячий путь обязан получить null и разрешить звонок, а не исключение (ТЗ §1.1).
        val s = store()
        assertFalse(s.exists())
        assertNull(s.current())
        assertNotNull(s.lastFailure)
    }

    @Test
    fun `повреждённый снимок не бросает, а даёт null`() {
        val dir = temp.newFolder("broken")
        val s = SnapshotStore(dir)
        s.write(builder.build(rules(), settings))
        s.invalidate()

        File(dir, SnapshotStore.FILE_NAME).writeBytes("совсем не снимок".toByteArray())
        assertNull(s.current())
        assertTrue(s.lastFailure!!.contains("повреждён") || s.lastFailure!!.contains("прочитан"))
    }

    @Test
    fun `обрезанный снимок не бросает, а даёт null`() {
        val dir = temp.newFolder("truncated")
        val s = SnapshotStore(dir)
        s.write(builder.build(rules(), settings))
        s.invalidate()

        val file = File(dir, SnapshotStore.FILE_NAME)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 3))

        assertNull(s.current())
        assertNotNull(s.lastFailure)
    }

    @Test
    fun `перезапись заменяет снимок целиком`() {
        val s = store()
        s.write(builder.build(rules(), settings))
        assertEquals(2, s.current()!!.ruleCount)

        s.write(builder.build(emptyList(), settings))
        assertEquals(0, s.current()!!.ruleCount)

        s.invalidate()
        assertEquals(0, s.current()!!.ruleCount, "на диске тоже должен быть новый снимок")
    }

    @Test
    fun `заголовок читается без разбора секций`() {
        val s = store()
        s.write(builder.build(rules(), settings))
        val header = s.readHeader()
        assertNotNull(header)
        assertEquals(2, header!!.ruleCount)
    }

    @Test
    fun `clear удаляет и файл, и кэш`() {
        val s = store()
        s.write(builder.build(rules(), settings))
        s.clear()
        assertFalse(s.exists())
        assertNull(s.current())
    }
}
