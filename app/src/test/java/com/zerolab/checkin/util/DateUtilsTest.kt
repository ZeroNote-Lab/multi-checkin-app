package com.zerolab.checkin.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** v1.3.12 时间分割归属逻辑确定性验证（JVM 单测） */
class DateUtilsTest {

    private fun ts(y: Int, m: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d, h, mi, 0)
        return c.timeInMillis
    }

    // 分割点 03:00（180 分钟）：凌晨 00:00~02:59 归属前一天；03:00 起归属当天
    @Test
    fun cutoff_before_returns_prev_day() {
        assertEquals("2026-10-04", DateUtils.belongDate(ts(2026, 10, 5, 2, 59), 180))
    }

    @Test
    fun cutoff_midnight_boundary() {
        assertEquals("2026-10-04", DateUtils.belongDate(ts(2026, 10, 5, 0, 0), 180))
    }

    @Test
    fun cutoff_equal_returns_same_day() {
        assertEquals("2026-10-05", DateUtils.belongDate(ts(2026, 10, 5, 3, 0), 180))
    }

    @Test
    fun cutoff_after_returns_same_day() {
        assertEquals("2026-10-05", DateUtils.belongDate(ts(2026, 10, 5, 3, 1), 180))
    }

    @Test
    fun cutoff_late_night_returns_same_day() {
        assertEquals("2026-10-05", DateUtils.belongDate(ts(2026, 10, 5, 23, 59), 180))
    }

    // 未开启（-1）→ 自然日，凌晨也归属当天
    @Test
    fun cutoff_disabled_natural_day() {
        assertEquals("2026-10-05", DateUtils.belongDate(ts(2026, 10, 5, 2, 0), -1))
    }

    // 极端分割点 00:59（59 分钟）与 23:30（1410 分钟）边界
    @Test
    fun cutoff_59_min_boundary() {
        assertEquals("2026-10-04", DateUtils.belongDate(ts(2026, 10, 5, 0, 58), 59))
        assertEquals("2026-10-05", DateUtils.belongDate(ts(2026, 10, 5, 0, 59), 59))
    }

    @Test
    fun cutoff_large_boundary() {
        assertEquals("2026-10-04", DateUtils.belongDate(ts(2026, 10, 5, 23, 29), 1410))
        assertEquals("2026-10-05", DateUtils.belongDate(ts(2026, 10, 5, 23, 30), 1410))
    }
}
