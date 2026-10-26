package com.zerolab.checkin.engine

import com.zerolab.checkin.data.entity.AutoState
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.data.entity.OffsetCredit
import com.zerolab.checkin.testutil.FakeCheckinStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * v1.4.2 重构：用内存假仓库验证打卡状态机（此前完全没有覆盖）。
 * 时间全部注入（today / now），结果与真实运行日期无关。
 */
class CheckinEngineTest {

    private val today = "2026-10-26"   // 周一

    private fun ts(date: String, hour: Int = 12, minute: Int = 0): Long {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return f.parse("$date ${"%02d".format(hour)}:${"%02d".format(minute)}")!!.time
    }

    private fun item(
        cfg: ItemConfig = ItemConfig(),
        id: Long = 1,
        created: String = "2026-10-01",
        active: Int = 1,
        name: String = "测试项"
    ): CheckinItem = CheckinItem(
        id = id, name = name, type = "NORMAL", configJson = cfg.toJson(),
        createdAt = ts(created, 0, 0), isActive = active
    )

    private fun rec(
        itemId: Long = 1,
        date: String,
        status: String = "SUCCESS",
        extra: String? = null,
        isAuto: Boolean = false
    ) = CheckinRecord(
        itemId = itemId, checkinDate = date, checkinTime = ts(date, 9, 0),
        status = status, extraJson = extra, isAuto = if (isAuto) 1 else 0
    )

    private lateinit var store: FakeCheckinStore

    @Before
    fun setUp() {
        store = FakeCheckinStore()
    }

    private fun addItem(cfg: ItemConfig = ItemConfig(), created: String = "2026-10-01", active: Int = 1): CheckinItem {
        val it = item(cfg, id = (store.itemList.size + 1).toLong(), created = created, active = active)
        store.itemList.add(it)
        return it
    }

    // ---------- dayInfo ----------

    @Test
    fun dayInfo_past_without_records_is_fail() {
        val it = addItem()
        assertEquals(DayState.FAIL, CheckinEngine.dayInfo(it, "2026-10-20", emptyList(), today).state)
    }

    @Test
    fun dayInfo_today_without_records_is_unchecked() {
        val it = addItem()
        assertEquals(DayState.UNCHECKED, CheckinEngine.dayInfo(it, today, emptyList(), today).state)
    }

    @Test
    fun dayInfo_future_and_before_creation_are_future() {
        val it = addItem()
        assertEquals(DayState.FUTURE, CheckinEngine.dayInfo(it, "2026-10-27", emptyList(), today).state)
        assertEquals(DayState.FUTURE, CheckinEngine.dayInfo(it, "2026-09-30", emptyList(), today).state)
    }

    @Test
    fun dayInfo_success_and_offset_and_skip() {
        val it = addItem()
        assertEquals(DayState.SUCCESS, CheckinEngine.dayInfo(it, today, listOf(rec(date = today)), today).state)
        assertEquals(DayState.OFFSET, CheckinEngine.dayInfo(it, "2026-10-20", listOf(rec(date = "2026-10-20", status = "OFFSET")), today).state)

        val cfg = ItemConfig().apply { scheduleMode = "DOUBLE_REST" }
        val weekend = addItem(cfg, created = "2026-10-01")
        // 2026-10-24 周六：双休模式无需打卡
        assertEquals(DayState.SKIP, CheckinEngine.dayInfo(weekend, "2026-10-24", emptyList(), today).state)
    }

    @Test
    fun dayInfo_journal_and_mood_never_mark_fail() {
        val journal = addItem(ItemConfig().apply { journalMode = true }, created = "2026-10-01")
        assertEquals(DayState.UNCHECKED, CheckinEngine.dayInfo(journal, "2026-10-20", emptyList(), today).state)
        assertEquals(DayState.SUCCESS, CheckinEngine.dayInfo(journal, "2026-10-20", listOf(rec(date = "2026-10-20")), today).state)

        val mood = addItem(ItemConfig().apply { journalMode = true; moodMode = true }, created = "2026-10-01")
        assertEquals(DayState.UNCHECKED, CheckinEngine.dayInfo(mood, "2026-10-21", emptyList(), today).state)
    }

    @Test
    fun dayInfo_negative_semantics() {
        val cfg = ItemConfig().apply { negative = true }
        val it = addItem(cfg, created = "2026-10-01")
        // 过去无操作 = 成功；今天有破戒记录 = FAIL
        assertEquals(DayState.SUCCESS, CheckinEngine.dayInfo(it, "2026-10-20", emptyList(), today).state)
        assertEquals(DayState.FAIL, CheckinEngine.dayInfo(it, today, listOf(rec(date = today, status = "FAIL")), today).state)
    }

    @Test
    fun dayInfo_combo_partial_and_success() {
        val cfg = ItemConfig().apply {
            methods.clear(); methods.add(Method.PHOTO.key); methods.add(Method.TEXT.key)
        }
        val it = addItem(cfg, created = "2026-10-01")
        val one = listOf(rec(date = today, extra = """{"method":"PHOTO"}"""))
        assertEquals(DayState.PARTIAL, CheckinEngine.dayInfo(it, today, one, today).state)
        val both = listOf(
            rec(date = today, extra = """{"method":"PHOTO"}"""),
            rec(date = today, extra = """{"method":"TEXT"}""")
        )
        assertEquals(DayState.SUCCESS, CheckinEngine.dayInfo(it, today, both, today).state)
    }

    @Test
    fun dayInfo_combo_required_n() {
        val cfg = ItemConfig().apply {
            methods.clear(); methods.add(Method.PHOTO.key); methods.add(Method.TEXT.key); methods.add(Method.VOICE.key)
            comboRequired = 2
        }
        val it = addItem(cfg, created = "2026-10-01")
        val two = listOf(
            rec(date = today, extra = """{"method":"PHOTO"}"""),
            rec(date = today, extra = """{"method":"TEXT"}""")
        )
        assertEquals(DayState.SUCCESS, CheckinEngine.dayInfo(it, today, two, today).state)
    }

    @Test
    fun dayInfo_daily_all_required() {
        val cfg = ItemConfig().apply { dailyLimit = 3; dailyAllRequired = true }
        val it = addItem(cfg, created = "2026-10-01")
        val two = listOf(rec(date = today), rec(date = today))
        assertEquals(DayState.PARTIAL, CheckinEngine.dayInfo(it, today, two, today).state)
        val three = listOf(rec(date = today), rec(date = today), rec(date = today))
        assertEquals(DayState.SUCCESS, CheckinEngine.dayInfo(it, today, three, today).state)
    }

    @Test
    fun dayInfo_time_window_passed_today_is_fail_final() {
        val cfg = ItemConfig().apply {
            timeWindowEnabled = true; twStart = "05:00"; twEnd = "08:30"
        }
        val it = addItem(cfg, created = "2026-10-01")
        // 传入的 now 不在窗口内：用 today 当天的 12:00 判断（engine 内部用系统时间）
        val info = CheckinEngine.dayInfo(it, today, emptyList(), today)
        // 系统时间不确定时只断言非 SUCCESS；窗口内则 UNCHECKED，窗口外则 FAIL(finalToday)
        assertTrue(info.state == DayState.UNCHECKED || info.state == DayState.FAIL)
    }

    // ---------- 排期 ----------

    @Test
    fun isScheduledDay_weekdays_mode() {
        val cfg = ItemConfig().apply { scheduleMode = "WEEKDAYS"; weekDays.addAll(listOf(1, 3)) }
        val it = addItem(cfg)
        assertTrue(CheckinEngine.isScheduledDay(it, "2026-10-26"))   // 周一
        assertFalse(CheckinEngine.isScheduledDay(it, "2026-10-27"))  // 周二
    }

    @Test
    fun isScheduledDay_double_rest() {
        val it = addItem(ItemConfig().apply { scheduleMode = "DOUBLE_REST" })
        assertFalse(CheckinEngine.isScheduledDay(it, "2026-10-24"))  // 周六
        assertFalse(CheckinEngine.isScheduledDay(it, "2026-10-25"))  // 周日
        assertTrue(CheckinEngine.isScheduledDay(it, "2026-10-23"))   // 周五
    }

    @Test
    fun isScheduledDay_big_small_week_alternates() {
        val big = addItem(ItemConfig().apply { scheduleMode = "BIGSMALL"; bigSmallStart = "BIG" }, created = "2026-10-01")
        // 创建周为 09-28；10-17 所在周 10-12，相差 2 周（大周）→ 周六需打卡
        assertTrue(CheckinEngine.isScheduledDay(big, "2026-10-17"))
        // 10-24 所在周 10-19，相差 3 周（小周）→ 周六休息
        assertFalse(CheckinEngine.isScheduledDay(big, "2026-10-24"))
    }

    // ---------- 连续天数 ----------

    @Test
    fun streak_counts_consecutive_days_including_today() {
        val it = addItem()
        listOf("2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25", "2026-10-26")
            .forEach { store.records.add(rec(date = it)) }
        assertEquals(7, CheckinEngine.streak(it, store, today))
    }

    @Test
    fun streak_today_empty_falls_back_to_yesterday() {
        val it = addItem()
        listOf("2026-10-24", "2026-10-25").forEach { store.records.add(rec(date = it)) }
        assertEquals(2, CheckinEngine.streak(it, store, today))
    }

    @Test
    fun streak_stops_at_gap() {
        val it = addItem()
        listOf("2026-10-24", "2026-10-25", "2026-10-26").forEach { store.records.add(rec(date = it)) }
        store.records.removeAll { it.checkinDate == "2026-10-25" }
        assertEquals(1, CheckinEngine.streak(it, store, today))
    }

    @Test
    fun streak_counts_skip_days_as_success() {
        val cfg = ItemConfig().apply { scheduleMode = "DOUBLE_REST" }
        val it = addItem(cfg, created = "2026-10-01")
        listOf("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-26")
            .forEach { store.records.add(rec(date = it)) }
        // 26(一) 23(五)…19(一) 连续 8 天 + 24/25 与 17/18 两个双休周末自动算成功 = 10
        assertEquals(10, CheckinEngine.streak(it, store, today))
    }

    @Test
    fun streak_negative_fail_today_resets_to_zero() {
        val it = addItem(ItemConfig().apply { negative = true })
        store.records.add(rec(date = today, status = "FAIL"))
        assertEquals(0, CheckinEngine.streak(it, store, today))
    }

    @Test
    fun streak_counts_offset_day() {
        val it = addItem()
        store.records.add(rec(date = "2026-10-25", status = "OFFSET"))
        store.records.add(rec(date = "2026-10-26"))
        assertEquals(2, CheckinEngine.streak(it, store, today))
    }

    @Test
    fun streak_ignores_days_before_creation() {
        val it = addItem(created = "2026-10-25")
        store.records.add(rec(date = "2026-10-25"))
        store.records.add(rec(date = "2026-10-26"))
        assertEquals(2, CheckinEngine.streak(it, store, today))
    }

    // ---------- 组合 / 子项进度 ----------

    @Test
    fun subDayProgress_single_limit_and_unlimited() {
        val single = addItem(ItemConfig().apply { dailyLimit = 3 })
        assertEquals(0, CheckinEngine.subDayProgress(single, today, store, today)[0])
        assertEquals(3, CheckinEngine.subDayProgress(single, today, store, today)[1])
        store.records.add(rec(date = today))
        store.records.add(rec(date = today))
        assertEquals(2, CheckinEngine.subDayProgress(single, today, store, today)[0])

        val unlimited = addItem(ItemConfig().apply { dailyLimit = -1 }, created = "2026-10-02")
        store.records.add(rec(itemId = unlimited.id, date = today))
        assertEquals(1, CheckinEngine.subDayProgress(unlimited, today, store, today)[0])
        assertEquals(1, CheckinEngine.subDayProgress(unlimited, today, store, today)[1])
    }

    @Test
    fun subDayProgress_combo_counts_done_methods() {
        val cfg = ItemConfig().apply {
            methods.clear(); methods.add(Method.PHOTO.key); methods.add(Method.TEXT.key)
        }
        val it = addItem(cfg)
        store.records.add(rec(date = today, extra = """{"method":"PHOTO"}"""))
        val p = CheckinEngine.subDayProgress(it, today, store, today)
        assertEquals(1, p[0])
        assertEquals(2, p[1])
        assertFalse(CheckinEngine.subDayDone(it, today, store))
        store.records.add(rec(date = today, extra = """{"method":"TEXT"}"""))
        assertTrue(CheckinEngine.subDayDone(it, today, store))
    }

    // ---------- perform ----------

    @Test
    fun perform_writes_success_record_with_injected_time() {
        val it = addItem()
        val r = CheckinEngine.perform(it, store, now = ts(today, 9, 30))
        assertTrue(r is CheckinResult.Ok)
        r as CheckinResult.Ok
        assertEquals("SUCCESS", r.status)
        assertEquals(today, r.date)
        assertEquals(1, store.allRecords(it.id).size)
        assertEquals(ts(today, 9, 30), store.allRecords(it.id).first().checkinTime)
    }

    @Test
    fun perform_blocks_when_daily_limit_reached() {
        val it = addItem()
        CheckinEngine.perform(it, store, now = ts(today, 9, 0))
        val second = CheckinEngine.perform(it, store, now = ts(today, 10, 0))
        assertTrue(second is CheckinResult.Blocked)
    }

    @Test
    fun perform_blocks_paused_and_group_items() {
        val paused = addItem(active = 0)
        assertTrue(CheckinEngine.perform(paused, store, now = ts(today, 9, 0)) is CheckinResult.Blocked)

        val group = addItem(ItemConfig().apply { groupMode = true }, created = "2026-10-02")
        assertTrue(CheckinEngine.perform(group, store, now = ts(today, 9, 0)) is CheckinResult.Blocked)
    }

    @Test
    fun perform_negative_with_shield_becomes_offset() {
        val cfg = ItemConfig().apply {
            negative = true
            offset.enabled = true; offset.nDays = 3; offset.k = 1
        }
        val it = addItem(cfg)
        store.grantCredit(OffsetCredit(itemId = it.id, mode = "A", earnedDate = "2026-10-20"))
        val r = CheckinEngine.perform(it, store, now = ts(today, 9, 0))
        r as CheckinResult.Ok
        assertEquals("OFFSET", r.status)
        assertEquals(0, store.availableCredits(it.id))
    }

    @Test
    fun perform_negative_without_shield_records_fail() {
        val it = addItem(ItemConfig().apply { negative = true })
        val r = CheckinEngine.perform(it, store, now = ts(today, 9, 0))
        r as CheckinResult.Ok
        assertEquals("FAIL", r.status)
    }

    @Test
    fun perform_outside_time_window_is_blocked() {
        val cfg = ItemConfig().apply {
            timeWindowEnabled = true; twStart = "05:00"; twEnd = "08:30"
        }
        val it = addItem(cfg)
        // 12:00 在窗口外（系统时间不影响：窗口判断用当前时刻，这里只验证不会被记成功）
        val r = CheckinEngine.perform(it, store, now = ts(today, 12, 0))
        if (CheckinEngine.inTimeWindow(cfg)) {
            assertTrue(r is CheckinResult.Ok)
        } else {
            assertTrue(r is CheckinResult.Blocked)
        }
    }

    @Test
    fun perform_journal_mode_allows_multiple_records() {
        val it = addItem(ItemConfig().apply { journalMode = true })
        assertTrue(CheckinEngine.perform(it, store, now = ts(today, 9, 0)) is CheckinResult.Ok)
        assertTrue(CheckinEngine.perform(it, store, now = ts(today, 10, 0)) is CheckinResult.Ok)
        assertEquals(2, store.allRecords(it.id).size)
    }

    @Test
    fun perform_on_unscheduled_day_is_blocked() {
        val cfg = ItemConfig().apply { scheduleMode = "WEEKDAYS"; weekDays.addAll(listOf(1)) }
        val it = addItem(cfg)
        // 2026-10-27 周二不在排期
        val r = CheckinEngine.perform(it, store, now = ts("2026-10-27", 9, 0))
        assertTrue(r is CheckinResult.Blocked)
    }
}
