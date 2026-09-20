package com.zerolab.checkin.util

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.ui.detail.ItemDetailActivity

/**
 * v1.3.2：打卡项 ⋮ 菜单（打卡页右上角）。
 * v1.3.1 起只保留「编辑 / 详情」；心情折线图开关移到创建/编辑页（cb_mood_chart）。
 */
object MoodChartMenu {
    fun show(activity: Activity, item: CheckinItem, anchor: View, onChanged: () -> Unit) {
        val pm = PopupMenu(activity, anchor)
        pm.menu.add(0, 2, 1, "编辑 / 详情")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                2 -> {
                    activity.startActivity(Intent(activity, ItemDetailActivity::class.java)
                        .putExtra(ItemDetailActivity.EXTRA_ID, item.id))
                    true
                }
                else -> false
            }
        }
        pm.show()
    }
}
