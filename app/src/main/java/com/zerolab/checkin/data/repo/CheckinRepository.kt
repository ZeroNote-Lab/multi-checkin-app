package com.zerolab.checkin.data.repo

import com.zerolab.checkin.data.AppDatabase
import com.zerolab.checkin.data.entity.*
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.PauseRange
import com.zerolab.checkin.util.DateUtils

class CheckinRepository(private val db: AppDatabase) : CheckinStore {

    private val itemDao = db.itemDao()
    private val quickDao = db.quickDao()
    private val recordDao = db.recordDao()
    private val creditDao = db.creditDao()
    private val autoDao = db.autoDao()

    // ---------- 打卡项 ----------
    override fun getItems(): List<CheckinItem> = itemDao.getAllSorted()
    override fun getItem(id: Long): CheckinItem? = itemDao.getById(id)
    fun itemCount(): Int = itemDao.count()

    override fun insertItem(item: CheckinItem): Long {
        val id = itemDao.insert(item)
        ensureQuickAfterCreate(id)
        return id
    }

    override fun updateItem(item: CheckinItem) = itemDao.update(item.copy(updatedAt = System.currentTimeMillis()))

    fun deleteItem(id: Long) {
        // v1.3.27：删除打卡组时级联删除组内全部子项（含记录），避免子项悬挂、被快捷递补"释放"
        itemDao.getById(id)?.let { item ->
            val cfg = try { com.zerolab.checkin.engine.ItemConfig.parse(item.configJson) } catch (_: Exception) { null }
            if (cfg?.groupMode == true) {
                cfg.groupMembers.forEach { mid ->
                    recordDao.deleteByItem(mid)
                    itemDao.deleteById(mid)
                }
            }
        }
        val quick = quickDao.get()
        if (quick?.itemId == id) {
            // v1.3.24：删除快捷项后，按列表序递补第一个激活项（打卡组与普通策略一致）；无则快捷置空。
            quickDao.upsert(QuickConfig(1, pickNextQuick(id)))
        }
        recordDao.deleteByItem(id)
        itemDao.deleteById(id)
    }

    /** v1.3.28：选出下一个可快捷项——激活项（含打卡组外壳），按列表序第一个；排除组内子项（子项不出现在打卡选择列表，递补到子项会导致列表无星标）；无则 null */
    private fun pickNextQuick(excludeId: Long): Long? {
        return itemDao.getAllSorted().firstOrNull { it.id != excludeId && it.isActive == 1 && it.groupTag == null }?.id
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
        val c = ItemConfig.parse(it.configJson)
        val eff = DateUtils.belongDate(System.currentTimeMillis(), c.dayCutoff)
        if (!active && it.isActive == 1) {
            // v1.4.2：记下停用区间（停用期间不记缺卡）
            if (c.pausedRanges.none { r -> r.to.isBlank() }) {
                c.pausedRanges.add(PauseRange(eff, ""))
            }
        } else if (active && it.isActive == 0) {
            // v1.4.2：恢复时闭合停用区间（停用到昨天为止）
            val open = c.pausedRanges.lastOrNull { r -> r.to.isBlank() }
            if (open != null) {
                open.to = DateUtils.addDays(eff, -1)
                if (open.to < open.from) c.pausedRanges.remove(open)
            } else if (c.pausedRanges.isEmpty()) {
                // 兼容旧数据：旧版停用项没有区间记录，按「最后一条记录之后」补一段已结束区间
                val lastRec = recordDao.lastDate(id)
                val from = if (lastRec != null) DateUtils.addDays(lastRec, 1) else DateUtils.dateOf(it.createdAt)
                val to = DateUtils.addDays(eff, -1)
                if (from <= to) c.pausedRanges.add(PauseRange(from, to))
            }
        }
        itemDao.update(it.copy(
            isActive = if (active) 1 else 0,
            configJson = c.toJson()
        ))
        if (!active) {
            // v1.4.2：暂停的快捷项自动把快捷让给下一个启用项（排除组内子项），没有则清空
            if (quickDao.get()?.itemId == id) {
                quickDao.upsert(QuickConfig(1, pickNextQuick(id)))
            }
        } else if (quickDao.get()?.itemId == null) {
            // 恢复时若当前没有快捷项（全部暂停过），把恢复项设为快捷
            quickDao.upsert(QuickConfig(1, id))
        }
    }

    /**
     * v1.4.2：启动时补齐旧停用项的停用区间。
     * 旧版本暂停只改 isActive，没有记录暂停日期；这里按「最后一条记录之后」补一段至今的区间，
     * 避免升级后历史日期继续显示成一整片缺卡。已有区间的项不处理。
     */
    fun migrateLegacyPauseRanges() {
        itemDao.getAllSorted().filter { it.isActive == 0 }.forEach { item ->
            val c = ItemConfig.parse(item.configJson)
            if (c.pausedRanges.isNotEmpty()) return@forEach
            val lastRec = recordDao.lastDate(item.id)
            val from = if (lastRec != null) DateUtils.addDays(lastRec, 1) else DateUtils.dateOf(item.createdAt)
            c.pausedRanges.add(PauseRange(from, ""))
            itemDao.update(item.copy(configJson = c.toJson()))
        }
    }

    // ---------- 快捷打卡 ----------
    fun getQuickItem(): CheckinItem? {
        val id = quickDao.get()?.itemId ?: return null
        return itemDao.getById(id)
    }
    override fun getQuickId(): Long? = quickDao.get()?.itemId

    override fun setQuick(id: Long?) {
        // v1.3.24：打卡组与普通项一致，均可设为快捷打卡
        quickDao.upsert(QuickConfig(1, id))
    }

    private fun ensureQuickAfterCreate(newId: Long) {
        val cfg = quickDao.get()
        if (cfg == null || cfg.itemId == null) {
            // v1.3.24：空库首个项（含打卡组）自动设为快捷打卡
            quickDao.upsert(QuickConfig(1, newId))
        }
    }

    // ---------- 打卡记录 ----------
    fun recordsOfMonth(itemId: Long, startDate: String, endDate: String) =
        recordDao.between(itemId, startDate, endDate)
    override fun recordsOfDay(itemId: Long, date: String) =
        recordDao.between(itemId, date, date)
    override fun allRecords(itemId: Long) = recordDao.allOf(itemId)
    override fun insertRecord(r: CheckinRecord): Long = recordDao.insert(r)

    // v1.3.8：删除单条记录（管理员记录操作）
    fun deleteRecord(id: Long) = recordDao.deleteById(id)
    fun updateRecord(r: CheckinRecord) = recordDao.update(r)

    // ---------- 抵消 ----------
    override fun creditsOf(itemId: Long) = creditDao.ofItem(itemId)
    override fun availableCredits(itemId: Long): Int = creditDao.availableCount(itemId)
    override fun grantCredit(c: OffsetCredit): Long = creditDao.insert(c)
    /** v1.4.2 管理员：手动发放 count 个盾牌 */
    fun grantCredits(itemId: Long, count: Int, date: String) {
        repeat(count.coerceAtLeast(0)) {
            creditDao.insert(OffsetCredit(itemId = itemId, mode = "M", earnedDate = date))
        }
    }
    /** v1.4.2 管理员：撤销 count 个未使用盾牌，返回实际撤销数 */
    fun revokeCredits(itemId: Long, count: Int): Int {
        val list = creditDao.unusedOfItem(itemId, count.coerceAtLeast(0))
        list.forEach { creditDao.deleteById(it.id) }
        return list.size
    }
    override fun consumeOne(itemId: Long, date: String, recordId: Long?): Boolean {
        val c = creditDao.firstAvailable(itemId) ?: return false
        creditDao.update(c.copy(isUsed = 1, usedDate = date, usedRecordId = recordId))
        return true
    }

    // ---------- 自动打卡状态 ----------
    override fun autoState(itemId: Long) = autoDao.get(itemId)
    override fun saveAutoState(s: AutoState) = autoDao.upsert(s)
}
