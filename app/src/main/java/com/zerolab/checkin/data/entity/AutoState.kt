package com.zerolab.checkin.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "auto_state")
data class AutoState(
    @PrimaryKey val itemId: Long,
    val lastAutoDate: String,
    val lastTriggerTs: Long
)
