package com.zerolab.checkin.util

import android.content.Context
import org.json.JSONObject

/**
 * 位置名称增强：可选联网逆地理编码 + 周边地点（高德 Web 服务 API）。
 *
 * 设计约束：
 *  - 默认全关，离线功能完全不受影响（不申请网络、不发请求）；
 *  - 两级开关 + 用户自带高德 API Key（不内置 Key，避免泄露 / 盗刷额度）；
 *  - 打卡时若增强可用则把地名写入记录 extraJson.locName（名称定格），之后开关变化不影响历史；
 *  - 失败 / 超时 / Key 无效一律返回 null，调用方降级为经纬度显示，绝不影响打卡成功。
 *
 * 坐标系：Android 定位返回 WGS-84，高德 API 要求 GCJ-02。本类在请求前统一做
 * [ChinaCoord.wgs84ToGcj02] 转换，返回的 POI 坐标仍是 GCJ-02（由调用方转回 WGS-84 入库），
 * 避免直接混用造成的百米级偏移。
 */
object NetGeo {
    const val PREFS = "netgeo_prefs"
    const val KEY_NET = "net_enabled"       // 联网增强总开关
    const val KEY_GEO = "geo_enabled"       // 定位增强开关（依赖联网增强）
    const val KEY_API_KEY = "api_key"       // 用户自己的高德 Web 服务 Key

    /** 周边地点（坐标为高德 GCJ-02） */
    data class Poi(
        val name: String,
        val address: String,
        val distanceMeters: Int,
        val lat: Double,
        val lng: Double
    )

    /** 逆地理编码结果：地址 + 周边 POI */
    data class GeoDetail(
        val address: String,
        val pois: List<Poi>
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isNetEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_NET, false)
    fun isGeoEnabled(ctx: Context): Boolean = isNetEnabled(ctx) && prefs(ctx).getBoolean(KEY_GEO, false)
    fun apiKey(ctx: Context): String = prefs(ctx).getString(KEY_API_KEY, "")?.trim() ?: ""

    fun setNetEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_NET, v).apply() }
    fun setGeoEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean(KEY_GEO, v).apply() }
    fun setApiKey(ctx: Context, k: String) { prefs(ctx).edit().putString(KEY_API_KEY, k.trim()).apply() }

    /** 在线可用：两级开关都开且 Key 非空 */
    fun enabled(ctx: Context): Boolean = isGeoEnabled(ctx) && apiKey(ctx).isNotBlank()

    /**
     * 只取地址文本（兼容旧调用）。必须在子线程调用；失败返回 null。
     */
    fun regeo(ctx: Context, lat: Double, lng: Double): String? =
        regeoDetail(ctx, lat, lng)?.address?.takeIf { it.isNotBlank() }

    /**
     * 逆地理编码 + 周边 500 米内的 POI（extensions=all，一次请求同时拿到地址与地点列表）。
     * 必须在子线程调用；任何失败返回 null（调用方降级为经纬度显示）。
     *
     * @param lat WGS-84 纬度
     * @param lng WGS-84 经度
     */
    fun regeoDetail(ctx: Context, lat: Double, lng: Double): GeoDetail? {
        if (!enabled(ctx)) return null
        val key = apiKey(ctx)
        if (key.isBlank()) return null

        // 高德要求 GCJ-02，先转换再请求
        val gcj = ChinaCoord.wgs84ToGcj02(lat, lng)
        var conn: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(
                "https://restapi.amap.com/v3/geocode/regeo?location=${gcj[1]},${gcj[0]}" +
                        "&key=$key&extensions=all&radius=500"
            )
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.useCaches = false
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            if (o.optString("status") != "1") return null
            val regeo = o.optJSONObject("regeocode") ?: return null
            val address = regeo.optString("formatted_address", "").trim()

            val pois = ArrayList<Poi>()
            val arr = regeo.optJSONArray("pois")
            if (arr != null) {
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    if (pois.size >= 15) break
                    val p = arr.optJSONObject(i) ?: continue
                    val name = p.optString("name", "").trim()
                    if (name.isBlank() || !seen.add(name)) continue
                    // location 为 "lng,lat"（GCJ-02）
                    val parts = p.optString("location", "").split(',')
                    if (parts.size != 2) continue
                    val plng = parts[0].trim().toDoubleOrNull() ?: continue
                    val plat = parts[1].trim().toDoubleOrNull() ?: continue
                    val dist = p.optString("distance", "").trim().toIntOrNull() ?: 0
                    pois.add(Poi(name, p.optString("address", "").trim(), dist, plat, plng))
                }
            }
            if (address.isBlank() && pois.isEmpty()) return null
            return GeoDetail(address, pois)
        } catch (_: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
