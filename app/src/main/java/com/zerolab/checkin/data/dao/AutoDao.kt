package com.zerolab.checkin.data.dao

import androidx.room.*
import com.zerolab.checkin.data.entity.AutoState

@Dao
interface AutoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: AutoState)

    @Query("SELECT * FROM auto_state WHERE itemId = :itemId")
    fun get(itemId: Long): AutoState?

    @Query("SELECT * FROM auto_state")
    fun all(): List<AutoState>
}
