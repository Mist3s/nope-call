package com.mist3s.nopecall.core.snapshot

import com.mist3s.nopecall.engine.RuleSnapshot
import com.mist3s.nopecall.engine.SnapshotCodec
import com.mist3s.nopecall.engine.SnapshotFormatException
import com.mist3s.nopecall.engine.SnapshotHeader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Синхронизация каталога после `rename`.
 *
 * Отдельный интерфейс не ради красоты: `fsync` каталога делается через `android.system.Os`,
 * которого нет на голой JVM. Без этого шва хранилище нельзя было бы протестировать без
 * устройства, а проверить надо именно то, что синхронизация **происходит** — иначе `rename`
 * не durable, и после потери питания снимок может отсутствовать (архитектура §5.2).
 */
public fun interface DirectorySync {
    public fun sync(dir: File)

    public companion object {
        /** Ничего не делает. Только для тестов и для JVM, где `Os` недоступен. */
        public val NONE: DirectorySync = DirectorySync { }
    }
}

/**
 * Файл снимка правил в Device Protected Storage (архитектура §5.2).
 *
 * Три свойства, ради которых этот класс существует:
 *
 *  1. **Чтение никогда не бросает.** Горячий путь получает `null` и разрешает звонок с причиной
 *     `SNAPSHOT_UNAVAILABLE` — по принципу ТЗ §1.1 отказ не может привести к блокировке.
 *  2. **Запись атомарна**: временный файл → `fsync` файла → `rename` → `fsync` каталога.
 *     Читатель либо видит прежний снимок, либо новый целиком, но никогда — половину.
 *  3. **Тёплый путь файл не перечитывает.** Ссылка на разобранный снимок иммутабельна
 *     и публикуется через `@Volatile`: замена целиком, без частичных обновлений.
 *
 * Принимает каталог, а не `Context`, чтобы тестироваться на голой JVM.
 */
public class SnapshotStore(
    private val dir: File,
    private val directorySync: DirectorySync = DirectorySync.NONE,
) {
    private val file = File(dir, FILE_NAME)
    private val tmpFile = File(dir, "$FILE_NAME.tmp")

    @Volatile
    private var cached: RuleSnapshot? = null

    @Volatile
    private var lastError: String? = null

    /** Сколько раз снимок читался с диска за жизнь процесса. Для диагностики. */
    @Volatile
    public var diskReads: Int = 0
        private set

    public val cachedSnapshot: RuleSnapshot?
        get() = cached

    public val lastFailure: String?
        get() = lastError

    public fun exists(): Boolean = file.isFile

    /**
     * Снимок для принятия решения. Из кэша, если он есть; иначе одно чтение с диска.
     *
     * @param verifyChecksums полная проверка сумм секций. В горячем пути `false`: заголовок
     *   уже поймал обрезанный файл и чужой формат, а хеширование всего объёма — это отдельные
     *   миллисекунды (архитектура §5.2).
     */
    public fun current(verifyChecksums: Boolean = false): RuleSnapshot? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: readFromDisk(verifyChecksums)?.also { cached = it }
        }
    }

    /** Заголовок без разбора секций — для диагностики и для проверки версии канонизации. */
    public fun readHeader(): SnapshotHeader? = try {
        SnapshotCodec.readHeader(ByteBuffer.wrap(file.readBytes()))
    } catch (t: Throwable) {
        lastError = t.message
        null
    }

    private fun readFromDisk(verifyChecksums: Boolean): RuleSnapshot? = try {
        if (!file.isFile) {
            lastError = "снимка нет"
            null
        } else {
            diskReads++
            val bytes = file.readBytes()
            SnapshotCodec.decode(ByteBuffer.wrap(bytes), verifyChecksums).also { lastError = null }
        }
    } catch (e: SnapshotFormatException) {
        // Битый снимок — не повод блокировать звонки. Пересборка ставится в очередь снаружи.
        lastError = "снимок повреждён: ${e.message}"
        null
    } catch (t: Throwable) {
        lastError = "снимок не прочитан: ${t.message}"
        null
    }

    /**
     * Атомарная запись. Выполняется вне горячего пути — при изменении правил и при пересборке.
     *
     * `fsync` **файла** без `fsync` **каталога** не делает `rename` устойчивым к потере питания:
     * запись данных может дойти до диска, а запись новой ссылки в каталоге — нет.
     */
    @Throws(IOException::class)
    public fun write(snapshot: RuleSnapshot) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("не удалось создать каталог ${dir.path}")
        }
        val bytes = SnapshotCodec.encode(snapshot)

        FileOutputStream(tmpFile).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmpFile.renameTo(file)) {
            tmpFile.delete()
            throw IOException("не удалось заменить ${file.path}")
        }
        directorySync.sync(dir)

        // Публикуем разобранный снимок сразу: перечитывать только что записанное незачем.
        cached = snapshot
        lastError = null
    }

    /**
     * Сбрасывает кэш. Следующее обращение прочитает файл заново.
     *
     * Нужно после пересборки снимка чужой стороной и после `MY_PACKAGE_REPLACED`.
     */
    public fun invalidate() {
        cached = null
    }

    /** Удаляет снимок и кэш. Для тестов и для полного сброса. */
    public fun clear() {
        cached = null
        file.delete()
        tmpFile.delete()
    }

    public companion object {
        public const val FILE_NAME: String = "snapshot.bin"
    }
}
