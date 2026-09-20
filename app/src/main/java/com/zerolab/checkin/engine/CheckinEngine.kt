package com.zerolab.checkin.engine

import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.data.entity.AutoState
import com.zerolab.checkin.data.entity.OffsetCredit
import com.zerolab.checkin.data.repo.CheckinRepository
import com.zerolab.checkin.util.DateUtils

/** 日历某天的展示状态 */
enum class DayState { SUCCESS, FAIL, UNCHECKED, FUTURE, OFFSET, SKIP, PARTIAL }

data class DayInfo(
    val date: String,
    val state: DayState,
    val count: Int,            // 当日记录条数
    val isAuto: Boolean,
    val records: List<CheckinRecord>,
    val finalToday: Boolean = false   // v1.1.7：当天已定论（固定时间段超时未打卡当天即红）
)

sealed class CheckinResult {
    data class Ok(val date: String, val status: String, val recordId: Long) : CheckinResult()
    data class Blocked(val reason: String) : CheckinResult()   // 次数已满 / 时段不允许
}

object CheckinEngine {

    // ---------- 配置快捷取值 ----------
    fun cfg(item: CheckinItem) = ItemConfig.parse(item.configJson)

    /** 该方式集合是否为负打卡语义（普通负打卡 或 双时间自定义负打卡） */
    fun isNegative(c: ItemConfig) = c.negative // v1.1.8：移除双时间自定义负打卡

    // ---------- 打卡日期判定（v6.1.0） ----------
    /** 某天是否属于该打卡项的需打卡日；false = 无需打卡（SKIP）。v1.2.0：随心记排期失效，恒为需打卡日；v1.3.0：心情日记同 */
    fun isScheduledDay(item: CheckinItem, date: String): Boolean {
        val c = cfg(item)
        if (c.journalMode || c.moodMode) return true
        return when (c.scheduleMode) {
            "WEEKDAYS" -> DateUtils.weekdayOf(date) in c.weekDays
            "DOUBLE_REST" -> DateUtils.weekdayOf(date) <= 5           // 周一~五需打卡，周六日休息
            "BIGSMALL" -> {
                val dow = DateUtils.weekdayOf(date)
                if (dow >= 6) {                                       // 周六：大周需打卡；周日：总是休息
                    dow == 6 && isBigWeek(item, date)
                } else true                                           // 周一~五总是需打卡
            }
            else -> true                                              // DAILY 每天
        }
    }

    /** 大小周：该日期所在周是否为「大周」（大周=单休，周六需打卡） */
    private fun isBigWeek(item: CheckinItem, date: String): Boolean {
        val c = cfg(item)
        val createWeek = DateUtils.weekIndex(DateUtils.dateOf(item.createdAt))
        val diff = DateUtils.weekIndex(date) - createWeek
        return if (c.bigSmallStart == "BIG") diff % 2L == 0L else diff % 2L != 0L
    }

    // ---------- 单日状态 ----------
    fun dayInfo(item: CheckinItem, date: String, records: List<CheckinRecord>): DayInfo {
        val c = cfg(item)
        val today = DateUtils.today()
        val neg = isNegative(c)
        val auto = records.any { it.isAuto == 1 }
        val offset = records.any { it.status == "OFFSET" }
        val created = DateUtils.dateOf(item.createdAt)
        val methods = c.methods.filter { it != Method.AUTO.key }
        return when {
            // 创建日之前：不属于本打卡项，按"未到"处理（不参与状态与连续天数）
            date < created -> DayInfo(date, DayState.FUTURE, 0, false, records)
            date > today -> DayInfo(date, DayState.FUTURE, records.size, auto, records)
            offset -> DayInfo(date, DayState.OFFSET, records.size, auto, records)
            // v1.2.0 随心记 / v1.3.0 心情日记：只记成功；无操作日无任何底色（含过去缺记），不染色
            (c.journalMode || c.moodMode) -> {
                val success = records.any { it.status == "SUCCESS" }
                DayInfo(date, if (success) DayState.SUCCESS else DayState.UNCHECKED, records.size, auto, records)
            }
            !isScheduledDay(item, date) -> DayInfo(date, DayState.SKIP, records.size, auto, records) // 无需打卡日
            records.isNotEmpty() -> {
                // v1.2.0 组合方式（多开关）：当天成功 = 完成方式数 >= comboRequired（0=全部），否则视为进行中
                val req = if (c.comboRequired in 1..methods.size) c.comboRequired else methods.size
                val comboDone = methods.count { m ->
                    records.any { r -> r.status == "SUCCESS" && r.extraJson?.contains(m) == true }
                }
                val comboAll = methods.size > 1 && comboDone >= req
                val success = records.any { it.status == "SUCCESS" }
                val paused = records.any { it.status == "PAUSED" }
                val st = when {
                    !neg && methods.size > 1 -> if (comboAll) DayState.SUCCESS else DayState.PARTIAL // v1.1.6：组合未全完成=部分完成（黄色）
                    // v1.2.0：时间打卡暂停未完成——今天=黄色部分完成；昨天（无 SUCCESS）=走下方 FAIL 红（次日缺卡）
                    !neg && paused && !success && date == today -> DayState.PARTIAL
                    neg -> DayState.FAIL            // 负打卡：有操作=破戒失败
                    success -> DayState.SUCCESS
                    else -> DayState.FAIL
                }
                DayInfo(date, st, records.size, auto, records)
            }
            // v1.1.7：固定时间段——已过窗口结束时间且今天仍未打卡 → 当天直接判定缺卡（不等次日）
            date == today && c.timeWindowEnabled && records.isEmpty() &&
                DateUtils.nowMinutes() > DateUtils.parseHHmm(c.twEnd) ->
                DayInfo(date, DayState.FAIL, 0, false, records, finalToday = true)
            date == today -> DayInfo(date, DayState.UNCHECKED, 0, false, records) // 今天进行中
            else -> {
                // 过去且无记录：正常=缺卡（红色显示）；负打卡=无操作成功
                DayInfo(date, if (neg) DayState.SUCCESS else DayState.FAIL, 0, false, records)
            }
        }
    }

    /** v1.1.6 固定时间段打卡：当前时刻是否在打卡窗口内（未启用恒为 true） */
    fun inTimeWindow(c: ItemConfig): Boolean {
        if (!c.timeWindowEnabled) return true
        val nowMin = DateUtils.nowMinutes()
        val a = DateUtils.parseHHmm(c.twStart); val b = DateUtils.parseHHmm(c.twEnd)
        return a >= 0 && b >= 0 && a < b && nowMin in a until b
    }

    /** 某天是否算"成功"（用于连续天数） */
    private fun dayIsSuccess(item: CheckinItem, date: String, repo: CheckinRepository): Boolean {
        if (date < DateUtils.dateOf(item.createdAt)) return false // 创建日之前不计入连续
        val recs = repo.recordsOfDay(item.id, date)
        val info = dayInfo(item, date, recs)
        if (info.state == DayState.SKIP) return true // 无需打卡日视为成功，不中断连续
        if (date == DateUtils.today() && recs.isEmpty()) return false // 今天未定论不计入
        // 补签（OFFSET）算进连续天数：补签把断的那天补上，连续不中断（v1.1.3）
        return info.state == DayState.SUCCESS || info.state == DayState.OFFSET
    }

    /** 连续成功天数 */
    fun streak(item: CheckinItem, repo: CheckinRepository): Int {        var d = DateUtils.today()
        // 今天未定论则从昨天起算
        if (!dayIsSuccess(item, d, repo)) d = DateUtils.addDays(d, -1)
        var n = 0
        // 上限保护，最多回溯 3660 天
        repeat(3660) {
            if (dayIsSuccess(item, d, repo)) { n++; d = DateUtils.addDays(d, -1) } else return n
        }
        return n
    }

    /** v1.2.0 随心记记录天数：有成功记录的去重天数（断签不归零、只增不减；PAUSED 暂停记录不计） */
    fun recordDays(item: CheckinItem, repo: CheckinRepository): Int =
        repo.allRecords(item.id).asSequence()
            .filter { it.status == "SUCCESS" }
            .map { it.checkinDate }
            .distinct()
            .count()

    /** 里程碑连续天数：从锚点（最近补签日）或创建日起算，用于抵消发放（补签后重新起算周期，防止补签白拿新机会） */
    private fun milestoneStreak(item: CheckinItem, repo: CheckinRepository): Int {
        val c = cfg(item)
        val start = c.offset.anchorDate.ifBlank { DateUtils.dateOf(item.createdAt) }
        var d = DateUtils.today()
        if (!dayIsSuccess(item, d, repo)) d = DateUtils.addDays(d, -1)
        var n = 0
        repeat(3660) {
            if (d < start) return n
            if (dayIsSuccess(item, d, repo)) { n++; d = DateUtils.addDays(d, -1) } else return n
        }
        return n
    }

    // ---------- 执行打卡 ----------
    fun perform(
        item: CheckinItem,
        repo: CheckinRepository,
        photoPath: String? = null,
        text: String? = null,
        voicePath: String? = null,
        lat: Double? = null,
        lng: Double? = null,
        extra: String? = null,
        isAuto: Boolean = false
    ): CheckinResult {
        val c = cfg(item)
        val now = System.currentTimeMillis()

        // v1.1.6 固定时间段打卡：仅窗口内可打卡成功（与负打卡/自动互斥，UI 层已保证）
        if (c.timeWindowEnabled && !isAuto && !inTimeWindow(c)) {
            val a = DateUtils.parseHHmm(c.twStart); val b = DateUtils.parseHHmm(c.twEnd)
            val reason = if (a >= 0 && DateUtils.nowMinutes() < a) "未到打卡时间（${c.twStart}–${c.twEnd}）"
                else "已过打卡时间（${c.twStart}–${c.twEnd}）"
            return CheckinResult.Blocked(reason)
        }

        // 归属日期与状态（v1.1.8：移除双时间自定义负打卡，统一按自然日归属）
        val date = DateUtils.dateOf(now)
        val status = if (isNegative(c) && !isAuto) "FAIL" else "SUCCESS"

        // 无需打卡日：不允许打卡（v1.2.0 随心记排期失效，恒允许；v1.3.0 心情日记同）
        if (!c.journalMode && !c.moodMode && !isScheduledDay(item, date)) return CheckinResult.Blocked("今日无需打卡")

        // 次数限制（正常模式，组合打卡按方式逐条记、不做条数拦截）；-1 不限；v1.2.0：随心记不限、PAUSED 暂停记录不计入已打次数；v1.3.0：心情日记不限
        val interactive = c.methods.filter { it != Method.AUTO.key }
        val already = repo.recordsOfDay(item.id, date).count { it.status != "PAUSED" }
        if (!isNegative(c) && c.dailyLimit > 0 && already >= c.dailyLimit && !isAuto && interactive.size <= 1 && !c.journalMode && !c.moodMode) {
            return CheckinResult.Blocked("今日已完成目标次数")
        }
        // 负打卡模式：同一归属日重复操作直接累加记录（破戒次数），不做上限拦截
        val rec = CheckinRecord(
            itemId = item.id, checkinDate = date, checkinTime = now, status = status,
            isAuto = if (isAuto) 1 else 0, photoPath = photoPath, textContent = text,
            voicePath = voicePath, latitude = lat, longitude = lng, extraJson = extra
        )
        val rid = repo.insertRecord(rec)

        // 正常模式成功后处理抵消发放
        if (status == "SUCCESS") grantOffsetAfterCheckin(item, repo)
        return CheckinResult.Ok(date, status, rid)
    }

    // ---------- 抵消机制发放 ----------
    private fun grantOffsetAfterCheckin(item: CheckinItem, repo: CheckinRepository) {
        val c = cfg(item)
        val off = c.offset
        if (!off.enabled) return
        if (c.journalMode || c.moodMode) return // v1.2.0 随心记 / v1.3.0 心情日记不参与抵消机制
        // 参数保护：nDays/k 必须为正，否则不发放（防导入/异常配置除零崩溃）
        if (off.nDays <= 0 || off.k <= 0) return
        val today = DateUtils.today()
        val ms = milestoneStreak(item, repo)
        when (off.mode) {
            "A" -> {
                // 每连续 nDays 天得 1，可累积：里程碑数 - 当前可用数（消耗过则按剩余补发）
                val milestones = ms / off.nDays
                val effective = repo.availableCredits(item.id)
                repeat((milestones - effective).coerceAtLeast(0)) {
                    repo.grantCredit(OffsetCredit(itemId = item.id, mode = "A", earnedDate = today))
                }
            }
            "B" -> {
                // 每达到 nDays 连续，把可用次数补到 k（不叠加）
                if (ms > 0 && ms % off.nDays == 0) {
                    val avail = repo.availableCredits(item.id)
                    repeat((off.k - avail).coerceAtLeast(0)) {
                        repo.grantCredit(OffsetCredit(itemId = item.id, mode = "B", earnedDate = today))
                    }
                }
            }
            "C" -> {
                val cycles = ms / off.nDays
                val need = cycles * off.k
                val effective = repo.availableCredits(item.id)
                repeat((need - effective).coerceAtLeast(0)) {
                    repo.grantCredit(OffsetCredit(itemId = item.id, mode = "C", earnedDate = today))
                }
            }
        }
    }

    /** 自动模式：漏签时自动/手动消耗一次抵消补签；返回是否补签 */
    fun offsetBackfill(item: CheckinItem, repo: CheckinRepository, date: String): Boolean {
        val c = cfg(item)
        if (c.journalMode || c.moodMode) return false // v1.2.0 随心记 / v1.3.0 心情日记无抵消机制
        if (!c.offset.enabled) return false
        if (repo.availableCredits(item.id) <= 0) return false // 无可用机会，不写孤立记录
        val now = System.currentTimeMillis()
        val rid = repo.insertRecord(
            CheckinRecord(itemId = item.id, checkinDate = date, checkinTime = now,
                status = "OFFSET", isAuto = 0)
        )
        val consumed = repo.consumeOne(item.id, date, rid)

        // v1.1.3：补签当天（今天）若无记录且为需打卡日，自动完成当天打卡（补签=处理昨日遗漏+完成今日）
        val today = DateUtils.today()
        if (consumed && today != date && isScheduledDay(item, today)
            && repo.recordsOfDay(item.id, today).isEmpty()) {
            repo.insertRecord(CheckinRecord(
                itemId = item.id, checkinDate = today, checkinTime = now,
                status = "SUCCESS", isAuto = 0))
        }

        // v1.1.3：里程碑周期从补签动作日重新起算——补签只消耗机会、不发放新机会，
        // 后续连续 N 天奖励从补签当天起重新累计（防止补签把连续顶过里程碑边界白拿补卡机会）。
        if (consumed && c.offset.anchorDate != today) {
            c.offset.anchorDate = today
            saveCfg(item, repo, c)
        }
        return consumed
    }


    /** 持久化 ItemConfig（anchorDate 等字段变更） */
    private fun saveCfg(item: CheckinItem, repo: CheckinRepository, c: ItemConfig) {
        repo.updateItem(item.copy(configJson = c.toJson()))
    }

    // ---------- 自动打卡（前台触发） ----------
    /** 返回本次新自动完成的 item 名称列表（用于提示） */
    fun tryAutoAll(repo: CheckinRepository): List<String> {
        val done = mutableListOf<String>()
        repo.getItems().filter { it.isActive == 1 }.forEach { item ->
            val c = cfg(item)
            if (Method.AUTO.key !in c.methods) return@forEach
            if (c.journalMode || c.moodMode) return@forEach // v1.2.0 随心记 / v1.3.0 心情日记不自动打卡
            val today = DateUtils.today()
            if (!isScheduledDay(item, today)) return@forEach // 无需打卡日不自动打卡
            val st = repo.autoState(item.id)
            if (st != null && st.lastAutoDate == today) return@forEach
            val already = repo.recordsOfDay(item.id, today)
            if (already.any { it.isAuto == 1 }) {
                repo.saveAutoState(AutoState(item.id, today, System.currentTimeMillis())); return@forEach
            }
            val r = perform(item, repo, isAuto = true)
            if (r is CheckinResult.Ok) {
                repo.saveAutoState(AutoState(item.id, today, System.currentTimeMillis()))
                done.add(item.name)
            }
        }
        return done
    }
}
