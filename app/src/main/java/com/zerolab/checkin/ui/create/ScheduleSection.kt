package com.zerolab.checkin.ui.create

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.zerolab.checkin.R

/**
 * v1.4.2 优化：创建页「打卡日期」区块（从 CreateItemActivity 抽出，约 140 行）。
 *
 * 负责四种排期模式（每天 / 每周固定几天 / 双休 / 大小周）的控件、状态与联动文案；
 * 状态由本类持有，保存时由页面读取 [mode] / [weekDays] / [bigSmallStart]。
 */
class ScheduleSection(
    private val activity: CreateItemActivity,
    private val isJournal: () -> Boolean,
    private val toast: (String) -> Unit
) {
    /** DAILY / WEEKDAYS / DOUBLE_REST / BIGSMALL */
    var mode: String = "DAILY"
    /** 1=周一 … 7=周日（WEEKDAYS 模式使用） */
    val weekDays: MutableSet<Int> = linkedSetOf()
    /** BIG=本周大周 / SMALL=本周小周（BIGSMALL 模式使用） */
    var bigSmallStart: String = "BIG"

    private val modeChips = LinkedHashMap<String, TextView>()
    private val dayChips = LinkedHashMap<Int, TextView>()
    private lateinit var hint: TextView
    private lateinit var weekDaysPanel: LinearLayout
    private lateinit var bigSmallPanel: LinearLayout
    private lateinit var bigChip: TextView
    private lateinit var smallChip: TextView
    private lateinit var bsHint: TextView

    /** 构建到页面传入的容器（R.id.schedule_container），构造后立即完成一次渲染 */
    fun build(container: LinearLayout) {
        // 模式选择行
        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "DAILY" to "每天",
            "WEEKDAYS" to "每周固定几天",
            "DOUBLE_REST" to "双休",
            "BIGSMALL" to "大小周"
        ).forEach { (m, label) ->
            val tv = scheduleChip(label, m == mode) {
                mode = m
                modeChips.forEach { (k, v) -> applyChipStyle(v, k == m) }
                renderPanels()
            }
            modeRow.addView(tv)
            modeChips[m] = tv
        }
        container.addView(modeRow)

        hint = TextView(activity).apply {
            textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 10, 0, 0)
        }
        container.addView(hint)

        // 每周固定几天：7 个圆形多选（选中绿色）
        weekDaysPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 2)
            visibility = View.GONE
        }
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { idx, label ->
            val day = idx + 1
            val tv = TextView(activity).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(0, (44 * activity.resources.displayMetrics.density).toInt(), 1f)
                lp.marginEnd = 8
                layoutParams = lp
                setOnClickListener {
                    // v1.2.0：随心记不设排期
                    if (isJournal()) {
                        toast("随心记不设排期，暂不支持选择星期")
                        return@setOnClickListener
                    }
                    if (day in weekDays) weekDays.remove(day) else weekDays.add(day)
                    renderWeekDayChips()
                }
            }
            weekDaysPanel.addView(tv)
            dayChips[day] = tv
        }
        container.addView(weekDaysPanel)
        renderWeekDayChips()

        // 大小周：选本周是大周还是小周（含解释）
        bigSmallPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        val bsRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        bigChip = scheduleChip("大周（单休）", bigSmallStart == "BIG") {
            bigSmallStart = "BIG"; renderBigSmallChips()
        }
        smallChip = scheduleChip("小周（双休）", bigSmallStart == "SMALL") {
            bigSmallStart = "SMALL"; renderBigSmallChips()
        }
        bsRow.addView(bigChip); bsRow.addView(smallChip)
        bigSmallPanel.addView(bsRow)
        bsHint = TextView(activity).apply {
            textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 8, 0, 0)
        }
        bigSmallPanel.addView(bsHint)
        container.addView(bigSmallPanel)
        renderBigSmallChips()

        renderPanels()
    }

    /** 编辑回显 / 状态变化后整块重绘 */
    fun render() {
        modeChips.forEach { (k, v) -> applyChipStyle(v, k == mode) }
        renderPanels()
        renderWeekDayChips()
        renderBigSmallChips()
    }

    /** 锁定 / 解锁：置灰但保持原 alpha 由调用方另行控制（与页面 refreshConflicts 的顺序保持一致） */
    fun setEnabled(enabled: Boolean) {
        modeChips.values.forEach { it.isEnabled = enabled }
        dayChips.values.forEach { it.isEnabled = enabled }
        bigChip.isEnabled = enabled
        smallChip.isEnabled = enabled
    }

    /** 统一设置透明度（0.4=弱化提示不可用） */
    fun setAlpha(alpha: Float) {
        modeChips.values.forEach { it.alpha = alpha }
        dayChips.values.forEach { it.alpha = alpha }
        bigChip.alpha = alpha
        smallChip.alpha = alpha
    }

    private fun scheduleChip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            this.text = text; textSize = 13f; gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 10
            layoutParams = lp
            setOnClickListener {
                // v1.2.0：随心记不设排期，点击弹提示
                if (isJournal()) {
                    toast("随心记不设排期，暂不支持调整打卡日期")
                    return@setOnClickListener
                }
                onClick()
            }
            applyChipStyle(this, selected)
        }

    private fun applyChipStyle(tv: TextView, selected: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = 20f
        bg.setColor(if (selected) 0xFF3A4152.toInt() else 0xFFEEF1F6.toInt())
        tv.background = bg
        tv.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF4A5160.toInt())
        tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun renderPanels() {
        hint.text = when (mode) {
            "WEEKDAYS" -> "仅选中的日期需要打卡，其余自动标记为「无需打卡」"
            "DOUBLE_REST" -> "周一至周五打卡，周六、周日休息（无需打卡）"
            "BIGSMALL" -> "大周=单休（周六也需打卡，周日休息）；小周=双休（周六、周日都休息），两种周交替"
            else -> "每天都需要打卡"
        }
        weekDaysPanel.visibility = if (mode == "WEEKDAYS") View.VISIBLE else View.GONE
        bigSmallPanel.visibility = if (mode == "BIGSMALL") View.VISIBLE else View.GONE
    }

    private fun renderWeekDayChips() {
        dayChips.forEach { (day, tv) ->
            val on = day in weekDays
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(if (on) 0xFF2FBF71.toInt() else 0xFFEEF1F6.toInt())
            tv.background = bg
            tv.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF4A5160.toInt())
            tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun renderBigSmallChips() {
        applyChipStyle(bigChip, bigSmallStart == "BIG")
        applyChipStyle(smallChip, bigSmallStart == "SMALL")
        bsHint.text = if (bigSmallStart == "BIG")
            "当前周为大周：周六也需打卡，仅周日休息"
        else
            "当前周为小周：周六、周日都休息（下周六需打卡）"
    }
}
