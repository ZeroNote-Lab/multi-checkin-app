package com.zerolab.checkin.ui.create

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zerolab.checkin.R

/**
 * 新建入口：点击 + 号后先选择打卡类型（普通打卡 / 日记打卡），再进入对应的单类型创建页。
 * 日记打卡在创建页内选择记录类型（随心记 / 心情日记）。
 * 数据驱动列表，后续新增打卡类型只需在 [typeEntries] 加一项。
 * v1.3.10：柔光卡片流视觉（渐变背景 + 光斑 + 悬浮白卡）。
 */
class ChooseTypeActivity : AppCompatActivity() {

    /** 类型入口（图标、图标圆底颜色、名称、描述、目标模式） */
    private data class TypeEntry(
        val iconRes: Int, val iconBg: Int, val name: String, val desc: String, val mode: String
    )

    private val typeEntries = listOf(
        TypeEntry(R.drawable.ic_type_check, 0xFFFFE9F2.toInt(), "普通打卡",
            "按规则打卡，有缺卡与连续天数", CreateItemActivity.MODE_NORMAL),
        TypeEntry(R.drawable.ic_type_note, 0xFFEDE5FF.toInt(), "日记打卡",
            "日记式记录，页内选随心记 / 心情日记", CreateItemActivity.MODE_JOURNAL)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_choose_type)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "选择打卡类型"

        setupBlobs()

        val container = findViewById<LinearLayout>(R.id.type_container)
        typeEntries.forEachIndexed { idx, e ->
            container.addView(typeCard(e, idx != 0))
        }
    }

    /** 两个角落的柔光大光斑（径向渐变，代码创建避免资源渲染差异） */
    private fun setupBlobs() {
        findViewById<android.view.View>(R.id.blob_pink).background = radialBlob(0xFFFFD9E9.toInt(), 0x00FFD9E9.toInt())
        findViewById<android.view.View>(R.id.blob_purple).background = radialBlob(0xFFE6DCFF.toInt(), 0x00E6DCFF.toInt())
    }

    private fun radialBlob(center: Int, edge: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColors(intArrayOf(center, center, edge))
        g.gradientType = GradientDrawable.RADIAL_GRADIENT
        g.gradientRadius = 150f * resources.displayMetrics.density
        return g
    }

    /** 悬浮白卡：主题色圆底图标 + 名称 + 描述 + 箭头 */
    private fun typeCard(e: TypeEntry, withTopMargin: Boolean): LinearLayout {
        val ctx = this@ChooseTypeActivity
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_type_card)
            elevation = 14.dp().toFloat()
            setPadding(18.dp(), 16.dp(), 18.dp(), 16.dp())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(ctx, CreateItemActivity::class.java)
                    .putExtra(CreateItemActivity.EXTRA_MODE, e.mode))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (withTopMargin) lp.topMargin = 14.dp()
            layoutParams = lp

            // 图标圆底
            val iconWrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 14.dp().toFloat()
                    setColor(e.iconBg)
                }
                layoutParams = LinearLayout.LayoutParams(46.dp(), 46.dp())
            }
            iconWrap.addView(ImageView(ctx).apply {
                setImageResource(e.iconRes)
                layoutParams = LinearLayout.LayoutParams(24.dp(), 24.dp())
            })
            addView(iconWrap)

            // 文本列
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = 14.dp()
                layoutParams = lp
            }
            textCol.addView(TextView(ctx).apply {
                text = e.name
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF2A2430.toInt())
            })
            textCol.addView(TextView(ctx).apply {
                text = e.desc
                textSize = 11.5f
                setTextColor(0xFF6E6878.toInt())
                setPadding(0, 3.dp(), 0, 0)
            })
            addView(textCol)

            // 右箭头
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_type_chevron)
                layoutParams = LinearLayout.LayoutParams(18.dp(), 18.dp())
            })
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
