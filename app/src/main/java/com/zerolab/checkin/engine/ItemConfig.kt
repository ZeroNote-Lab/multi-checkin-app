package com.zerolab.checkin.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 一个位置点 */
data class LocatePoint(var name: String = "", var lat: Double = 0.0, var lng: Double = 0.0, var radius: Int = 200)

/** 抵消机制配置 */
data class OffsetCfg(
    var enabled: Boolean = false,
    var mode: String = "A",       // A 累积 / B 阈值 / C 周期
    var nDays: Int = 3,
    var k: Int = 1,
    var autoConsume: Boolean = false,
    var anchorDate: String = ""   // 里程碑周期锚点：最近一次补签动作日（补签后从此重新起算连续 N 天）
)

/**
 * 打卡项规则配置（v6：方式为多开关集合）。
 */
class ItemConfig {
    val methods: MutableSet<String> = linkedSetOf("NORMAL")
    var dailyLimit: Int = 1                 // 每日次数，-1 不限
    var negative: Boolean = false           // 负打卡：状态反转（操作=失败，无操作=成功）
    var customNeg: Boolean = false          // 双时间点自定义负打卡
    var t1: String = "05:00"
    var t2: String = "15:00"
    var offsetBackfill: Boolean = false     // 负打卡补签

    // 各方式参数
    var photoFromCamera: Boolean = true
    var photoFromAlbum: Boolean = true
    var textMinWords: Int = 1
    var textNoRepeat: Boolean = false
    var locNegative: Boolean = false        // 位置负打卡（离开范围才算）
    val locPoints: MutableList<LocatePoint> = mutableListOf()
    var stepTarget: Int = 5000
    var timerMinutes: Int = 25
    var qrContent: String = ""
    var nfcTagId: String = ""
    var voiceMaxSeconds: Int = 10
    var autoForeground: Boolean = true

    // 打卡日期（v6.1.0）：DAILY=每天 / WEEKDAYS=每周固定几天 / DOUBLE_REST=双休 / BIGSMALL=大小周
    var scheduleMode: String = "DAILY"
    val weekDays: MutableSet<Int> = linkedSetOf()   // 1=周一 … 7=周日（WEEKDAYS 模式使用）
    var bigSmallStart: String = "BIG"               // BIG=创建当周为大周 / SMALL=创建当周为小周

    var offset: OffsetCfg = OffsetCfg()

    fun toJson(): String {
        val o = JSONObject()
        o.put("methods", JSONArray(methods.toList()))
        o.put("dailyLimit", dailyLimit)
        o.put("negative", negative)
        o.put("customNeg", customNeg)
        o.put("t1", t1); o.put("t2", t2)
        o.put("offsetBackfill", offsetBackfill)
        o.put("photoFromCamera", photoFromCamera)
        o.put("photoFromAlbum", photoFromAlbum)
        o.put("textMinWords", textMinWords)
        o.put("textNoRepeat", textNoRepeat)
        o.put("locNegative", locNegative)
        val pts = JSONArray()
        locPoints.forEach { p ->
            pts.put(JSONObject().put("name", p.name).put("lat", p.lat).put("lng", p.lng).put("radius", p.radius))
        }
        o.put("locPoints", pts)
        o.put("stepTarget", stepTarget)
        o.put("timerMinutes", timerMinutes)
        o.put("qrContent", qrContent.ifBlank { "uuid:" + UUID.randomUUID().toString() })
        o.put("nfcTagId", nfcTagId)
        o.put("voiceMaxSeconds", voiceMaxSeconds)
        o.put("autoForeground", autoForeground)
        o.put("scheduleMode", scheduleMode)
        o.put("weekDays", JSONArray(weekDays.toList()))
        o.put("bigSmallStart", bigSmallStart)
        o.put("offset", JSONObject()
            .put("enabled", offset.enabled)
            .put("mode", offset.mode)
            .put("nDays", offset.nDays)
            .put("k", offset.k)
            .put("autoConsume", offset.autoConsume)
            .put("anchorDate", offset.anchorDate))
        return o.toString(2)
    }

    companion object {
        fun parse(raw: String?): ItemConfig {
            val c = ItemConfig()
            if (raw.isNullOrBlank()) return c
            try {
                val o = JSONObject(raw)
                c.methods.clear()
                val arr = o.optJSONArray("methods")
                if (arr != null) for (i in 0 until arr.length()) c.methods.add(arr.getString(i))
                if (c.methods.isEmpty()) {
                    // 兼容旧单类型字段
                    o.optString("type", "NORMAL").takeIf { it.isNotBlank() }?.let { c.methods.add(it) }
                }
                c.dailyLimit = o.optInt("dailyLimit", 1)
                c.negative = o.optBoolean("negative", false)
                c.customNeg = o.optBoolean("customNeg", false)
                c.t1 = o.optString("t1", "05:00"); c.t2 = o.optString("t2", "15:00")
                c.offsetBackfill = o.optBoolean("offsetBackfill", false)
                c.photoFromCamera = o.optBoolean("photoFromCamera", true)
                c.photoFromAlbum = o.optBoolean("photoFromAlbum", true)
                c.textMinWords = o.optInt("textMinWords", 1)
                c.textNoRepeat = o.optBoolean("textNoRepeat", false)
                c.locNegative = o.optBoolean("locNegative", false)
                o.optJSONArray("locPoints")?.let { pts ->
                    for (i in 0 until pts.length()) {
                        val p = pts.getJSONObject(i)
                        c.locPoints.add(LocatePoint(
                            p.optString("name"), p.optDouble("lat", 0.0),
                            p.optDouble("lng", 0.0), p.optInt("radius", 200)))
                    }
                }
                c.stepTarget = o.optInt("stepTarget", 5000)
                c.timerMinutes = o.optInt("timerMinutes", 25)
                c.qrContent = o.optString("qrContent", "")
                c.nfcTagId = o.optString("nfcTagId", "")
                c.voiceMaxSeconds = o.optInt("voiceMaxSeconds", 10)
                c.autoForeground = o.optBoolean("autoForeground", true)
                c.scheduleMode = o.optString("scheduleMode", "DAILY")
                o.optJSONArray("weekDays")?.let { wd ->
                    c.weekDays.clear()
                    for (i in 0 until wd.length()) c.weekDays.add(wd.getInt(i))
                }
                c.bigSmallStart = o.optString("bigSmallStart", "BIG")
                o.optJSONObject("offset")?.let { off ->
                    c.offset = OffsetCfg(
                        off.optBoolean("enabled", false),
                        off.optString("mode", "A"),
                        off.optInt("nDays", 3),
                        off.optInt("k", 1),
                        off.optBoolean("autoConsume", false),
                        off.optString("anchorDate", ""))
                }
            } catch (_: Exception) { }
            return c
        }

        /** 由方式集合推导主类型字段 */
        fun mainType(methods: Collection<String>): String =
            if (methods.size == 1) methods.first() else if (methods.isEmpty()) "NORMAL" else "MULTI"
    }
}
