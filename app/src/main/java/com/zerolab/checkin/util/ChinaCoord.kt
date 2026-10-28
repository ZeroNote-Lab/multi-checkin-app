package com.zerolab.checkin.util

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84（GPS 原始坐标）与 GCJ-02（国测局坐标，高德 / 腾讯地图使用）互转。
 *
 * Android [android.location.Location] 给出的是 WGS-84；高德 Web 服务 API（regeo / place 等）
 * 要求传入 GCJ-02。两者直接混用会产生约 100–700 米的偏移（广州一带约 200–500 米），
 * 表现为「人在地标 A，地名却解析到附近的 B」。
 *
 * 算法为公开的通用实现：先按椭球参数做偏移量计算，再用迭代法反向逼近。
 * 中国境外不做偏移（返回原坐标）。
 */
object ChinaCoord {

    private const val A = 6378245.0                     // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594323       // 第一偏心率平方

    /** 是否在中国境外（境外不偏移） */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84 → GCJ-02；返回 [lat, lng] */
    fun wgs84ToGcj02(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * Math.PI)
        return doubleArrayOf(lat + dLat, lng + dLng)
    }

    /** GCJ-02 → WGS-84（迭代逼近，误差 < 1 米）；返回 [lat, lng] */
    fun gcj02ToWgs84(lat: Double, lng: Double): DoubleArray {
        if (outOfChina(lat, lng)) return doubleArrayOf(lat, lng)
        var wgsLat = lat
        var wgsLng = lng
        repeat(6) {
            val g = wgs84ToGcj02(wgsLat, wgsLng)
            val dLat = lat - g[0]
            val dLng = lng - g[1]
            wgsLat += dLat
            wgsLng += dLng
            if (abs(dLat) < 1e-9 && abs(dLng) < 1e-9) return doubleArrayOf(wgsLat, wgsLng)
        }
        return doubleArrayOf(wgsLat, wgsLng)
    }

    /** 两个坐标点之间的近似距离（米，球面） */
    fun distanceMeters(la1: Double, ln1: Double, la2: Double, ln2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(la2 - la1)
        val dLng = Math.toRadians(ln2 - ln1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
