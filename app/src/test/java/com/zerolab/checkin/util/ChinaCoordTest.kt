package com.zerolab.checkin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WGS-84 ↔ GCJ-02 坐标转换（高德 API 必须用 GCJ-02，否则会出现百米级偏移） */
class ChinaCoordTest {

    @Test
    fun outsideChina_returnsInput() {
        // 东京（境外不偏移）
        val lat = 35.681236
        val lng = 139.767125
        val g = ChinaCoord.wgs84ToGcj02(lat, lng)
        assertEquals(lat, g[0], 1e-9)
        assertEquals(lng, g[1], 1e-9)
        val w = ChinaCoord.gcj02ToWgs84(lat, lng)
        assertEquals(lat, w[0], 1e-9)
        assertEquals(lng, w[1], 1e-9)
    }

    @Test
    fun insideChina_offsetIsHundredMeters() {
        // 广州大剧院附近：WGS-84 → GCJ-02 应有百米级偏移
        val lat = 23.1047
        val lng = 113.3245
        val g = ChinaCoord.wgs84ToGcj02(lat, lng)
        val d = ChinaCoord.distanceMeters(lat, lng, g[0], g[1])
        assertTrue("偏移应大于 100 米，实际 $d", d > 100)
        assertTrue("偏移应小于 1000 米，实际 $d", d < 1000)
    }

    @Test
    fun roundTrip_errorUnderOneMeter() {
        val lat = 23.1047
        val lng = 113.3245
        val g = ChinaCoord.wgs84ToGcj02(lat, lng)
        val w = ChinaCoord.gcj02ToWgs84(g[0], g[1])
        val d = ChinaCoord.distanceMeters(lat, lng, w[0], w[1])
        assertTrue("往返误差应小于 1 米，实际 $d", d < 1.0)
    }

    @Test
    fun distance_oneHundredthDegreeLatitudeAbout1_1km() {
        val d = ChinaCoord.distanceMeters(23.0, 113.0, 23.01, 113.0)
        assertTrue("实际 $d", d in 1100.0..1120.0)
    }
}
