package com.zerolab.checkin.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.theme.ThemeManager
import com.zerolab.checkin.ui.create.CreateItemActivity
import com.zerolab.checkin.util.DateUtils
import com.zerolab.checkin.util.formatLatLng
import kotlin.concurrent.thread

class ItemDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val repo = (application as CheckinApp).repository
        val item = repo.getItem(id)
        if (item == null) { finish(); return }

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "打卡项详情"

        val cfg = ItemConfig.parse(item.configJson)
        val theme = ThemeManager.of(item.theme)
        findViewById<TextView>(R.id.tv_emoji).text = theme.emoji
        findViewById<TextView>(R.id.tv_name).text = item.name
        findViewById<View>(R.id.header_card).background?.setTint(theme.soft)
        findViewById<TextView>(R.id.tv_sub).text =
            "主题：${theme.name}    状态：${if (item.isActive == 1) "启用中" else "已暂停"}"

        val sb = StringBuilder()
        val methodNames = cfg.methods.mapNotNull { Method.of(it)?.label }
        sb.appendLine("打卡方式：${methodNames.joinToString(" + ")}")
        sb.appendLine("每日次数：${if (cfg.dailyLimit < 0) "不限" else cfg.dailyLimit}")
        sb.appendLine("负打卡（状态反转）：${if (cfg.negative) "是" else "否"}")
        if (cfg.customNeg) sb.appendLine("双时间负打卡：${cfg.t1} / ${cfg.t2}")
        if (cfg.textMinWords > 0 && Method.TEXT.key in cfg.methods) sb.appendLine("文字最低字数：${cfg.textMinWords}")
        if (Method.LOCATION.key in cfg.methods) {
            sb.appendLine("位置负打卡：${if (cfg.locNegative) "是（离开范围有效）" else "否（范围内有效）"}")
            cfg.locPoints.forEach { sb.appendLine("  · ${it.name} ${formatLatLng(it.lat, it.lng)} 半径${it.radius}m") }
        }
        if (Method.STEPS.key in cfg.methods) sb.appendLine("目标步数：${cfg.stepTarget}")
        if (Method.TIMER.key in cfg.methods) sb.appendLine("倒计时：${cfg.timerMinutes} 分钟")
        if (Method.QRCODE.key in cfg.methods) sb.appendLine("专属二维码：${cfg.qrContent}")
        if (Method.NFC.key in cfg.methods) sb.appendLine("NFC 标签：${cfg.nfcTagId.ifBlank { "未绑定" }}")
        if (Method.VOICE.key in cfg.methods) sb.appendLine("语音最长：${cfg.voiceMaxSeconds} 秒")
        if (cfg.offset.enabled) sb.appendLine("抵消机制：模式${cfg.offset.mode}，每${cfg.offset.nDays}天得${cfg.offset.k}次，${if (cfg.offset.autoConsume) "自动消耗" else "手动消耗"}")
        sb.appendLine("修改策略：${if (item.editPolicy == "LOCKED") "不可修改" else "每${item.editInterval}天可改一次"}")
        sb.append("创建时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(item.createdAt))}")
        findViewById<TextView>(R.id.tv_detail).text = sb.toString()

        thread {
            val streak = CheckinEngine.streak(item, repo)
            val credits = repo.availableCredits(item.id)
            runOnUiThread {
                findViewById<TextView>(R.id.tv_sub).text =
                    "主题：${theme.name}    🔥连续${streak}天" + if (credits > 0) "    🛡️×$credits" else ""
            }
        }

        findViewById<Button>(R.id.btn_edit).setOnClickListener {
            val i = Intent(this, CreateItemActivity::class.java)
            i.putExtra(CreateItemActivity.EXTRA_ID, item.id)
            startActivity(i)
        }
    }

    override fun onResume() {
        super.onResume()
        // 编辑返回后刷新
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val repo = (application as CheckinApp).repository
        val item = repo.getItem(id) ?: return
        findViewById<TextView>(R.id.tv_name).text = item.name
    }

    companion object { const val EXTRA_ID = "extra_item_id" }
}
