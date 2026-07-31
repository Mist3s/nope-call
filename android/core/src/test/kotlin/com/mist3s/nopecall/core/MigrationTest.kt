package com.mist3s.nopecall.core

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.mist3s.nopecall.core.storage.NopeCallDatabase
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Миграции базы (архитектура §5.4).
 *
 * Тест существует потому, что `fallbackToDestructiveMigration` запрещён: правила — единственное,
 * что пользователь создал руками, и потерять их при обновлении нельзя. Схемы для этого
 * и экспортируются в репозиторий.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NopeCallDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `миграция 1 в 2 сохраняет правила и записи журнала`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO block_rules
                    (id, title, targetType, matchType, pattern, patternCanonical, patternVariants,
                     action, orderIndex, isEnabled, translitVariants, leetVariants, createdAt,
                     updatedAt, matchCount, errorCount, canonVersion)
                VALUES (1, 'Москва', 'NUMBER', 'PREFIX', '8495', '7495', '', 'REJECT', 600,
                        1, 0, 0, 0, 0, 3, 0, 1)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO screening_events
                    (id, occurredAt, rawNumber, digits, presentation, nameSource, action, reason,
                     degradations, latencyMs, budgetMs, canonVersion)
                VALUES (1, 1000, '+74951234567', '74951234567', 'ALLOWED', 'NONE', 'REJECT',
                        'RULE_MATCH', 0, 12, 1500, 1)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME,
            2,
            /* validateDroppedTables = */ true,
            *NopeCallDatabase.MIGRATIONS,
        )

        migrated.query("SELECT title, matchCount FROM block_rules WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst(), "правило обязано выжить")
            assertEquals("Москва", cursor.getString(0))
            assertEquals(3, cursor.getInt(1), "счётчик срабатываний тоже")
        }

        // Новые столбцы существуют и пусты: «не определяли» — не то же самое, что «нет».
        migrated.query(
            "SELECT coldStart, networkType, volte, extrasKeys FROM screening_events WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0), "coldStart у старой записи неизвестен")
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
        migrated.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
