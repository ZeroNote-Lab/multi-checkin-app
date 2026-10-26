package com.zerolab.checkin.ui.create

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.zerolab.checkin.theme.IconManager
import com.zerolab.checkin.theme.ItemIcon
import com.zerolab.checkin.theme.ThemeManager

/**
 * v1.4.2 优化：打卡项「图标 + 主题色」选择器（从 CreateItemActivity 抽出，约 130 行）。
 *
 * 收起态显示 5 个常用图标 + 「更多」格，展开显示全部 21 个；下方一排 8 套花卉主题色点。
 * 子项弹层（打卡组）复用 [cell] / [moreCell] 两个静态构建器，避免重复实现。
 */
class IconPicker(
    private val ctx: Context,
    private val container: LinearLayout
) {
    /** 当前选中的图标 key；null = 未选（编辑旧数据时无高亮） */
    var selected: String? = null
    var expanded: Boolean = false
    /** 当前选中的打卡项主题 id（FloraTheme） */
    var themeId: String = "sakura"

    /** 主题变化回调：宿主需要把 id 存回自己的状态（保存时使用） */
    var onThemeChanged: ((String) -> Unit)? = null

    fun render() {
        container.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val grid = GridLayout(ctx).apply {
            columnCount = 6
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // v1.3.19：收起态只显示前 5 个常用图标 + 更多格；编辑回显时若选中图标不在常用 5 个，替换第 5 格
        val shown = if (expanded) IconManager.all else {
            val commons = IconManager.common.take(5).toMutableList()
            val selIcon = selected?.let { key -> IconManager.all.firstOrNull { it.key == key } }
            if (selIcon != null && commons.none { it.key == selIcon.key }) commons[4] = selIcon
            commons
        }
        shown.forEach { ic ->
            val cell = cell(ctx, ic, selected)
            cell.setOnClickListener {
                selected = ic.key
                render()
            }
            grid.addView(cell)
        }
        val more = moreCell(ctx, expanded)
        more.setOnClickListener {
            expanded = !expanded
            render()
        }
        grid.addView(more)
        container.addView(grid)

        // v1.3.24：图标下方主题选择（8 套点缀色；打卡按钮跟随全局主题，主题仅做卡片/点缀）
        container.addView(TextView(ctx).apply {
            text = "主题"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(12), 0, dp(6))
        })
        val themeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        ThemeManager.themes.forEach { t ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.bottomMargin = dp(4)
                layoutParams = lp
            }
            val dot = View(ctx).apply {
                val d = dp(26)
                layoutParams = LinearLayout.LayoutParams(d, d)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(t.primary)
                    setStroke(dp(2), if (t.id == themeId) 0xFF1F2430.toInt() else 0x00000000)
                }
            }
            cell.addView(dot)
            cell.addView(TextView(ctx).apply {
                text = t.name; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(if (t.id == themeId) 0xFF1F2430.toInt() else 0xFF9AA1B2.toInt())
            })
            cell.setOnClickListener {
                themeId = t.id
                onThemeChanged?.invoke(t.id)
                render()
            }
            themeRow.addView(cell)
        }
        container.addView(themeRow)
    }

    companion object {
        /** 图标格：纯图标居中、无文字；selKey 为当前选中 key（打卡组子项弹层复用） */
        fun cell(ctx: Context, ic: ItemIcon, selKey: String?): View {
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()
            val sel = ic.key == selKey
            val v = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val lp = GridLayout.LayoutParams().apply {
                    width = 0; height = dp(48)   // 圆角正方形；列宽均分随容器自适应，防溢出
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                layoutParams = lp
            }
            v.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (sel) 0xFFE4F6EC.toInt() else 0xFFFFFFFF.toInt())
                setStroke(if (sel) dp(2) else dp(1), if (sel) 0xFF2FBF71.toInt() else 0xFFE3EAE5.toInt())
            }
            v.addView(TextView(ctx).apply { text = ic.emoji; textSize = 22f; gravity = Gravity.CENTER })
            return v
        }

        /** 更多 / 收起格：纯图标无文字 */
        fun moreCell(ctx: Context, expanded: Boolean): View {
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()
            val v = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val lp = GridLayout.LayoutParams().apply {
                    width = 0; height = dp(48)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                layoutParams = lp
            }
            v.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (expanded) 0xFFFFFFFF.toInt() else 0x00000000)
                setStroke(dp(1), 0xFFC4D2CA.toInt())
            }
            v.addView(TextView(ctx).apply {
                text = if (expanded) "▴" else "+"; textSize = 22f
                setTextColor(0xFF7E9188.toInt()); gravity = Gravity.CENTER
            })
            return v
        }
    }
}
