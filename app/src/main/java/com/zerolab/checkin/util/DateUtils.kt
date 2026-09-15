package com.zerolab.checkin.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtils {
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val minFmt get() = SimpleDateFormat("HH:mm", Locale.US)
    private val fileFmt get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val titleFmt get() = SimpleDateFormat("yyyy年M月", Locale.US)

    fun today(): String = dayFmt.format(Date())

    fun dateOf(ts: Long): String = dayFmt.format(Date(ts))

    fun fileNameTs(): String = fileFmt.format(Date())

    fun monthTitle(year: Int, month0: Int): String {
        val c = Calendar.getInstance()
        c.clear(); c.set(year, month0, 1)
        return titleFmt.format(c.time)
    }

    fun parseHHmm(s: String?): Int {
        if (s.isNullOrBlank()) return -1
        val p = s.split(":")
        if (p.size != 2) return -1
        val h = p[0].toIntOrNull() ?: return -1
        val m = p[1].toIntOrNull() ?: return -1
        return h * 60 + m
    }

    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** 双时间点自定义负打卡的一个归属结果 */
    data class CustomSlot(val date: String, val status: String) // status: SUCCESS / FAIL

    /**
     * 双时间点自定义负打卡归属（v6.0.1 用户新定义）。
     * 设两个分界 t1、t2（如 05:00 / 13:00）：
     *  - [00:00, t1) 操作 → 归属昨天，记为「未打卡」(FAIL)
     *  - [t1, t2)    操作 → 归属今天，记为「打卡成功」(SUCCESS)
     *  - [t2, 24:00) 操作 → 归属今天，记为「未打卡」(FAIL)
     */
    // v1.1.8：双时间自定义负打卡已移除，customSlot 逻辑不再使用（保留注释备查）
    // fun customSlot(nowTs: Long, t1: String, t2: String): CustomSlot {
    //     val a = parseHHmm(t1); val b = parseHHmm(t2)
    //     val now = nowMinutesOf(nowTs)
    //     val cal = Calendar.getInstance()
    //     cal.timeInMillis = nowTs
    //     return when {
    //         a >= 0 && b >= 0 && now in a until b -> CustomSlot(dayFmt.format(cal.time), "SUCCESS")
    //         a >= 0 && now < a -> {
    //             cal.add(Calendar.DAY_OF_YEAR, -1)
    //             CustomSlot(dayFmt.format(cal.time), "FAIL")
    //         }
    //         else -> CustomSlot(dayFmt.format(cal.time), "FAIL")
    //     }
    // }

    private fun nowMinutesOf(ts: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun addDays(date: String, delta: Int): String {
        val c = Calendar.getInstance()
        c.time = dayFmt.parse(date)!!
        c.add(Calendar.DAY_OF_YEAR, delta)
        return dayFmt.format(c.time)
    }

    /** 生成某年某月的网格：前置补位 + 每天日期，返回 (日期字符串, 是否本月) 列表，周一为首列 */
    fun monthGrid(year: Int, month0: Int): List<Pair<String, Boolean>> {
        val cal = Calendar.getInstance()
        cal.clear(); cal.set(year, month0, 1)
        val firstWeekday = cal.get(Calendar.DAY_OF_WEEK) // 周日=1
        val leadBlanks = (firstWeekday + 5) % 7         // 转成周一=0
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val result = mutableListOf<Pair<String, Boolean>>()
        val prev = Calendar.getInstance().apply { clear(); set(year, month0, 1); add(Calendar.DAY_OF_MONTH, -leadBlanks) }
        repeat(leadBlanks) {
            result.add(dayFmt.format(prev.time) to false)
            prev.add(Calendar.DAY_OF_MONTH, 1)
        }
        for (d in 1..daysInMonth) {
            cal.clear(); cal.set(year, month0, d)
            result.add(dayFmt.format(cal.time) to true)
        }
        while (result.size % 7 != 0) {
            cal.clear(); cal.set(year, month0, daysInMonth)
            cal.add(Calendar.DAY_OF_MONTH, (result.size - leadBlanks - daysInMonth) + 1)
            result.add(dayFmt.format(cal.time) to false)
        }
        return result
    }

    fun dayOfMonth(date: String): Int = date.substring(8, 10).toInt()

    /** 星期几：1=周一 … 7=周日 */
    fun weekdayOf(date: String): Int {
        val c = Calendar.getInstance()
        c.time = dayFmt.parse(date)!!
        val dow = c.get(Calendar.DAY_OF_WEEK) // 1=周日
        return (dow + 5) % 7 + 1
    }

    /** 该日期所在周（周一为一周起点）的绝对周序号，自 1970-01-05（周一）起算，跨年稳定 */
    fun weekIndex(date: String): Long {
        val c = Calendar.getInstance()
        c.time = dayFmt.parse(date)!!
        val offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // 距本周一的天数（周一=0）
        c.add(Calendar.DAY_OF_YEAR, -offset)
        val epoch = Calendar.getInstance().apply { clear(); set(1970, 0, 5) }.timeInMillis
        return (c.timeInMillis - epoch) / 86400000L / 7
    }

    /** 距某个 yyyy-MM-dd 日期过去的整天数；解析失败返回很大值 */
    fun daysSince(date: String?): Int {
        if (date.isNullOrBlank()) return Int.MAX_VALUE
        return try {
            val d = dayFmt.parse(date) ?: return Int.MAX_VALUE
            (((System.currentTimeMillis() - d.time) / 86400000L)).toInt()
        } catch (e: Exception) { Int.MAX_VALUE }
    }
}
