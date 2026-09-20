package com.zerolab.checkin.ui.detail

import android.content.Intent
import android.os.Bundle
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
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "打卡项详情"
        build()
    }

    override fun onResume() {
        super.onResume()
        // 编辑返回后全量刷新（v1.2.0：修复编辑保存后卡片不更新的 bug）
        build()
    }

    /** 按数据库最新数据重建整页 */
    private fun build() {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val repo = (application as CheckinApp).repository
        val item = repo.getItem(id)
        if (item == null) { finish(); return }

        findViewById<TextView>(R.id.tv_name).text = item.name
        val cfg = ItemConfig.parse(item.configJson)
        val theme = ThemeManager.of(item.theme)
        findViewById<TextView>(R.id.tv_emoji).text = theme.emoji
        findViewById<TextView>(R.id.tv_sub).text =
            "主题：${theme.name}    状态：${if (item.isActive == 1) "启用中" else "已暂停"}"

        val sb = StringBuilder()
        // v1.2.0：随心记模式展示；v1.3.0：心情日记
        if (cfg.moodMode) sb.appendLine("打卡模式：😊 心情日记（5 档心情 + 可选文字，只记记录、不记缺卡）")
        else if (cfg.journalMode) sb.appendLine("打卡模式：📔 随心记（只记成功、可多次记录、不记缺卡）")
        val methodNames = cfg.methods.mapNotNull { Method.of(it)?.label }
        sb.appendLine("打卡方式：${methodNames.joinToString(" + ")}")
        sb.appendLine("每日次数：${if (cfg.journalMode || cfg.dailyLimit < 0) "不限" else cfg.dailyLimit}")
        // v1.2.0：组合打卡完成数
        val interactive = cfg.methods.filter { it != Method.AUTO.key }
        if (!cfg.journalMode && interactive.size > 1 && cfg.comboRequired in 1..interactive.size)
            sb.appendLine("完成规则：完成 ${cfg.comboRequired}/${interactive.size} 项即完成")
        if (!cfg.journalMode) sb.appendLine("负打卡（状态反转）：${if (cfg.negative) "是" else "否"}")
        // v1.1.8：双时间自定义负打卡已移除（老数据 customNeg 不再展示）
        if (cfg.textMinWords > 0 && Method.TEXT.key in cfg.methods) sb.appendLine("文字最低字数：${cfg.textMinWords}")
        if (Method.LOCATION.key in cfg.methods) {
            sb.appendLine("位置负打卡：${if (cfg.locNegative) "是（离开范围有效）" else "否（范围内有效）"}")
            cfg.locPoints.forEach { sb.appendLine("  · ${it.name} ${formatLatLng(it.lat, it.lng)} 半径${it.radius}m") }
        }
        if (Method.STEPS.key in cfg.methods) sb.appendLine("目标步数：${cfg.stepTarget}")
        if (Method.TIMER.key in cfg.methods) sb.appendLine(
            // v1.2.0：时间打卡（倒计时/正计时）+ 允许暂停
            "时间打卡：${if (cfg.timerMode == "COUNTUP") "正计时" else "倒计时"} ${cfg.timerMinutes} 分钟" +
                if (cfg.timerPausable) "（可暂停保存续时）" else "")
        if (Method.QRCODE.key in cfg.methods) sb.appendLine("专属二维码：${cfg.qrContent}")
        if (Method.NFC.key in cfg.methods) sb.appendLine("NFC 标签：${cfg.nfcTagId.ifBlank { "未绑定" }}")
        if (Method.VOICE.key in cfg.methods) sb.appendLine("语音最长：${cfg.voiceMaxSeconds} 秒")
        if (cfg.offset.enabled) sb.appendLine("抵消机制：模式${cfg.offset.mode}，每${cfg.offset.nDays}天得${cfg.offset.k}次，${if (cfg.offset.autoConsume) "自动消耗" else "手动消耗"}")
        // v1.3.0：FLEX=日记项可随时修改
        sb.appendLine("修改策略：${when (item.editPolicy) { "LOCKED" -> "不可修改"; "INTERVAL_N" -> "每${item.editInterval}天可改一次"; else -> "可随时修改" }}")
        sb.append("创建时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(item.createdAt))}")
        findViewById<TextView>(R.id.tv_detail).text = sb.toString()

        thread {
            // v1.2.0：随心记显示"记录天数"，普通模式保持连续天数；v1.3.0：心情日记同
            val days = if (cfg.journalMode) CheckinEngine.recordDays(item, repo)
                else CheckinEngine.streak(item, repo)
            val credits = repo.availableCredits(item.id)
            val prefix = if (cfg.moodMode) "😊已记录" else if (cfg.journalMode) "📔已记录" else "🔥连续"
            runOnUiThread {
                findViewById<TextView>(R.id.tv_sub).text =
                    "主题：${theme.name}    $prefix${days}天" + if (credits > 0 && !cfg.journalMode) "    🛡️×$credits" else ""
            }
        }

        findViewById<Button>(R.id.btn_edit).setOnClickListener {
            val i = Intent(this, CreateItemActivity::class.java)
            i.putExtra(CreateItemActivity.EXTRA_ID, item.id)
            startActivity(i)
        }
    }

    companion object { const val EXTRA_ID = "extra_item_id" }
}
