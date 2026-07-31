package com.mist3s.nopecall.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * База данных приложения (ТЗ §8.3, архитектура §5.4).
 *
 * Лежит в обычном (Credential Encrypted) хранилище — не в Device Protected. Причина не в
 * удобстве: журнал звонков и имена чувствительнее правил, и держать их в хранилище, доступном
 * системе без учётных данных пользователя, нельзя. Горячий путь до разблокировки обходится
 * снимком правил, а не базой.
 *
 * `exportSchema = true` обязателен с первой версии: без схем в репозитории нельзя написать тест
 * миграции, а терять созданные пользователем правила при обновлении недопустимо.
 */
@Database(
    entities = [
        RuleEntity::class,
        SettingEntity::class,
        ScreeningEventEntity::class,
        CallLogMirrorEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
public abstract class NopeCallDatabase : RoomDatabase() {

    public abstract fun rules(): RuleDao
    public abstract fun settings(): SettingsDao
    public abstract fun events(): ScreeningEventDao
    public abstract fun mirror(): CallLogMirrorDao

    /** Объединение двух слоёв журнала. Своих таблиц не имеет (ТЗ §7.3). */
    public abstract fun feed(): JournalFeedDao

    public companion object {
        public const val NAME: String = "nope-call.db"

        @Volatile
        private var instance: NopeCallDatabase? = null

        /**
         * Открывает базу. **Только после разблокировки экрана**: до неё хранилище недоступно,
         * и обращение сюда из фазы 1 или из горячего пути — ошибка (архитектура §3.1).
         */
        public fun get(context: Context): NopeCallDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): NopeCallDatabase =
            Room.databaseBuilder(context.applicationContext, NopeCallDatabase::class.java, NAME)
                // Никаких fallbackToDestructiveMigration: правила пользователя — единственное,
                // что он создал руками, и потерять их при обновлении нельзя.
                .addMigrations(*MIGRATIONS)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        /**
         * Миграции. Только `ADD COLUMN` с nullable-полями: пересоздание таблицы событий
         * потребовало бы копирования всего журнала, а он может быть на десятки тысяч записей.
         *
         * Backfill здесь **запрещён**: миграция блокирует открытие базы, а открытие базы
         * происходит при первом же обращении интерфейса. Досчитывать что-либо по журналу
         * нужно фоновой задачей, а не тут (CLAUDE.md §3.7).
         */
        internal val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Диагностика звонка и контекст сети: нужны сводке режима наблюдения
                    // (ТЗ §7.7.5) и диагностике (§9.7).
                    db.execSQL("ALTER TABLE screening_events ADD COLUMN coldStart INTEGER")
                    db.execSQL("ALTER TABLE screening_events ADD COLUMN directBoot INTEGER")
                    db.execSQL("ALTER TABLE screening_events ADD COLUMN networkType TEXT")
                    db.execSQL("ALTER TABLE screening_events ADD COLUMN volte INTEGER")
                    db.execSQL("ALTER TABLE screening_events ADD COLUMN operatorName TEXT")
                    db.execSQL("ALTER TABLE screening_events ADD COLUMN roaming INTEGER")
                    db.execSQL("ALTER TABLE screening_events ADD COLUMN extrasKeys TEXT")
                }
            },
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Признак «название узнали позже». Раньше это состояние кодировалось
                    // затиранием источника на SYSTEM_LOG, из-за чего показатель «подпись
                    // оператора пришла позже» считал таковым имя из телефонной книги.
                    db.execSQL("ALTER TABLE screening_events ADD COLUMN nameLate INTEGER")

                    // Уже записанные поздние названия помечаются флагом, но источник у них
                    // остаётся неустановленным: задним числом выяснить, было ли имя контактом,
                    // нельзя, а записать догадку в данные — значит потом на неё опереться.
                    db.execSQL(
                        "UPDATE screening_events SET nameLate = 1 WHERE nameSource = 'SYSTEM_LOG'"
                    )
                }
            },
        )

        /** Для тестов: сбросить синглтон между прогонами. */
        internal fun resetForTests() {
            instance = null
        }
    }
}
