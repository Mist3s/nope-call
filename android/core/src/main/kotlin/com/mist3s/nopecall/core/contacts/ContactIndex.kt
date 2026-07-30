package com.mist3s.nopecall.core.contacts

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import com.mist3s.nopecall.core.facts.ContactMembership
import com.mist3s.nopecall.engine.PhoneNumberNormalizer
import java.io.File
import java.security.MessageDigest

/**
 * Индекс номеров из телефонной книги (ТЗ §6.4, архитектура §5.3).
 *
 * **В индексе лежат только усечённые хеши номеров, без имён.** Это не перестраховка: файл живёт
 * в Device Protected Storage, доступном системе без учётных данных пользователя, а адресная книга
 * с именами чувствительнее правил и сопоставима с журналом. Горячему пути имена не нужны —
 * правилу `IN_CONTACTS` нужна только проверка принадлежности.
 *
 * Коллизия усечённого хеша (пренебрежимо маловероятная при тысячах контактов) даёт ложное
 * «номер в контактах», то есть ошибку в сторону **разрешения** звонка — направление,
 * согласованное с ТЗ §1.1.
 *
 * Отдельный файл, а не часть снимка правил: `ContactsContract` уведомляет об изменениях очень
 * часто (синхронизация аккаунтов, мессенджеры), и пересобирать весь снимок по каждому событию —
 * заметный износ и расход батареи ни на что.
 */
public class ContactIndex(
    private val dir: File,
    private val normalizer: PhoneNumberNormalizer,
) : ContactMembership {

    private val file = File(dir, FILE_NAME)

    /** Отсортированный массив хешей: бинарный поиск, ноль аллокаций на проверку. */
    @Volatile
    private var hashes: LongArray? = null

    @Volatile
    private var loaded = false

    /**
     * @return `true`/`false` если индекс есть; `null` если его нет или он не прочитан —
     *   тогда решение помечается флагом `CONTACT_INDEX_STALE`, а не притворяется знанием.
     */
    override fun contains(e164: String?): Boolean? {
        if (e164.isNullOrEmpty()) return null
        val index = current() ?: return null
        return index.binarySearch(hashOf(e164)) >= 0
    }

    public fun isAvailable(): Boolean = current() != null

    public fun size(): Int = current()?.size ?: 0

    private fun current(): LongArray? {
        hashes?.let { return it }
        if (loaded) return null
        synchronized(this) {
            if (loaded) return hashes
            loaded = true
            hashes = readFile()
            return hashes
        }
    }

    private fun readFile(): LongArray? = try {
        if (!file.isFile) {
            null
        } else {
            val bytes = file.readBytes()
            if (bytes.size % 8 != 0) {
                null
            } else {
                LongArray(bytes.size / 8) { i ->
                    var v = 0L
                    for (b in 0 until 8) v = (v shl 8) or (bytes[i * 8 + b].toLong() and 0xFF)
                    v
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "индекс контактов не прочитан", t)
        null
    }

    /**
     * Перестраивает индекс из телефонной книги.
     *
     * Выполняется вне горячего пути: обращение к `ContentProvider` из `onScreenCall` запрещено —
     * синхронный запрос нельзя прервать по таймауту, а типовой отказ это как раз холодный старт
     * провайдера (архитектура §5.3).
     *
     * @return число номеров в индексе, либо `-1` если разрешения нет.
     */
    public fun rebuild(context: Context, region: String = "RU"): Int {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Без разрешения система и так обычно не присылает нам звонки от контактов,
            // поэтому пустой индекс здесь — не потеря, а честное «не знаю» (ТЗ §6.4).
            return -1
        }

        val collected = HashSet<Long>(1024)
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(0) ?: continue
                    val forms = normalizer.normalize(raw, region)
                    val key = forms.e164 ?: forms.canonicalDigits.ifEmpty { null } ?: continue
                    collected += hashOf(key)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "не удалось прочитать контакты", t)
            return -1
        }

        val sorted = collected.toLongArray().apply { sort() }
        return if (write(sorted)) {
            hashes = sorted
            loaded = true
            sorted.size
        } else {
            -1
        }
    }

    private fun write(sorted: LongArray): Boolean = try {
        if (!dir.isDirectory) dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        val bytes = ByteArray(sorted.size * 8)
        for (i in sorted.indices) {
            var v = sorted[i]
            for (b in 7 downTo 0) {
                bytes[i * 8 + b] = (v and 0xFF).toByte()
                v = v ushr 8
            }
        }
        tmp.writeBytes(bytes)
        tmp.renameTo(File(dir, FILE_NAME))
    } catch (t: Throwable) {
        Log.w(TAG, "индекс контактов не записан", t)
        false
    }

    public fun invalidate() {
        hashes = null
        loaded = false
    }

    /** Старшие 8 байт SHA-256: имя третьего лица в файл не попадает. */
    private fun hashOf(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (digest[i].toLong() and 0xFF)
        return v
    }

    public companion object {
        public const val FILE_NAME: String = "contacts.idx"
        private const val TAG = "NopeCallContacts"
    }
}
