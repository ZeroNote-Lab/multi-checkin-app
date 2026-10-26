package com.zerolab.checkin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zerolab.checkin.data.dao.*
import com.zerolab.checkin.data.entity.*

@Database(
    entities = [
        CheckinItem::class,
        QuickConfig::class,
        CheckinRecord::class,
        OffsetCredit::class,
        AutoState::class
    ],
    version = 2,
    // v1.4.2：导出 schema 供 Room MigrationTestHelper 使用（app/schemas 入库）
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun quickDao(): QuickDao
    abstract fun recordDao(): RecordDao
    abstract fun creditDao(): CreditDao
    abstract fun autoDao(): AutoDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v1.4.2 优化：迁移链起点（v1 → v2 无表结构变更，仅建立升级通道）。
         *
         * 以后任何实体字段变更必须：
         *  1) 把 [Database.version] +1；
         *  2) 在这里新增对应 MIGRATION_x_y 并加入 [MIGRATIONS]；
         *  3) 在模拟器上装旧版本再覆盖安装验证。
         *
         * 禁止使用 fallbackToDestructiveMigration()——那会在 schema 变化时静默清空用户全部打卡数据。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v1 → v2：无表结构变更，仅把用户库版本推进到迁移链起点
            }
        }

        private val MIGRATIONS = arrayOf(MIGRATION_1_2)

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "checkin6.db"
                )
                    .addMigrations(*MIGRATIONS)
                    // v1.4.2 优化：主线程数据库访问仅剩单行 getItem 之类的小查询，
                    // 重计算（连续天数 / 月历状态 / 补签结算）已移到后台线程（QuickCheckinFragment 快照）
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
