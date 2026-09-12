package com.zerolab.checkin.data.dao

import androidx.room.*
import com.zerolab.checkin.data.entity.QuickConfig

@Dao
interface QuickDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(config: QuickConfig)

    @Query("SELECT * FROM quick_config WHERE id = 1")
    fun get(): QuickConfig?

    @Query("UPDATE quick_config SET itemId = :itemId, updatedAt = :ts WHERE id = 1")
    fun setItem(itemId: Long?, ts: Long)
}
