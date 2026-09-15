package com.zerolab.checkin.data.repo

import com.zerolab.checkin.data.AppDatabase
import com.zerolab.checkin.data.entity.*

class CheckinRepository(private val db: AppDatabase) {

    private val itemDao = db.itemDao()
    private val quickDao = db.quickDao()
    private val recordDao = db.recordDao()
    private val creditDao = db.creditDao()
    private val autoDao = db.autoDao()

    // ---------- 打卡项 ----------
    fun getItems(): List<CheckinItem> = itemDao.getAllSorted()
    fun getItem(id: Long): CheckinItem? = itemDao.getById(id)
    fun itemCount(): Int = itemDao.count()

    fun insertItem(item: CheckinItem): Long {
        val id = itemDao.insert(item)
        ensureQuickAfterCreate(id)
        return id
    }

    fun updateItem(item: CheckinItem) = itemDao.update(item.copy(updatedAt = System.currentTimeMillis()))

    fun deleteItem(id: Long) {
        val quick = quickDao.get()
        if (quick?.itemId == id) {
            val next = itemDao.getFirstActive(id)
            quickDao.upsert(QuickConfig(1, next?.id))
        }
        recordDao.deleteByItem(id)
        itemDao.deleteById(id)
    }

    fun setPinned(id: Long, pinned: Boolean) {
        val it = itemDao.getById(id) ?: return
        if (pinned) {
            itemDao.update(
                it.copy(isPinned = 1, pinnedAt = System.currentTimeMillis())
            )
        } else {
            // v1.1.7：取消置顶 → 插入未置顶区最上方（sortOrder 最小），其余未置顶依次后移
            val unpinned = itemDao.getAllSorted().filter { x -> x.id != id && x.isPinned == 0 }
            itemDao.update(it.copy(isPinned = 0, pinnedAt = null, sortOrder = 0))
            unpinned.forEachIndexed { idx, x -> itemDao.updateSortOrder(x.id, idx + 1) }
        }
    }
    fun setSortOrder(id: Long, order: Int) = itemDao.updateSortOrder(id, order)

    fun setActive(id: Long, active: Boolean) {
        val it = itemDao.getById(id) ?: return
        itemDao.update(it.copy(isActive = if (active) 1 else 0))
    }

    // ---------- 快捷打卡 ----------
    fun getQuickItem(): CheckinItem? {
        val id = quickDao.get()?.itemId ?: return null
        return itemDao.getById(id)
    }
    fun getQuickId(): Long? = quickDao.get()?.itemId

    fun setQuick(id: Long?) = quickDao.upsert(QuickConfig(1, id))

    private fun ensureQuickAfterCreate(newId: Long) {
        val cfg = quickDao.get()
        if (cfg == null || cfg.itemId == null) {
            quickDao.upsert(QuickConfig(1, newId))
        }
    }

    // ---------- 打卡记录 ----------
    fun recordsOfMonth(itemId: Long, startDate: String, endDate: String) =
        recordDao.between(itemId, startDate, endDate)
    fun recordsOfDay(itemId: Long, date: String) =
        recordDao.between(itemId, date, date)
    fun allRecords(itemId: Long) = recordDao.allOf(itemId)
    fun countOfDay(itemId: Long, date: String) = recordDao.countOfDay(itemId, date)
    fun insertRecord(r: CheckinRecord): Long = recordDao.insert(r)

    // ---------- 抵消 ----------
    fun creditsOf(itemId: Long) = creditDao.ofItem(itemId)
    fun availableCredits(itemId: Long): Int = creditDao.availableCount(itemId)
    fun grantCredit(c: OffsetCredit): Long = creditDao.insert(c)
    fun consumeOne(itemId: Long, date: String, recordId: Long?): Boolean {
        val c = creditDao.firstAvailable(itemId) ?: return false
        creditDao.update(c.copy(isUsed = 1, usedDate = date, usedRecordId = recordId))
        return true
    }

    // ---------- 自动打卡状态 ----------
    fun autoState(itemId: Long) = autoDao.get(itemId)
    fun allAutoStates() = autoDao.all()
    fun saveAutoState(s: AutoState) = autoDao.upsert(s)
}
