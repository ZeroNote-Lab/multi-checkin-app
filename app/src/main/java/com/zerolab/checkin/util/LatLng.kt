package com.zerolab.checkin.util

import java.util.Locale

/** 度分秒（秒保留 1 位小数，整数秒不显示小数），如 39°14′14″N 123°9′0″W；自动处理 60 秒进位 */
private fun dms(v: Double): String {
    var d = v.toInt()
    var mf = (v - d) * 60.0
    var m = mf.toInt()
    var s = (mf - m) * 60.0
    if (s >= 59.95) { s = 0.0; m += 1 }
    if (m >= 60) { m = 0; d += 1 }
    val sInt = s.toInt()
    val sStr = if (Math.abs(s - sInt) < 0.05) "$sInt''" else String.format(Locale.US, "%.1f''", s)
    return "${d}°${m}'${sStr}"
}

/** 标准经纬度：23°0′0″N 100°10′23″E；西经/南纬带 W/S */
fun formatLatLng(lat: Double, lng: Double): String {
    val latDir = if (lat >= 0) "N" else "S"
    val lngDir = if (lng >= 0) "E" else "W"
    return dms(Math.abs(lat)) + latDir + " " + dms(Math.abs(lng)) + lngDir
}
