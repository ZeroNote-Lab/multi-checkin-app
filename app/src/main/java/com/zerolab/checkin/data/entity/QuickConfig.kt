package com.zerolab.checkin.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 全局唯一快捷打卡配置，id 固定为 1 */
@Entity(tableName = "quick_config")
data class QuickConfig(
    @PrimaryKey val id: Int = 1,
    val itemId: Long?,
    val updatedAt: Long = System.currentTimeMillis()
)
