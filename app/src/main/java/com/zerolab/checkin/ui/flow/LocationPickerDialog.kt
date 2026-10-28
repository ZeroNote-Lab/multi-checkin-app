package com.zerolab.checkin.ui.flow

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zerolab.checkin.util.ChinaCoord
import com.zerolab.checkin.util.NetGeo

/**
 * 位置选择弹窗。
 *
 * 使用自定义列表而不是 AlertDialog 的 `setMessage + setItems`：部分机型 / 主题下两者同时使用时
 * 列表会被吞掉，只剩说明文字和按钮，导致「只能取消 / 重新定位」。
 */
object LocationPickerDialog {

    /**
     * @param info 顶部说明文字（当前定位、范围判定结果等）
     * @param currentLat 当前定位纬度（WGS-84）
     * @param currentLng 当前定位经度（WGS-84）
     * @param onRetry 非空时显示「重新定位」按钮
     * @param onPick 选中回调：名称为 null 表示无名地址；坐标已转回 WGS-84
     */
    fun show(
        ctx: Context,
        title: String,
        info: String,
        detail: NetGeo.GeoDetail,
        currentLat: Double,
        currentLng: Double,
        onRetry: (() -> Unit)?,
        onPick: (name: String?, lat: Double, lng: Double) -> Unit
    ): AlertDialog {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        box.addView(TextView(ctx).apply {
            text = info
            textSize = 13f
            setTextColor(0xFF3A4152.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        var dlgRef: AlertDialog? = null

        fun addRow(name: String, sub: String?, onClick: () -> Unit) {
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true
                isFocusable = true
                if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
            }
            row.addView(TextView(ctx).apply {
                text = name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1F2430.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!sub.isNullOrBlank()) {
                row.addView(TextView(ctx).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(0xFF6E7F78.toInt())
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            row.setOnClickListener {
                dlgRef?.dismiss()
                onClick()
            }
            list.addView(row)
        }

        // 第 1 行：当前定位
        addRow(
            "使用当前定位",
            detail.address.ifBlank { "按当前经纬度记录" }
        ) { onPick(detail.address.takeIf { it.isNotBlank() }, currentLat, currentLng) }

        // 后续行：附近地点（高德 POI，GCJ-02 → WGS-84）
        detail.pois.take(12).forEach { p ->
            val w = ChinaCoord.gcj02ToWgs84(p.lat, p.lng)
            val sub = listOfNotNull(
                if (p.distanceMeters > 0) "距当前 ${p.distanceMeters} 米" else null,
                p.address.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            addRow(p.name, sub) { onPick(p.name, w[0], w[1]) }
        }
        if (detail.pois.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "未获取到附近地点，可直接使用当前定位"
                textSize = 12f
                setTextColor(0xFF9AA1B2.toInt())
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
        }

        val visualRows = 1 + detail.pois.take(12).size + if (detail.pois.isEmpty()) 1 else 0
        val scroll = ScrollView(ctx).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxOf(dp(72), minOf(dp(320), dp(60) * visualRows))
            )
        }
        box.addView(scroll)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
        if (onRetry != null) {
            builder.setNegativeButton("重新定位") { _, _ -> onRetry() }
            builder.setNeutralButton("取消", null)
        } else {
            builder.setNegativeButton("取消", null)
        }
        val dlg = builder.create()
        dlgRef = dlg
        dlg.show()
        return dlg
    }
}
