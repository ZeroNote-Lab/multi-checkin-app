package com.zerolab.checkin.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "checkin_item")
data class CheckinItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,                 // 主方式；多方式时为 MULTI
    val configJson: String,
    val icon: String = "default",
    val theme: String = "sakura",
    val groupTag: String? = null,
    val sortOrder: Int = 0,
    val isPinned: Int = 0,
    val pinnedAt: Long? = null,
    val isActive: Int = 1,
    val editPolicy: String = "LOCKED",
    val editInterval: Int? = null,
    val lastEditDate: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
