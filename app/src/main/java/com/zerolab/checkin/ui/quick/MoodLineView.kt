package com.zerolab.checkin.ui.quick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 心情折线图（v1.3.0）：日记备注栏第一行展示当天多次心情记录的趋势。
 * 输入：当天带心情的记录按时间排序的档位（1=😄 开心 … 5=😖 很差）。
 * 绘制：5 档横向网格线 + 折线 + 圆点（点色=心情色）。
 */
class MoodLineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var moods: List<Int> = emptyList()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f
        color = 0x33A0A0A0.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
        color = 0x99888888.toInt()
        textAlign = Paint.Align.LEFT
    }

    fun setMoods(list: List<Int>) {
        moods = list.filter { it in 1..5 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (moods.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val padL = 8f * resources.displayMetrics.density
        val padR = 10f * resources.displayMetrics.density
        val padT = 8f * resources.displayMetrics.density
        val padB = 14f * resources.displayMetrics.density
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        // 5 档网格线（1=顶部开心 … 5=底部很差）
        for (i in 1..5) {
            val y = padT + (i - 1) * plotH / 4f
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
        }
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("😄", w - padR + 2f * resources.displayMetrics.density, padT + labelPaint.textSize, labelPaint)
        canvas.drawText("😖", w - padR + 2f * resources.displayMetrics.density, h - padB + labelPaint.textSize * 0.4f, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        // 折线
        val path = Path()
        val pts = mutableListOf<android.graphics.PointF>()
        moods.forEachIndexed { idx, mood ->
            val x = padL + idx * (plotW / (moods.size - 1).toFloat())
            val y = padT + (mood - 1) * plotH / 4f
            pts.add(android.graphics.PointF(x, y))
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        linePaint.color = 0xFF8A93A6.toInt()
        canvas.drawPath(path, linePaint)
        // 圆点（点色=该条心情色）
        pts.forEachIndexed { idx, p ->
            dotPaint.color = MonthCalendarView.MOOD_COLORS[moods[idx]]
            canvas.drawCircle(p.x, p.y, 5f * resources.displayMetrics.density, dotPaint)
            // 首末点稍大
            if (idx == 0 || idx == pts.size - 1) {
                dotPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(p.x, p.y, 8f * resources.displayMetrics.density, dotPaint)
                dotPaint.color = MonthCalendarView.MOOD_COLORS[moods[idx]]
                canvas.drawCircle(p.x, p.y, 6.5f * resources.displayMetrics.density, dotPaint)
            }
        }
    }
}
