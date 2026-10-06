package com.zerolab.checkin.theme

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * v1.3.14 全局主题：6 套莫兰迪柔和色板。
 * 与打卡项主题（FloraTheme，图标装饰）分离：本主题决定整站背景 / 卡片 / 主色 / 选中态。
 */
data class GlobalTheme(
    val id: String,
    val name: String,
    val en: String,
    @ColorInt val accent: Int,       // 主色：按钮 / 选中 / 高亮
    @ColorInt val bg: Int,           // 页面背景（莫兰迪浅色）
    @ColorInt val card: Int,         // 卡片底色
    @ColorInt val ink: Int,          // 主文字
    @ColorInt val sub: Int,          // 副文字
    @ColorInt val line: Int,         // 分割线
    @ColorInt val soft: Int,         // 主色浅底（图标圆底）
    @ColorInt val fill: Int,         // 更浅填充（区块底）
    @ColorInt val chip: Int,         // 未选中胶囊底
    @ColorInt val mid: Int,          // 中间强调
    val dots: List<Int>              // 设置页色板展示（主色 + 浅色）
) {
    val emoji: String get() = when (id) {
        "qingti" -> "🌿"; "sakura" -> "🌸"; "wulan" -> "🌫️"
        "sun" -> "🌻"; "lavender" -> "💜"; "mint" -> "🍃"; else -> "🎨"
    }
}

object GlobalThemeManager {
    private const val PREFS = "checkin_global_theme"
    private const val KEY = "theme_id"

    val themes = listOf(
        GlobalTheme(
            "qingti", "青提浅水", "Fresh Mint", Color.parseColor("#5AB3A4"),
            Color.parseColor("#F1F8F6"), Color.parseColor("#FFFFFF"), Color.parseColor("#23403D"),
            Color.parseColor("#6B8581"), Color.parseColor("#DCEAE6"), Color.parseColor("#D7F1EB"),
            Color.parseColor("#E9F6F2"), Color.parseColor("#EDF6F3"), Color.parseColor("#7EC9BC"),
            listOf(Color.parseColor("#5AB3A4"), Color.parseColor("#D7F1EB"))
        ),
        GlobalTheme(
            "sakura", "樱花粉", "Sakura", Color.parseColor("#E5A8B8"),
            Color.parseColor("#FAF3F5"), Color.parseColor("#FFFFFF"), Color.parseColor("#432F36"),
            Color.parseColor("#8A7078"), Color.parseColor("#F0DFE4"), Color.parseColor("#F8E3E9"),
            Color.parseColor("#FBEFF2"), Color.parseColor("#F8EDF0"), Color.parseColor("#EDB6C3"),
            listOf(Color.parseColor("#E5A8B8"), Color.parseColor("#F8E3E9"))
        ),
        GlobalTheme(
            "wulan", "雾蓝", "Mist Blue", Color.parseColor("#A3BDCC"),
            Color.parseColor("#F2F6F8"), Color.parseColor("#FFFFFF"), Color.parseColor("#2C3A44"),
            Color.parseColor("#6E7F8A"), Color.parseColor("#DFE8ED"), Color.parseColor("#E4EEF3"),
            Color.parseColor("#EFF5F8"), Color.parseColor("#F0F4F6"), Color.parseColor("#B5CAD6"),
            listOf(Color.parseColor("#A3BDCC"), Color.parseColor("#E4EEF3"))
        ),
        GlobalTheme(
            "sun", "向日葵", "Sunflower", Color.parseColor("#E8C08A"),
            Color.parseColor("#FAF6EF"), Color.parseColor("#FFFFFF"), Color.parseColor("#40382C"),
            Color.parseColor("#857A6A"), Color.parseColor("#EFE6D6"), Color.parseColor("#FAEFDC"),
            Color.parseColor("#FBF3E6"), Color.parseColor("#F7F0E3"), Color.parseColor("#EFCE9F"),
            listOf(Color.parseColor("#E8C08A"), Color.parseColor("#FAEFDC"))
        ),
        GlobalTheme(
            "lavender", "薰衣草", "Lavender", Color.parseColor("#B5A7DA"),
            Color.parseColor("#F6F4FA"), Color.parseColor("#FFFFFF"), Color.parseColor("#383247"),
            Color.parseColor("#7A738C"), Color.parseColor("#E9E4F1"), Color.parseColor("#EFE8F8"),
            Color.parseColor("#F5F1FB"), Color.parseColor("#F2EFF7"), Color.parseColor("#C5B9E2"),
            listOf(Color.parseColor("#B5A7DA"), Color.parseColor("#EFE8F8"))
        ),
        GlobalTheme(
            "mint", "薄荷", "Peppermint", Color.parseColor("#79C7AC"),
            Color.parseColor("#F0F8F4"), Color.parseColor("#FFFFFF"), Color.parseColor("#24403A"),
            Color.parseColor("#6D8982"), Color.parseColor("#DCEFE7"), Color.parseColor("#DAF4EB"),
            Color.parseColor("#E8F7F1"), Color.parseColor("#EDF8F3"), Color.parseColor("#96D5BF"),
            listOf(Color.parseColor("#79C7AC"), Color.parseColor("#DAF4EB"))
        )
    )

    private val map = themes.associateBy { it.id }
    fun of(id: String?): GlobalTheme = map[id] ?: themes.first()
    fun indexOf(id: String?): Int = themes.indexOfFirst { it.id == id }.coerceAtLeast(0)

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "qingti") ?: "qingti"

    fun set(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id).apply()
    }

    /** 根据背景色亮度返回黑/白文字色 */
    @ColorInt
    fun onColor(@ColorInt bg: Int): Int {
        val r = Color.red(bg); val g = Color.green(bg); val b = Color.blue(bg)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.62) Color.parseColor("#1F2430") else Color.WHITE
    }
}
