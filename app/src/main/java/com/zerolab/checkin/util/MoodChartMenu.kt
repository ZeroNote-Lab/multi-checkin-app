package com.zerolab.checkin.util

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.ui.detail.ItemDetailActivity
import kotlin.concurrent.thread

/**
 * v1.3.0：打卡项 ⋮ 菜单（打卡页右上角）。
 * 第一项「心情折线图」勾选项（仅心情日记可切，LOCKED 管不着，随时可改）；
 * 第二项「编辑 / 详情」。
 */
object MoodChartMenu {
    fun show(activity: Activity, item: CheckinItem, anchor: View, onChanged: () -> Unit) {
        val repo = (activity.application as CheckinApp).repository
        val cfg = ItemConfig.parse(item.configJson)
        val pm = PopupMenu(activity, anchor)
        val miChart = pm.menu.add(0, 1, 0, if (cfg.moodMode) "心情折线图" else "心情折线图（仅心情日记可用）")
        miChart.isCheckable = true
        miChart.isChecked = cfg.moodChart
        miChart.isEnabled = cfg.moodMode
        pm.menu.add(0, 2, 1, "编辑 / 详情")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> {
                    if (cfg.moodMode) {
                        val newCfg = ItemConfig.parse(item.configJson)
                        newCfg.moodChart = !newCfg.moodChart
                        thread {
                            repo.updateItem(item.copy(configJson = newCfg.toJson(), updatedAt = System.currentTimeMillis()))
                            activity.runOnUiThread { onChanged() }
                        }
                    }
                    true
                }
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
