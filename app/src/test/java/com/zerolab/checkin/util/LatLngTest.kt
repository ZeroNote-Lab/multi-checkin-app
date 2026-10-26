package com.zerolab.checkin.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.4.2：经纬度度分秒格式化回归（位置打卡的标准点展示依赖它） */
class LatLngTest {

    @Test
    fun formats_zero_minutes_and_seconds() {
        assertEquals("23°0'0''N 100°0'0''E", formatLatLng(23.0, 100.0))
    }

    @Test
    fun formats_negative_coordinates_with_south_west() {
        assertEquals("33°30'0''S 70°15'0''W", formatLatLng(-33.5, -70.25))
    }

    @Test
    fun formats_seconds_with_one_decimal() {
        // 12.3456° → 12°20'44.2''
        assertEquals("12°20'44.2''N 0°0'0''E", formatLatLng(12.3456, 0.0))
    }

    @Test
    fun carries_seconds_to_next_degree() {
        // 12.99999° 秒数进位：59.99'' → 0''，分钟进位到 60 → 度 +1
        assertEquals("13°0'0''N 0°0'0''E", formatLatLng(12.99999, 0.0))
    }

    @Test
    fun formats_longitude_with_degree_only() {
        assertEquals("0°0'0''N 123°9'0''W", formatLatLng(0.0, -123.15))
    }
}
