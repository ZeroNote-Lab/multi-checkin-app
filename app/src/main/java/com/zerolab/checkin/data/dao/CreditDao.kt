package com.zerolab.checkin.data.dao

import androidx.room.*
import com.zerolab.checkin.data.entity.OffsetCredit

@Dao
interface CreditDao {
    @Insert
    fun insert(credit: OffsetCredit): Long

    @Query("SELECT * FROM offset_credit WHERE itemId = :itemId ORDER BY earnedDate ASC")
    fun ofItem(itemId: Long): List<OffsetCredit>

    @Query("SELECT COUNT(*) FROM offset_credit WHERE itemId = :itemId AND isUsed = 0")
    fun availableCount(itemId: Long): Int

    @Query("SELECT * FROM offset_credit WHERE itemId = :itemId AND isUsed = 0 ORDER BY earnedDate ASC LIMIT 1")
    fun firstAvailable(itemId: Long): OffsetCredit?

    @Update
    fun update(credit: OffsetCredit)

    // v1.3.8：一键修复工具用——还回某日消耗的盾牌（撤销自动补签）
    @Query("UPDATE offset_credit SET isUsed = 0, usedDate = NULL, usedRecordId = NULL WHERE itemId = :itemId AND isUsed = 1 AND usedDate = :date")
    fun returnUsedByDate(itemId: Long, date: String): Int
}
