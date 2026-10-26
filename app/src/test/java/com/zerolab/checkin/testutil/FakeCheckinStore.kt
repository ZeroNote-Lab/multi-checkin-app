package com.zerolab.checkin.testutil

import com.zerolab.checkin.data.entity.AutoState
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.data.entity.OffsetCredit
import com.zerolab.checkin.data.repo.CheckinStore

/**
 * v1.4.2：引擎与导入导出的内存假仓库。
 * 行为对齐 CheckinRepository 的关键点：插入打卡项时若没有快捷项则自动设为快捷。
 */
class FakeCheckinStore(initialItems: List<CheckinItem> = emptyList()) : CheckinStore {

    val itemList = initialItems.toMutableList()
    val records = mutableListOf<CheckinRecord>()
    val credits = mutableListOf<OffsetCredit>()
    private val autoStates = mutableMapOf<Long, AutoState>()
    private var nextItemId = (initialItems.maxOfOrNull { it.id } ?: 0L) + 1
    private var nextRecordId = 1000L
    private var nextCreditId = 5000L
    var quickItemId: Long? = null

    override fun getItems(): List<CheckinItem> = itemList.toList()
    override fun getItem(id: Long): CheckinItem? = itemList.firstOrNull { it.id == id }

    override fun updateItem(item: CheckinItem) {
        val i = itemList.indexOfFirst { it.id == item.id }
        if (i >= 0) itemList[i] = item
    }

    override fun insertItem(item: CheckinItem): Long {
        val id = if (item.id > 0) item.id else nextItemId++
        itemList.add(item.copy(id = id))
        // 与生产实现一致：空库首个打卡项自动成为快捷项
        if (quickItemId == null) quickItemId = id
        return id
    }

    override fun recordsOfDay(itemId: Long, date: String): List<CheckinRecord> =
        records.filter { it.itemId == itemId && it.checkinDate == date }

    override fun allRecords(itemId: Long): List<CheckinRecord> = records.filter { it.itemId == itemId }

    override fun insertRecord(r: CheckinRecord): Long {
        val id = nextRecordId++
        records.add(r.copy(id = id))
        return id
    }

    override fun availableCredits(itemId: Long): Int =
        credits.count { it.itemId == itemId && it.isUsed == 0 }

    override fun creditsOf(itemId: Long): List<OffsetCredit> = credits.filter { it.itemId == itemId }

    override fun grantCredit(c: OffsetCredit): Long {
        val id = nextCreditId++
        credits.add(c.copy(id = id))
        return id
    }

    override fun consumeOne(itemId: Long, date: String, recordId: Long?): Boolean {
        val i = credits.indexOfFirst { it.itemId == itemId && it.isUsed == 0 }
        if (i < 0) return false
        credits[i] = credits[i].copy(isUsed = 1, usedDate = date, usedRecordId = recordId)
        return true
    }

    override fun getQuickId(): Long? = quickItemId
    override fun setQuick(id: Long?) { quickItemId = id }

    override fun autoState(itemId: Long): AutoState? = autoStates[itemId]
    override fun saveAutoState(s: AutoState) { autoStates[s.itemId] = s }
}
