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

    /** v1.4.0：周模式（只显示当前周一行，高度紧凑） */
    var weekMode: Boolean = false
        set(value) { field = value; requestLayout(); invalidate() }
    /** v1.4.0：日记模式月视图矮一点，普通项保持300dp */
    var compactMonth: Boolean = false
        set(value) { field = value; requestLayout(); invalidate() }

    /** v1.4.0：周视图锚点日期（默认今天），左右箭头切周时±7天 */
    var weekAnchor: String = DateUtils.today()
        set(value) { field = value; invalidate() }

    /** 日期字符串 -> 当日信息（仅本月需要） */
    private var infoMap: Map<String, DayInfo> = emptyMap()
    private var grid: List<Pair<String, Boolean>> = emptyList()
    private var year = 0; private var month = 0
    private var listener: ((String) -> Unit)? = null
    /** 负打卡模式：缺卡(FAIL)是否显示红色（负打卡=破戒记录才红；普通缺卡无底色） */
    private var negativeMode = false
    /** v1.3.15：是否显示圆外小标记（⚡/×n；打卡组日历不显示） */
    private var showBadges = true
    /** v1.3.26：当前选中的日期（点击圈选，参考真实日历；默认今天） */
    var selectedDate: String? = DateUtils.today()
        set(value) { field = value; invalidate() }

    private val successC = 0xFF2FBF71.toInt()
    private val failC = 0xFFEF5350.toInt()
    private val offsetC = 0xFF4C8DFF.toInt()
    private val skipC = 0xFFF59E0B.toInt()
    private val partialC = 0xFFFFC53D.toInt()   // v1.1.6 部分完成（组合未全完成）黄色
    private val futureText = 0xFFC4CAD6.toInt()
    private val normalText = 0xFF3A4152.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** v1.3.0：心情 5 档色值（index 1~5：😄 开心绿 → 😖 很差红），图例/折线图/日历共用 */
    companion object {
        val MOOD_COLORS = listOf(
            0,
            0xFF2FBF71.toInt(),  // 1 😄 开心
            0xFF9CCC65.toInt(),  // 2 🙂 不错
            0xFFFBC02D.toInt(),  // 3 😐 一般
            0xFFFB8C00.toInt(),  // 4 😟 低落
            0xFFE53935.toInt()   // 5 😖 很差
        )
        val MOOD_EMOJIS = listOf("😄", "🙂", "😐", "😟", "😖")

        /** 解析记录的心情档位（extraJson.mood，1~5；无返回 null） */
        fun moodOf(r: com.zerolab.checkin.data.entity.CheckinRecord): Int? = try {
            org.json.JSONObject(r.extraJson ?: "{}").optInt("mood", 0).takeIf { it in 1..5 }
        } catch (_: Exception) { null }
    }

    fun setData(year: Int, month0: Int, infos: Map<String, DayInfo>, negative: Boolean, onClick: (String) -> Unit, showBadges: Boolean = true) {
        this.year = year; this.month = month0
        this.grid = DateUtils.monthGrid(year, month0)
        this.infoMap = infos
        this.negativeMode = negative
        this.showBadges = showBadges
        this.listener = onClick
        invalidate()
    }

    private val headerH get() = (34 * resources.displayMetrics.density)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // v1.4.0：周模式78dp；月模式普通项300dp，日记模式220dp
        val h = when {
            weekMode -> (34 + 44).dp()
            compactMonth -> 220.dp()
            else -> 300.dp()
        }
        setMeasuredDimension(measuredWidth, h)
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colW = width / 7f
        val week = listOf("一", "二", "三", "四", "五", "六", "日")
        textPaint.textSize = 12f * resources.displayMetrics.scaledDensity
        textPaint.color = 0xFF8A91A3.toInt()
        val baseY = headerH / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        week.forEachIndexed { i, w -> canvas.drawText(w, colW * i + colW / 2, baseY, textPaint) }

        val top = headerH
        // v1.4.0：周模式只画 weekAnchor 所在那一行，平移到可视区域顶部
        val rowOffset = if (weekMode) {
            val anchorIdx = grid.indexOfFirst { it.first == weekAnchor }
            if (anchorIdx >= 0) anchorIdx / 7 else 0
        } else 0
        val rowH = (height - headerH) / (if (weekMode) 1f else 6f)
        grid.forEachIndexed { idx, (date, inMonth) ->
            val row = idx / 7; val col = idx % 7
            if (weekMode && row != rowOffset) return@forEachIndexed
            val displayRow = row - rowOffset
            val cx = colW * col + colW / 2
            val cy = top + rowH * displayRow + rowH / 2
            val info = infoMap[date]
            val day = DateUtils.dayOfMonth(date)
            val radius = Math.min(colW, rowH) * 0.36f
            val today = date == DateUtils.today()

            val bg = when {
                !inMonth -> 0
                info == null -> if (date > DateUtils.today()) 0 else 0
                info.state == DayState.SUCCESS -> info.records.lastOrNull { moodOf(it) != null }
                    ?.let { MOOD_COLORS[moodOf(it)!!] } ?: successC
                info.state == DayState.FAIL -> if (date == DateUtils.today() && info.records.isEmpty() && !info.finalToday) 0 else failC
                info.state == DayState.OFFSET -> offsetC
                info.state == DayState.SKIP -> skipC
                info.state == DayState.PARTIAL -> partialC
                else -> 0
            }
            if (bg != 0 && date != selectedDate) {
                paint.color = bg
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(cx - radius, cy - radius, cx + radius, cy + radius), radius*0.3f, radius*0.3f, paint)
            }
            if (inMonth && date == selectedDate) {
                val d = resources.displayMetrics.density
                if (bg != 0) {
                    val fr = radius - 2.5f * d
                    paint.color = bg
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(RectF(cx - fr, cy - fr, cx + fr, cy + fr), fr * 0.3f, fr * 0.3f, paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                paint.color = themeColor
                canvas.drawRoundRect(RectF(cx - radius, cy - radius, cx + radius, cy + radius), radius*0.3f, radius*0.3f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                paint.strokeWidth = 1.5f * d
                val wr = radius - 1.75f * d
                canvas.drawRoundRect(RectF(cx - wr, cy - wr, cx + wr, cy + wr), wr * 0.3f, wr * 0.3f, paint)
                paint.style = Paint.Style.FILL
            }
            textPaint.textSize = 14f * resources.displayMetrics.scaledDensity
            textPaint.isFakeBoldText = bg != 0
            val textOnBg = if (bg == MOOD_COLORS[2] || bg == MOOD_COLORS[3]) 0xFF1F2430.toInt() else 0xFFFFFFFF.toInt()
            textPaint.color = when {
                !inMonth -> futureText
                bg != 0 -> textOnBg
                else -> normalText
            }
            val hasBottom = showBadges && info != null && (info.isAuto || info.count > 1)
            val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(day.toString(), cx, ty, textPaint)
            textPaint.isFakeBoldText = false
            if (hasBottom) {
                val inf = info!!
                textPaint.textSize = 9f * resources.displayMetrics.scaledDensity
                textPaint.color = 0xFF3A4152.toInt()
                textPaint.isFakeBoldText = true
                val label = (if (inf.isAuto) "⚡" else "") + (if (inf.count > 1) "×${inf.count}" else "")
                val labelW = textPaint.measureText(label)
                val rightPad = 2f * resources.displayMetrics.density
                val lx = cx + colW / 2f - rightPad - labelW / 2f
                canvas.drawText(label, lx, cy - radius * 0.85f, textPaint)
                textPaint.isFakeBoldText = false
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val colW = width / 7f
            // v1.4.0：周模式下行号需要偏移
            val rowOffset = if (weekMode) {
                val todayIdx = grid.indexOfFirst { it.first == DateUtils.today() }
                if (todayIdx >= 0) todayIdx / 7 else 0
            } else 0
            val rowH = (height - headerH) / (if (weekMode) 1f else 6f)
            val col = (event.x / colW).toInt()
            val displayRow = ((event.y - headerH) / rowH).toInt()
            val row = displayRow + rowOffset
            val idx = row * 7 + col
            if (idx in grid.indices) {
                val (date, inMonth) = grid[idx]
                if (inMonth && date <= DateUtils.today()) {
                    selectedDate = date
                    listener?.invoke(date)
                }
            }
        }
        return true
    }
}
