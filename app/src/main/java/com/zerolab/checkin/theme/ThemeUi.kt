package com.zerolab.checkin.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.annotation.ColorInt

/**
 * v1.3.14 全局换肤工具：把当前 GlobalTheme 应用到界面根背景 / 状态栏 / 主按钮 / 主文字。
 * 各页面 onCreate/onViewCreated 末尾调用一次；卡片类元素保留白/浅色，与莫兰迪浅背景天然协调。
 */
object ThemeUi {

    fun current(activity: Activity): GlobalTheme = GlobalThemeManager.of(GlobalThemeManager.get(activity))

    /** 窗口 + 根背景 + 状态栏/导航栏 */
    fun applyWindow(activity: Activity) {
        val t = current(activity)
        try {
            val win: Window = activity.window
            win.setBackgroundDrawable(ColorDrawable(t.bg))
            if (Build.VERSION.SDK_INT >= 21) {
                win.statusBarColor = t.bg
                win.navigationBarColor = t.bg
                if (Build.VERSION.SDK_INT >= 23) {
                    win.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
        } catch (_: Exception) {}
    }

    /** 根布局背景 + 指定 id 的主按钮着色 + 顶部标题栏文字着色 */
    fun apply(activity: Activity, root: View?, tintButtonIds: List<Int> = emptyList(), tintTextIds: List<Int> = emptyList()) {
        applyWindow(activity)
        val t = current(activity)
        try { root?.setBackgroundColor(t.bg) } catch (_: Exception) {}
        tintButtonIds.forEach { id ->
            try { (root?.findViewById<View>(id) as? Button)?.background?.setTint(t.accent) } catch (_: Exception) {}
        }
        tintTextIds.forEach { id ->
            try { (root?.findViewById<View>(id) as? TextView)?.setTextColor(t.accent) } catch (_: Exception) {}
        }
    }

    /** 颜色按比例加深（v1.3.26：底部导航选中色加深约 8%，浅色主题更清晰） */
    @ColorInt
    fun darken(@ColorInt color: Int, factor: Float = 0.92f): Int {
        val a = android.graphics.Color.alpha(color)
        val r = (android.graphics.Color.red(color) * factor).toInt()
        val g = (android.graphics.Color.green(color) * factor).toInt()
        val b = (android.graphics.Color.blue(color) * factor).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    /** 底部导航选中色（v1.3.26：accent 加深 8% 提升辨识度） */
    fun tintBottomNav(nav: com.google.android.material.bottomnavigation.BottomNavigationView, t: GlobalTheme) {
        try {
            val list = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(darken(t.accent), 0xFF9AA1B2.toInt())
            )
            nav.itemIconTintList = list
            nav.itemTextColor = list
        } catch (_: Exception) {}
    }
}
