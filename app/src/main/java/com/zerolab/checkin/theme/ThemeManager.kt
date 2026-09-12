package com.zerolab.checkin.theme

import android.graphics.Color
import androidx.annotation.ColorInt

data class FloraTheme(
    val id: String,
    val name: String,
    val emoji: String,
    @ColorInt val primary: Int,
    @ColorInt val gradientStart: Int,
    @ColorInt val gradientEnd: Int,
    @ColorInt val soft: Int      // 浅底/文字衬托色
)

object ThemeManager {
    val themes = listOf(
        FloraTheme("sakura", "樱花粉", "🌸", Color.parseColor("#FF6FB0"), Color.parseColor("#FFD3E2"), Color.parseColor("#FF69B4"), Color.parseColor("#FFF0F6")),
        FloraTheme("sunflower", "向日葵", "🌻", Color.parseColor("#F5A623"), Color.parseColor("#FFE28A"), Color.parseColor("#FF9F1C"), Color.parseColor("#FFF7E3")),
        FloraTheme("mint", "薄荷绿", "🍃", Color.parseColor("#2EAF7D"), Color.parseColor("#B6F0CB"), Color.parseColor("#2E8B57"), Color.parseColor("#E8FAF0")),
        FloraTheme("lavender", "薰衣草", "💜", Color.parseColor("#8E6BD6"), Color.parseColor("#E0D0F7"), Color.parseColor("#8A2BE2"), Color.parseColor("#F4EEFD")),
        FloraTheme("coral", "珊瑚橘", "🧡", Color.parseColor("#FF6B3D"), Color.parseColor("#FFC2A8"), Color.parseColor("#FF4500"), Color.parseColor("#FFF1EA")),
        FloraTheme("ocean", "海洋蓝", "🌊", Color.parseColor("#3D8BFD"), Color.parseColor("#B6D8FF"), Color.parseColor("#4169E1"), Color.parseColor("#EBF3FF")),
        FloraTheme("rose", "玫瑰红", "🌹", Color.parseColor("#E83E8C"), Color.parseColor("#FFB3D6"), Color.parseColor("#C71585"), Color.parseColor("#FFEDF5")),
        FloraTheme("succulent", "多肉绿", "🌵", Color.parseColor("#4CAF7A"), Color.parseColor("#BFE9CC"), Color.parseColor("#3CB371"), Color.parseColor("#EDFAF1"))
    )

    private val map = themes.associateBy { it.id }
    fun of(id: String?): FloraTheme = map[id] ?: themes.first()
    fun indexOf(id: String?): Int = themes.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** 根据背景色亮度返回黑/白文字色，保证字一定看得清 */
    @ColorInt
    fun onColor(@ColorInt bg: Int): Int {
        val r = Color.red(bg); val g = Color.green(bg); val b = Color.blue(bg)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.62) Color.parseColor("#1F2430") else Color.WHITE
    }
}
