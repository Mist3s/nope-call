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
    version = 1,
    exportSchema = true,
)
public abstract class NopeCallDatabase : RoomDatabase() {

    public abstract fun rules(): RuleDao
    public abstract fun settings(): SettingsDao
    public abstract fun events(): ScreeningEventDao
    public abstract fun mirror(): CallLogMirrorDao

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
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        /** Для тестов: сбросить синглтон между прогонами. */
        internal fun resetForTests() {
            instance = null
        }
    }
}
