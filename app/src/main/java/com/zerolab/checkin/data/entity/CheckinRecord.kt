package com.zerolab.checkin.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "checkin_record",
    indices = [Index("itemId"), Index(value = ["itemId", "checkinDate"])]
)
data class CheckinRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val checkinDate: String,        // yyyy-MM-dd（归属日期）
    val checkinTime: Long,
    val status: String,             // SUCCESS / FAIL / OFFSET
    val isAuto: Int = 0,
    val photoPath: String? = null,
    val textContent: String? = null,
    val voicePath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val extraJson: String? = null
)
