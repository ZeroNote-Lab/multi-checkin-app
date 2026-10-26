package com.zerolab.checkin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.2：实时定位结果的可读来源描述（位置打卡弹窗展示）。
 * 只测纯函数，不触碰 Android 定位 API。
 */
class FreshLocationTest {

    @Test
    fun gps_with_good_accuracy_and_just_now() {
        val fix = FreshLocation.Fix(23.0, 113.0, 12f, "gps", 1_000)
        assertEquals("GPS · 精度 ±12 米 · 刚刚", fix.sourceText())
    }

    @Test
    fun network_with_seconds_age() {
        val fix = FreshLocation.Fix(23.0, 113.0, 80f, "network", 25_000)
        assertEquals("网络 · 精度 ±80 米 · 25 秒前", fix.sourceText())
    }

    @Test
    fun unknown_provider_falls_back_to_provider_name() {
        val fix = FreshLocation.Fix(23.0, 113.0, 30f, "passive", 120_000)
        assertEquals("passive · 精度 ±30 米", fix.sourceText())
    }

    @Test
    fun blank_provider_and_unknown_accuracy_are_omitted() {
        val fix = FreshLocation.Fix(23.0, 113.0, Float.MAX_VALUE, "", 120_000)
        assertEquals("定位", fix.sourceText())
    }

    @Test
    fun accuracy_is_rounded_to_integer_meters() {
        val fix = FreshLocation.Fix(23.0, 113.0, 12.7f, "gps", 2_000)
        assertTrue(fix.sourceText().contains("精度 ±12 米"))
    }
}
