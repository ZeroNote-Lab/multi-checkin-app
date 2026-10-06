package com.zerolab.checkin.ui.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zerolab.checkin.R
import com.zerolab.checkin.theme.GlobalTheme
import com.zerolab.checkin.theme.GlobalThemeManager
import com.zerolab.checkin.theme.ThemeUi

/**
 * v1.3.14 外观主题：6 套莫兰迪主题选择，选中即持久化，返回主界面后全局换肤。
 */
class ThemeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUi.applyWindow(this)
        val curId = GlobalThemeManager.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlobalThemeManager.of(curId).let { android.graphics.drawable.ColorDrawable(it.bg) }
            setPadding(0, 0, 0, 24.dp())
        }

        // 顶部栏
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 0, 16.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56.dp())
        }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            background = getDrawable(android.R.drawable.ic_menu_close_clear_cancel) // 占位，下面用 drawable 覆盖
            setBackgroundResource(0)
            layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())
            setOnClickListener { finish() }
        }.also { it.setImageResource(R.drawable.ic_back) })
        top.addView(TextView(this).apply {
            text = "外观主题"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F2430.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8.dp() }
        })
        root.addView(top)

        // 说明
        root.addView(TextView(this).apply {
            text = "选择后全局界面即时换肤（背景 / 主色 / 选中态）"
            textSize = 12f
            setTextColor(0xFF6E7F78.toInt())
            setPadding(20.dp(), 2.dp(), 20.dp(), 10.dp())
        })

        // 6 张主题卡
        GlobalThemeManager.themes.forEach { t ->
            root.addView(themeCard(t, t.id == curId))
        }

        setContentView(root)
    }

    private fun themeCard(t: GlobalTheme, selected: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16.dp().toFloat()
                setColor(0xFFFFFFFF.toInt())
                setStroke(if (selected) 3.dp() else 1.dp(), if (selected) t.accent else 0xFFE7EAF1.toInt())
            }
            setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 14.dp(); marginEnd = 14.dp(); topMargin = 12.dp()
            }
            setOnClickListener {
                GlobalThemeManager.set(this@ThemeActivity, t.id)
                Toast.makeText(this@ThemeActivity, "已切换为「${t.name}」", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        // 主色圆底
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(t.accent)
            }
            layoutParams = LinearLayout.LayoutParams(46.dp(), 46.dp())
        }.also { wrap ->
            wrap.addView(TextView(this).apply {
                text = t.emoji
                textSize = 20f
            })
        })
        // 名称 + 色板
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 14.dp() }
        }
        col.addView(TextView(this).apply {
            text = t.name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F2430.toInt())
        })
        col.addView(TextView(this).apply {
            text = t.en
            textSize = 11f
            setTextColor(0xFF9AA1B2.toInt())
            setPadding(0, 2.dp(), 0, 0)
        })
        // 色板小圆点
        val dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6.dp(), 0, 0)
        }
        t.dots.forEach { c ->
            dotsRow.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(1.dp(), 0xFFE7EAF1.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(14.dp(), 14.dp()).apply { marginEnd = 6.dp() }
            })
        }
        col.addView(dotsRow)
        card.addView(col)
        // 选中标记
        card.addView(TextView(this).apply {
            text = if (selected) "✓" else ""
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(t.accent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())
        })
        return card
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
