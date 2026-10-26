package com.zerolab.checkin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.2：日期工具的行为回归（周序号 / 周几 / 月历网格 / 日期加减 / 时间解析） */
class DateUtilsCalendarTest {

    @Test
    fun weekdayOf_monday_is_1() {
        // 2026-10-26 是周一
        assertEquals(1, DateUtils.weekdayOf("2026-10-26"))
    }

    @Test
    fun weekdayOf_sunday_is_7() {
        // 2026-10-25 是周日
        assertEquals(7, DateUtils.weekdayOf("2026-10-25"))
    }

    @Test
    fun weekdayOf_crosses_month_boundary() {
        assertEquals(1, DateUtils.weekdayOf("2026-11-02"))
        assertEquals(7, DateUtils.weekdayOf("2026-11-01"))
    }

    @Test
    fun weekIndex_same_week_for_monday_and_sunday() {
        val monday = DateUtils.weekIndex("2026-10-19")
        val sunday = DateUtils.weekIndex("2026-10-25")
        assertEquals(monday, sunday)
    }

    @Test
    fun weekIndex_advances_by_one_per_week() {
        val w1 = DateUtils.weekIndex("2026-10-19")
        val w2 = DateUtils.weekIndex("2026-10-26")
        val w3 = DateUtils.weekIndex("2026-11-02")
        assertEquals(1L, w2 - w1)
        assertEquals(1L, w3 - w2)
    }

    @Test
    fun weekIndex_stable_across_year_boundary() {
        // 2026-12-28（周一）与 2027-01-03（周日）属于同一周
        assertEquals(DateUtils.weekIndex("2026-12-28"), DateUtils.weekIndex("2027-01-03"))
        assertEquals(1L, DateUtils.weekIndex("2027-01-04") - DateUtils.weekIndex("2026-12-28"))
    }

    @Test
    fun monthGrid_october_2026_alignment() {
        // 2026-10-01 是周四 → 周一为首列时前置 3 个空格
        val grid = DateUtils.monthGrid(2026, 9)
        assertEquals(0, grid.size % 7)
        assertEquals(3, grid.takeWhile { !it.second }.size)
        assertEquals("2026-10-01", grid[3].first)
        assertEquals(31, grid.count { it.second })
        assertEquals("2026-10-31", grid.last { it.second }.first)
    }

    @Test
    fun monthGrid_february_2027_starts_on_monday() {
        // 2027-02-01 是周一 → 无前置空格
        val grid = DateUtils.monthGrid(2027, 1)
        assertEquals(0, grid.takeWhile { !it.second }.size)
        assertEquals("2027-02-01", grid.first().first)
        assertEquals(28, grid.count { it.second })
    }

    @Test
    fun addDays_crosses_month_and_year() {
        assertEquals("2026-11-01", DateUtils.addDays("2026-10-31", 1))
        assertEquals("2026-10-31", DateUtils.addDays("2026-11-01", -1))
        assertEquals("2027-01-01", DateUtils.addDays("2026-12-31", 1))
        assertEquals("2026-12-31", DateUtils.addDays("2027-01-01", -1))
    }

    @Test
    fun addDays_handles_non_leap_february() {
        assertEquals("2026-02-28", DateUtils.addDays("2026-03-01", -1))
        assertEquals("2024-02-29", DateUtils.addDays("2024-03-01", -1))
    }

    @Test
    fun dayOfMonth_parses_two_digits() {
        assertEquals(1, DateUtils.dayOfMonth("2026-10-01"))
        assertEquals(26, DateUtils.dayOfMonth("2026-10-26"))
    }

    @Test
    fun parseHHmm_valid_and_invalid() {
        assertEquals(0, DateUtils.parseHHmm("00:00"))
        assertEquals(5 * 60, DateUtils.parseHHmm("05:00"))
        assertEquals(23 * 60 + 59, DateUtils.parseHHmm("23:59"))
        assertEquals(-1, DateUtils.parseHHmm(null))
        assertEquals(-1, DateUtils.parseHHmm(""))
        assertEquals(-1, DateUtils.parseHHmm("5"))
        assertEquals(-1, DateUtils.parseHHmm("05:00:00"))
        assertEquals(-1, DateUtils.parseHHmm("aa:bb"))
    }

    @Test
    fun monthTitle_formats_chinese_month() {
        assertEquals("2026年10月", DateUtils.monthTitle(2026, 9))
        assertEquals("2026年1月", DateUtils.monthTitle(2026, 0))
    }

    @Test
    fun daysSince_null_or_blank_is_max() {
        assertEquals(Int.MAX_VALUE, DateUtils.daysSince(null))
        assertEquals(Int.MAX_VALUE, DateUtils.daysSince(""))
        // 今天距今天 0 天
        assertEquals(0, DateUtils.daysSince(DateUtils.today()))
        // 不合法日期返回 MAX_VALUE
        assertEquals(Int.MAX_VALUE, DateUtils.daysSince("not-a-date"))
    }

    @Test
    fun daysSince_yesterday_is_one_or_more() {
        val yesterday = DateUtils.addDays(DateUtils.today(), -1)
        assertTrue(DateUtils.daysSince(yesterday) >= 1)
    }
}
