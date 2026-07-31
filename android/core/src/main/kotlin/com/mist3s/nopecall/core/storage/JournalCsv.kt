package com.mist3s.nopecall.core.storage

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Экспорт журнала в CSV (ТЗ §7.6).
 *
 * Выгружается **тот же объединённый журнал**, который показан на экране: страницами через
 * [JournalRepository.page], а не отдельным запросом по `screening_events`. Собственный запрос
 * был бы быстрее, но выгрузка перестала бы совпадать с тем, что пользователь видит — а расхождение
 * между экраном и файлом читается как потеря данных, и объяснить его нечем.
 *
 * Формат подчинён одной цели — открыться в Excel двойным щелчком, без диалога импорта:
 *  * UTF-8 **с BOM**: без него Excel читает файл в системной кодировке, и русские заголовки
 *    приходят «крокозябрами»;
 *  * разделитель `;`, а не запятая: в локалях с запятой в роли десятичного знака Excel считает
 *    разделителем полей именно точку с запятой;
 *  * перевод строки CRLF — как требует RFC 4180.
 *
 * Значения обезвреживаются от CSV-инъекции (см. [neutralizeFormula]): это не гипотетическая
 * угроза, а обычный случай — номер вида `+79991234567` Excel принимает за формулу.
 */
public class JournalCsv(
    private val journal: JournalRepository,
    /**
     * Зона для метки времени. Параметр, а не `ZoneId.systemDefault()` внутри: иначе тест на
     * формат даты зависел бы от зоны машины, на которой он запущен.
     */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Пишет журнал в поток. Возвращает число выгруженных строк (без заголовка).
     *
     * Поток **не закрывается**: им владеет вызывающая сторона — обычно это `OutputStream`
     * от `ContentResolver`, и закрывать его дважды нельзя. Буфер при выходе сбрасывается,
     * иначе последняя порция осталась бы в памяти писателя.
     *
     * @param filter те же фильтры, что и у экрана журнала, включая период `fromAt`/`toAt`
     * @param pageSize размер порции чтения из базы; на память влияет только он, а не размер журнала
     */
    public suspend fun writeTo(
        out: OutputStream,
        filter: JournalFilter = JournalFilter(),
        pageSize: Int = PAGE_SIZE,
    ): Int {
        // Писатель создаётся здесь, а не принимается параметром: кодировка — часть формата,
        // и отдавать её наружу значило бы позволить выгрузку без UTF-8, то есть без смысла.
        val writer = OutputStreamWriter(out, Charsets.UTF_8).buffered()
        val written = writeTo(writer, filter, pageSize)
        writer.flush()
        return written
    }

    /**
     * Тот же экспорт в готовый [Writer] — для случаев, когда поток уже обёрнут вызывающей
     * стороной. Кодировку в этом случае обеспечивает она: BOM здесь пишется как символ
     * `U+FEFF`, и в UTF-8 он даст нужные три байта, а в другой кодировке — мусор.
     */
    public suspend fun writeTo(
        writer: Writer,
        filter: JournalFilter = JournalFilter(),
        pageSize: Int = PAGE_SIZE,
    ): Int {
        writer.write(BOM)
        writeRow(writer, HEADERS)

        var written = 0
        var cursor: JournalCursor? = null
        do {
            val page = journal.page(cursor = cursor, filter = filter, limit = pageSize)
            for (item in page.items) {
                writeRow(writer, columnsOf(item))
                written++
            }
            // Сброс после каждой порции: журнал бывает на десятки тысяч записей, и держать их
            // в буфере до конца экспорта незачем — весь смысл постраничного чтения был бы потерян.
            writer.flush()
            cursor = page.next
        } while (cursor != null)

        return written
    }

    /** Значения строки в порядке [HEADERS]. */
    private fun columnsOf(item: JournalItem): List<String> = listOf(
        formatAt(item.occurredAt),
        item.rawNumber,
        item.e164.orEmpty(),
        item.nameRaw.orEmpty(),
        item.nameSource,
        item.kind,
        item.action.orEmpty(),
        item.reason.orEmpty(),
        item.matchedRuleTitle.orEmpty(),
        // Пусто, а не ноль: `null` здесь значит «исход неизвестен», и «0» на его месте
        // читался бы как «звонок длился ноль секунд» (ТЗ §7.2).
        item.durationSeconds?.toString().orEmpty(),
        item.latencyMs?.toString().orEmpty(),
    )

    private fun writeRow(writer: Writer, values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) writer.write(SEPARATOR.toString())
            writer.write(field(value))
        }
        writer.write(EOL)
    }

    /**
     * ISO 8601 с локальным смещением: `2027-01-15T10:30:00+03:00`.
     *
     * Смещение обязательно. Без него метка времени неотличима от UTC, и выгрузки с разных
     * устройств нельзя сопоставить. Дробные доли секунды отброшены намеренно: точность журнала
     * — секунда, а лишние знаки Excel показывает как есть и только мешают читать.
     */
    private fun formatAt(at: Long): String =
        FORMAT.format(Instant.ofEpochMilli(at).atZone(zone))

    public companion object {

        /** Заголовки в порядке колонок. Русские: файл открывает человек, а не программа. */
        public val HEADERS: List<String> = listOf(
            "когда", "номер", "e164", "название", "источник названия", "тип записи",
            "решение", "причина", "правило", "длительность", "задержка",
        )

        /** Разделитель полей: `;`, как требует ТЗ §7.6. */
        public const val SEPARATOR: Char = ';'

        /** CRLF: Excel открывает такой файл без вопросов, RFC 4180 требует именно его. */
        public const val EOL: String = "\r\n"

        /**
         * Метка порядка байтов. В UTF-8 даёт `EF BB BF`; без неё Excel читает файл в системной
         * кодировке и русские заголовки приходят «крокозябрами».
         *
         * Записана escape-последовательностью, а не самим символом: `U+FEFF` невидим, и в
         * исходнике его нельзя ни увидеть, ни отличить от случайно потерянного.
         */
        public const val BOM: String = "\uFEFF"

        /** Тип содержимого для `Intent`/`ContentResolver` при отдаче файла. */
        public const val MIME_TYPE: String = "text/csv"

        /**
         * Размер порции чтения. Значение не критично: важно лишь, что оно ограничено — весь
         * журнал в памяти не собирается ни при каком размере выгрузки.
         */
        public const val PAGE_SIZE: Int = 500

        private val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        /**
         * Символы, с которых Excel начинает считать значение формулой (CSV-инъекция).
         *
         * `-` в списке вместе с `=`: `-1+1` для Excel тоже выражение. Наши числовые колонки
         * отрицательными не бывают, поэтому список безопасен для них целиком.
         */
        private val FORMULA_STARTS = charArrayOf('=', '+', '-', '@', '\t', '\r')

        /**
         * Поле в виде, пригодном для записи: сначала обезвреживание формулы, потом экранирование.
         *
         * Порядок именно такой. Обезвреживание добавляет символ в начало значения, и если бы
         * оно шло после экранирования, апостроф оказался бы **перед** открывающей кавычкой —
         * то есть кавычка перестала бы быть открывающей, и строка развалилась бы на поля.
         */
        internal fun field(value: String): String = escape(neutralizeFormula(value))

        /**
         * Экранирование по RFC 4180: значение с разделителем, кавычкой или переводом строки
         * берётся в кавычки, внутренние кавычки удваиваются.
         *
         * Кавычки в названии звонящего — не редкость, а норма: операторская подпись приходит
         * в виде `ООО "Ромашка"`. Без удвоения такое значение обрывает поле на середине,
         * и дальше съезжают все колонки строки.
         */
        internal fun escape(value: String): String {
            val needsQuotes = value.any {
                it == SEPARATOR || it == '"' || it == '\n' || it == '\r'
            }
            if (!needsQuotes) return value
            return "\"" + value.replace("\"", "\"\"") + "\""
        }

        /**
         * Обезвреживание CSV-инъекции: значение, которое Excel исполнил бы как формулу,
         * получает ведущий апостроф — признак «это текст».
         *
         * Апостроф, а не альтернативы. Отброшены две:
         *  * **удалить ведущий символ** — портит данные: `+79991234567` перестаёт быть номером,
         *    а выгрузка журнала существует ровно для того, чтобы номера в ней были верными;
         *  * **обернуть в `="…"`** — сама формула и есть; программа, которая её не понимает,
         *    покажет пользователю `="+7999…"` дословно.
         *
         * Кавычки вокруг значения от исполнения формулы **не спасают**: Excel снимает их при
         * импорте и вычисляет то, что внутри.
         */
        internal fun neutralizeFormula(value: String): String =
            if (value.isNotEmpty() && value[0] in FORMULA_STARTS) "'$value" else value
    }
}
