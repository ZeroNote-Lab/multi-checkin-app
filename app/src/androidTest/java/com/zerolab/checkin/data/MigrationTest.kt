package com.zerolab.checkin.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.4.2：Room 迁移链验证（1 → 2）。需要模拟器 / 真机（connectedDebugAndroidTest）。
 *
 * v1 → v2 是无结构变更的迁移，本测试确保：
 *  - v1 数据库能被迁移到 v2 且 schema 校验通过；
 *  - 迁移前写入的打卡项 / 记录在迁移后仍可正常读取。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun freshName(): String {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        ctx.deleteDatabase(name)
        return name
    }

    @Test
    fun migrate1To2_preservesExistingRows() {
        val dbName = freshName()
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO checkin_item " +
                    "(id, name, type, configJson, icon, theme, groupTag, sortOrder, isPinned, pinnedAt, " +
                    "isActive, editPolicy, editInterval, lastEditDate, createdAt, updatedAt) " +
                    "VALUES (1, '旧打卡项', 'NORMAL', '{}', 'default', 'sakura', NULL, 0, 0, NULL, " +
                    "1, 'LOCKED', NULL, NULL, 1000, 1000)"
            )
            execSQL(
                "INSERT INTO checkin_record " +
                    "(id, itemId, checkinDate, checkinTime, status, isAuto, photoPath, textContent, " +
                    "voicePath, latitude, longitude, extraJson) " +
                    "VALUES (10, 1, '2026-10-20', 1790000000000, 'SUCCESS', 0, NULL, '迁移前的文字', " +
                    "NULL, NULL, NULL, NULL)"
            )
            close()
        }

        helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
            dbName
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("旧打卡项", db.itemDao().getById(1)?.name)
            val records = db.recordDao().allOf(1)
            assertEquals(1, records.size)
            assertEquals("迁移前的文字", records.first().textContent)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate1To2_works_on_emptyDatabase() {
        val dbName = freshName()
        helper.createDatabase(dbName, 1).close()
        helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)
    }
}
