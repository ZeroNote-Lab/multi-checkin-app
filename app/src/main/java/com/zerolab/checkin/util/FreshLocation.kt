package com.zerolab.checkin.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v1.4.2 实时定位：只接受本次请求期间新到达的定位结果。
 *
 * 背景：旧实现先读 getLastKnownLocation 缓存，等待超时后回退到缓存点，
 * 导致「人在大剧院却显示一小时前南沙万达」这类过期位置；这里彻底不再使用历史缓存。
 *
 * 规则：
 *  - 同时向 GPS / 网络供应商请求更新，只收本次请求开始之后（允许 5 秒时钟偏差）的回调；
 *  - 精度越好越优先，拿到 ≤[GOOD_ACCURACY_M] 的结果提前结束；超时后取最优结果；
 *  - 结果年龄超过 [MAX_AGE_MS] 或精度差于 [MAX_ACCURACY_M] 视为不可用，返回 null 由调用方提示重试。
 */
object FreshLocation {

    /** 超过该精度视为不可用（米） */
    const val MAX_ACCURACY_M = 200f
    /** 达到该精度即可结束等待（米） */
    private const val GOOD_ACCURACY_M = 60f
    /** 即使回调很快，结果时间戳也不允许早于请求开始 5 秒以上 */
    private const val CLOCK_TOLERANCE_MS = 5_000L
    /** 结果最大年龄（毫秒） */
    private const val MAX_AGE_MS = 30_000L

    class Fix(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val provider: String,
        val ageMs: Long
    ) {
        /** 「GPS · 精度 ±12 米 · 刚刚」这样的可读来源描述 */
        fun sourceText(): String {
            val p = when {
                provider.contains("gps", true) -> "GPS"
                provider.contains("network", true) -> "网络"
                else -> provider.ifBlank { "定位" }
            }
            val acc = if (accuracy <= 0f || accuracy == Float.MAX_VALUE) "" else " · 精度 ±${accuracy.toInt()} 米"
            val age = when {
                ageMs < 5_000 -> " · 刚刚"
                ageMs < 60_000 -> " · ${ageMs / 1000} 秒前"
                else -> ""
            }
            return "$p$acc$age"
        }
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /** 阻塞式申请一次实时定位（必须在子线程调用）。返回 null 表示没拿到可用结果。 */
    @SuppressLint("MissingPermission")
    fun request(ctx: Context, timeoutMs: Long = 20_000L): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = try {
            lm.getProviders(true).filter {
                it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
            }.ifEmpty { lm.getProviders(true) }
        } catch (_: Exception) {
            emptyList()
        }
        if (providers.isEmpty()) return null

        val startedAt = System.currentTimeMillis()
        val best = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                // 丢弃旧缓存回调：结果时间必须不早于本次请求（允许 5 秒时钟误差）
                if (l.time > 0L && l.time < startedAt - CLOCK_TOLERANCE_MS) return
                val cur = best.get()
                val better = cur == null ||
                    (l.hasAccuracy() && (!cur.hasAccuracy() || l.accuracy < cur.accuracy))
                if (better) best.set(l)
                val f = best.get()
                if (f != null && f.hasAccuracy() && f.accuracy <= GOOD_ACCURACY_M) latch.countDown()
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("deprecated")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        var registered = 0
        for (p in providers) {
            try {
                lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                registered++
            } catch (_: Exception) {
            }
        }
        if (registered == 0) return null

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }

        val l = best.get() ?: return null
        val age = if (l.time > 0L) (System.currentTimeMillis() - l.time).coerceAtLeast(0L) else 0L
        if (age > MAX_AGE_MS) return null
        val acc = if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE
        if (acc > MAX_ACCURACY_M) return null
        return Fix(l.latitude, l.longitude, acc, l.provider ?: "", age)
    }
}
