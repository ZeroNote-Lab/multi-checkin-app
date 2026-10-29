package com.zerolab.checkin.util

import android.content.Context

/**
 * 后台计步的本地存储。
 *
 * 前台服务持续读取 TYPE_STEP_COUNTER（开机累计值），把「当天 0 点后的累计步数」算出来存这里：
 *  - 跨天时用上一次读数作为新一天的基准（服务在跑时误差只有几秒）；
 *  - 服务刚启动 / 设备重启导致计数回退时，以当前读数为基准；
 *  - heartbeat 每分钟更新一次，读取时据此判断服务是否还活着。
 */
object StepStore {
    private const val PREFS = "step_bg"
    const val KEY_ENABLED = "enabled"
    private const val KEY_DATE = "date"
    private const val KEY_BASE = "base"
    private const val KEY_TOTAL = "total"
    private const val KEY_LAST_COUNTER = "last_counter"
    private const val KEY_LAST_TS = "last_ts"
    private const val KEY_HEARTBEAT = "heartbeat"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_ENABLED, v).apply() }

    /** 服务是否在最近 5 分钟内上报过心跳 */
    fun isRunning(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ENABLED, false)) return false
        val hb = p.getLong(KEY_HEARTBEAT, 0L)
        return hb > 0 && System.currentTimeMillis() - hb < 5 * 60_000L
    }

    fun heartbeat(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
    }

    /** 今日后台计步步数；没有今天的数据返回 null */
    fun todayStepsOrNull(ctx: Context): Long? {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ENABLED, false)) return null
        if (p.getString(KEY_DATE, "") != DateUtils.today()) return null
        if (!isRunning(ctx)) return null
        return p.getLong(KEY_TOTAL, 0L)
    }

    /** 计数传感器回调：返回今日步数 */
    fun onCounterEvent(ctx: Context, counter: Long): Long {
        val p = prefs(ctx)
        val today = DateUtils.today()
        val date = p.getString(KEY_DATE, "") ?: ""
        val last = p.getLong(KEY_LAST_COUNTER, -1L)
        val lastTs = p.getLong(KEY_LAST_TS, 0L)
        var base = p.getLong(KEY_BASE, -1L)
        var total = p.getLong(KEY_TOTAL, 0L)
        val now = System.currentTimeMillis()
        if (date != today) {
            // 跨天：服务连续在跑（15 分钟内有读数）时用上一条读数当 0 点基准；否则从现在开始
            val continuous = lastTs > 0 && now - lastTs < 15 * 60_000L && last in 1..counter
            base = if (continuous) last else counter
            total = (counter - base).coerceAtLeast(0L)
        } else {
            if (base < 0 || counter < base) base = counter
            total = (counter - base).coerceAtLeast(0L)
        }
        p.edit()
            .putString(KEY_DATE, today)
            .putLong(KEY_BASE, base)
            .putLong(KEY_TOTAL, total)
            .putLong(KEY_LAST_COUNTER, counter)
            .putLong(KEY_LAST_TS, now)
            .putLong(KEY_HEARTBEAT, now)
            .apply()
        return total
    }

    /** 只有步数检测器（没有计步器）时：按事件累加 */
    fun onDetectorStep(ctx: Context): Long {
        val p = prefs(ctx)
        val today = DateUtils.today()
        var total = p.getLong(KEY_TOTAL, 0L)
        if (p.getString(KEY_DATE, "") != today) total = 0L
        total += 1
        p.edit()
            .putString(KEY_DATE, today)
            .putLong(KEY_TOTAL, total)
            .putLong(KEY_LAST_TS, System.currentTimeMillis())
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
        return total
    }

    /** 跨天检查：服务每分钟调用一次，没收到计步事件也能把日期基准切到新一天 */
    fun rollDayIfNeeded(ctx: Context) {
        val p = prefs(ctx)
        val today = DateUtils.today()
        if (p.getString(KEY_DATE, "") == today) return
        val last = p.getLong(KEY_LAST_COUNTER, -1L)
        if (last < 0) return
        p.edit()
            .putString(KEY_DATE, today)
            .putLong(KEY_BASE, last)
            .putLong(KEY_TOTAL, 0L)
            .apply()
    }

    /** 停止后台计步时清掉今日计数，避免下次开启读到旧值 */
    fun clearToday(ctx: Context) {
        val p = prefs(ctx)
        p.edit()
            .remove(KEY_DATE)
            .remove(KEY_BASE)
            .remove(KEY_TOTAL)
            .remove(KEY_LAST_COUNTER)
            .remove(KEY_HEARTBEAT)
            .apply()
    }
}
