package com.zerolab.checkin.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "offset_credit", indices = [Index("itemId")])
data class OffsetCredit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val mode: String,               // A / B / C
    val earnedDate: String,
    val expireDate: String? = null,
    val isUsed: Int = 0,
    val usedDate: String? = null,
    val usedRecordId: Long? = null
)
