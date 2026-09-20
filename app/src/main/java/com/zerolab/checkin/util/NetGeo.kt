package com.zerolab.checkin.util

import android.content.Context
import org.json.JSONObject

/**
 * 位置名称增强（v1.3.0）：可选联网逆地理编码。
 * 设计约束：
 *  - 默认全关，离线功能完全不受影响（不申请网络、不发请求）；
 *  - 两级开关 + 用户自带高德 API Key（不内置 Key，避免 GitHub 泄露/盗刷额度）；
 *  - 打卡时若增强可用则把真实地名写入该条记录 extraJson.locName（名称定格），之后开关变化不影响历史；
 *  - 失败/超时/Key 无效一律返回 null，调用方降级为只显示经纬度，绝不影响打卡成功。
 */
object NetGeo {
    const val PREFS = "netgeo_prefs"
    const val KEY_NET = "net_enabled"       // 联网增强总开关
    const val KEY_GEO = "geo_enabled"       // 定位增强开关（依赖联网增强）
    const val KEY_API_KEY = "api_key"       // 用户自己的高德 Web 服务 Key

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isNetEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NET, false)
    fun isGeoEnabled(ctx: Context): Boolean = isNetEnabled(ctx) && prefs(ctx).getBoolean(KEY_GEO, false)
    fun apiKey(ctx: Context): String = prefs(ctx).getString(KEY_API_KEY, "")?.trim() ?: ""

    fun setNetEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_NET, v).apply() }
    fun setGeoEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_GEO, v).apply() }
    fun setApiKey(ctx: Context, k: String) { prefs(ctx).edit().putString(KEY_API_KEY, k.trim()).apply() }

    /** 在线可用：两级开关都开且 Key 非空 */
    fun enabled(ctx: Context): Boolean = isGeoEnabled(ctx) && apiKey(ctx).isNotBlank()

    // v1.3.4：内存缓存，避免同一位置短时间内重复请求高德 API
    // key = 经纬度四舍五入到小数点后 4 位（约 11 米精度），TTL 5 分钟
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private class CacheEntry(val name: String, val ts: Long)
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    /**
     * 逆地理编码：高德 regeo，把经纬度翻译成真实地名。
     * 必须在子线程调用；任何失败返回 null（调用方降级经纬度显示）。
     * 同一位置（4 位小数）5 分钟内复用缓存结果，不重复请求。
     */
    fun regeo(ctx: Context, lat: Double, lng: Double): String? {
        if (!enabled(ctx)) return null
        val key = "%.4f,%.4f".format(lat, lng)
        val now = System.currentTimeMillis()
        cache[key]?.let { e ->
            if (now - e.ts < CACHE_TTL_MS) return e.name
        }
        val name = doRegeo(ctx, lat, lng)
        if (name != null) cache[key] = CacheEntry(name, now)
        return name
    }

    private fun doRegeo(ctx: Context, lat: Double, lng: Double): String? {
        val key = apiKey(ctx)
        if (key.isBlank()) return null
        var conn: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(
                "https://restapi.amap.com/v3/geocode/regeo?location=$lng,$lat&key=$key&extensions=base")
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.useCaches = false
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            if (o.optString("status") != "1") return null
            return o.optJSONObject("regeocode")
                ?.optString("formatted_address")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
