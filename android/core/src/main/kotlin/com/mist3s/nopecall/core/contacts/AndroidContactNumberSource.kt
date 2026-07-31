package com.mist3s.nopecall.core.contacts

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import com.mist3s.nopecall.engine.NumberForms
import com.mist3s.nopecall.engine.PhoneNumberNormalizer

/**
 * Телефонная книга через `ContentResolver` — разовым проходом, для предпросмотра правила
 * (ТЗ §9.3, критерий приёмки §18 п. 16).
 *
 * Единственное место, где предпросмотр видит `ContactsContract`. Вся арифметика показателя
 * живёт в `JournalRepository` и потому проверяется без устройства (архитектура §12.1).
 *
 * Публичный, а не `internal`, в отличие от `AndroidCallLogSource`: источник создаёт тот, у кого
 * есть `Context` интерфейсного экрана, — мост в `:app`. Отдавать его через граф `:core` смысла
 * нет, потому что к горячему пути он не имеет отношения и в снимке не участвует.
 *
 * Вызывается **не** из горячего пути. Прочитанные номера никуда не записываются: результат
 * возвращается вызывающему и живёт до конца показа предпросмотра.
 */
public class AndroidContactNumberSource(
    private val context: Context,
    private val normalizer: PhoneNumberNormalizer,
    private val region: String = "RU",
) : ContactNumberSource {

    override fun numbers(limit: Int): List<NumberForms>? {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Именно `null`: «разрешения нет» и «в книге ноль номеров» — разные утверждения,
            // и второе нельзя показывать вместо первого (ТЗ §1.1).
            return null
        }

        // Ключ — каноническая форма: у одного контакта номер часто записан дважды (`+7…`
        // и `8…`), а у разных контактов совпадает рабочий номер. Без свёртки показатель
        // «зацепит N номеров книги» считал бы одно и то же несколько раз.
        val byCanonical = LinkedHashMap<String, NumberForms>(INITIAL_CAPACITY)
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                // Порядок не нужен: показатель — число, а не список. Сортировка провайдера
                // на нескольких тысячах строк стоит дороже, чем даёт.
                null,
            )?.use { cursor ->
                // Предел накладывается чтением, а не `LIMIT` в `sortOrder`: провайдеры
                // разбирают запрос в строгом режиме и отвергают дописанный `LIMIT`
                // (на журнале звонков это уже стоило отладки).
                while (byCanonical.size < limit && cursor.moveToNext()) {
                    val raw = cursor.getString(0) ?: continue
                    val forms = normalizer.normalize(raw, region)
                    val key = forms.canonicalDigits.ifEmpty { forms.digits }
                    if (key.isNotEmpty()) byCanonical.putIfAbsent(key, forms)
                }
                byCanonical.values.toList()
            }
            // `?.use` вернул `null` — провайдер не ответил вовсе. Это тоже «не знаю»,
            // а не пустая книга, поэтому подстановки `emptyList()` здесь нет.
        } catch (t: Throwable) {
            Log.w(TAG, "не удалось прочитать контакты для предпросмотра", t)
            null
        }
    }

    private companion object {
        const val TAG = "NopeCallContacts"
        const val INITIAL_CAPACITY = 512
    }
}
