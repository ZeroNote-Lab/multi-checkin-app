package com.zerolab.checkin.data.dao

import androidx.room.*
import com.zerolab.checkin.data.entity.CheckinRecord

@Dao
interface RecordDao {
    @Insert
    fun insert(record: CheckinRecord): Long

    @Query("SELECT * FROM checkin_record WHERE itemId = :itemId AND checkinDate BETWEEN :start AND :end ORDER BY checkinTime ASC")
    fun between(itemId: Long, start: String, end: String): List<CheckinRecord>

    @Query("SELECT * FROM checkin_record WHERE itemId = :itemId ORDER BY checkinTime DESC")
    fun allOf(itemId: Long): List<CheckinRecord>

    @Query("SELECT COUNT(*) FROM checkin_record WHERE itemId = :itemId AND checkinDate = :date")
    fun countOfDay(itemId: Long, date: String): Int

    @Query("SELECT DISTINCT checkinDate FROM checkin_record WHERE itemId = :itemId")
    fun recordDates(itemId: Long): List<String>

    @Query("DELETE FROM checkin_record WHERE itemId = :itemId")
    fun deleteByItem(itemId: Long)
}
