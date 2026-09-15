package com.zerolab.checkin.data.dao

import androidx.room.*
import com.zerolab.checkin.data.entity.CheckinItem

@Dao
interface ItemDao {
    @Insert
    fun insert(item: CheckinItem): Long

    @Update
    fun update(item: CheckinItem)

    @Delete
    fun delete(item: CheckinItem)

    @Query("DELETE FROM checkin_item WHERE id = :id")
    fun deleteById(id: Long)

    @Query("SELECT * FROM checkin_item ORDER BY isPinned DESC, pinnedAt DESC, sortOrder ASC, createdAt ASC")
    fun getAllSorted(): List<CheckinItem>

    @Query("SELECT * FROM checkin_item WHERE isActive = 1 AND id != :excludeId ORDER BY isPinned DESC, pinnedAt DESC, sortOrder ASC, createdAt ASC LIMIT 1")
    fun getFirstActive(excludeId: Long): CheckinItem?

    @Query("SELECT * FROM checkin_item WHERE id = :id")
    fun getById(id: Long): CheckinItem?

    @Query("SELECT COUNT(*) FROM checkin_item")
    fun count(): Int

    // v1.1.7：拖拽排序持久化
    @Query("UPDATE checkin_item SET sortOrder = :order WHERE id = :id")
    fun updateSortOrder(id: Long, order: Int)
}
