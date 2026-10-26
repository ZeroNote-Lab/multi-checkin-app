package com.zerolab.checkin.data.repo

import com.zerolab.checkin.data.entity.AutoState
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.data.entity.OffsetCredit

/**
 * v1.4.2 重构：打卡引擎依赖的最小数据接口。
 *
 * [CheckinRepository] 是生产实现（Room）；单元测试用内存假实现验证状态机，
 * 不需要 Android / 数据库。新增引擎依赖的数据操作时，请同时补到本接口。
 */
interface CheckinStore {
    fun getItems(): List<CheckinItem>
    fun getItem(id: Long): CheckinItem?
    fun updateItem(item: CheckinItem)

    fun recordsOfDay(itemId: Long, date: String): List<CheckinRecord>
    fun allRecords(itemId: Long): List<CheckinRecord>
    fun insertRecord(r: CheckinRecord): Long

    fun availableCredits(itemId: Long): Int
    fun grantCredit(c: OffsetCredit): Long
    fun consumeOne(itemId: Long, date: String, recordId: Long?): Boolean

    fun autoState(itemId: Long): AutoState?
    fun saveAutoState(s: AutoState)
}
