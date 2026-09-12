package com.zerolab.checkin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun quickDao(): QuickDao
    abstract fun recordDao(): RecordDao
    abstract fun creditDao(): CreditDao
    abstract fun autoDao(): AutoDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "checkin6.db"
                ).allowMainThreadQueries().build().also { INSTANCE = it }
            }
        }
    }
}
