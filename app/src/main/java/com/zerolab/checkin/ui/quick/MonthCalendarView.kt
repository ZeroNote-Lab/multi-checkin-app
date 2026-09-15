package com.zerolab.checkin.ui.quick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.zerolab.checkin.R
import com.zerolab.checkin.engine.DayInfo
import com.zerolab.checkin.engine.DayState
import com.zerolab.checkin.util.DateUtils

class MonthCalendarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var themeColor: Int = 0xFF39C5BB.toInt()
        set(value) { field = value; invalidate() }

    /** 日期字符串 -> 当日信息（仅本月需要） */
    private var infoMap: Map<String, DayInfo> = emptyMap()
    private var grid: List<Pair<String, Boolean>> = emptyList()
    private var year = 0; private var month = 0
    private var listener: ((String) -> Unit)? = null
    /** 负打卡模式：缺卡(FAIL)是否显示红色（负打卡=破戒记录才红；普通缺卡无底色） */
    private var negativeMode = false

    private val successC = 0xFF2FBF71.toInt()
    private val failC = 0xFFEF5350.toInt()
    private val offsetC = 0xFF4C8DFF.toInt()
    private val skipC = 0xFFF59E0B.toInt()
    private val partialC = 0xFFFFC53D.toInt()   // v1.1.6 部分完成（组合未全完成）黄色
    private val futureText = 0xFFC4CAD6.toInt()
    private val normalText = 0xFF3A4152.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun setData(year: Int, month0: Int, infos: Map<String, DayInfo>, negative: Boolean, onClick: (String) -> Unit) {
        this.year = year; this.month = month0
        this.grid = DateUtils.monthGrid(year, month0)
        this.infoMap = infos
        this.negativeMode = negative
        this.listener = onClick
        invalidate()
    }

    private val headerH get() = (34 * resources.displayMetrics.density)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colW = width / 7f
        val week = listOf("一", "二", "三", "四", "五", "六", "日")
        textPaint.textSize = 12f * resources.displayMetrics.scaledDensity
        textPaint.color = 0xFF8A91A3.toInt()
        val baseY = headerH / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        week.forEachIndexed { i, w -> canvas.drawText(w, colW * i + colW / 2, baseY, textPaint) }

        val top = headerH
        val rowH = (height - headerH) / 6f
        grid.forEachIndexed { idx, (date, inMonth) ->
            val row = idx / 7; val col = idx % 7
            val cx = colW * col + colW / 2
            val cy = top + rowH * row + rowH / 2
            val info = infoMap[date]
            val day = DateUtils.dayOfMonth(date)
            val radius = Math.min(colW, rowH) * 0.36f
            val today = date == DateUtils.today()

            val bg = when {
                !inMonth -> 0
                info == null -> if (date > DateUtils.today()) 0 else 0
                info.state == DayState.SUCCESS -> successC      // 手动/自动成功统一绿色
            info.state == DayState.FAIL -> if (date == DateUtils.today() && info.records.isEmpty() && !info.finalToday) 0 else failC // v1.1.7：破戒/固定时间段超时当天红；普通缺卡次日红
                info.state == DayState.OFFSET -> offsetC
                info.state == DayState.SKIP -> skipC            // 无需打卡日：橙色
                info.state == DayState.PARTIAL -> partialC     // v1.1.6 部分完成：黄色
                else -> 0
            }
            if (bg != 0) {
                paint.color = bg
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(cx - radius, cy - radius, cx + radius, cy + radius), radius*0.3f, radius*0.3f, paint)
            } else if (inMonth && today) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * resources.displayMetrics.density
                paint.color = themeColor
                canvas.drawRoundRect(RectF(cx - radius, cy - radius, cx + radius, cy + radius), radius*0.3f, radius*0.3f, paint)
                paint.style = Paint.Style.FILL
            }
            textPaint.textSize = 14f * resources.displayMetrics.scaledDensity
            textPaint.isFakeBoldText = bg != 0
            textPaint.color = when {
                !inMonth -> futureText
                bg != 0 -> 0xFFFFFFFF.toInt()
                else -> normalText
            }
            val hasBottom = info != null && (info.isAuto || info.count > 1)
            val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2 - (if (hasBottom) rowH*0.12f else 0f)
            canvas.drawText(day.toString(), cx, ty, textPaint)
            textPaint.isFakeBoldText = false
            // 底部小标记：自动打卡 ⚡ 与多次打卡 ×n（位于日期下方同一位置）
            if (hasBottom) {
                val inf = info!!
                textPaint.textSize = 9f * resources.displayMetrics.scaledDensity
                textPaint.color = if (bg != 0) 0xFFFFFFFF.toInt() else normalText
                val label = (if (inf.isAuto) "⚡" else "") + (if (inf.count > 1) "${inf.count}次" else "")
                canvas.drawText(label, cx, cy + rowH * 0.26f, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val colW = width / 7f
            val rowH = (height - headerH) / 6f
            val col = (event.x / colW).toInt()
            val row = ((event.y - headerH) / rowH).toInt()
            val idx = row * 7 + col
            if (idx in grid.indices) {
                val (date, inMonth) = grid[idx]
                if (inMonth && date <= DateUtils.today()) listener?.invoke(date)
            }
        }
        return true
    }
}
