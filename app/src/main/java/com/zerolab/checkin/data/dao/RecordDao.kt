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

    // v1.3.8：一键修复工具用——删除单条记录 / 改单条记录状态
    @Query("DELETE FROM checkin_record WHERE id = :id")
    fun deleteById(id: Long)

    @Query("UPDATE checkin_record SET status = :status WHERE id = :id")
    fun updateStatus(id: Long, status: String)
}
