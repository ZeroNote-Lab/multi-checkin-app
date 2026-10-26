package com.zerolab.checkin.ui.create

import android.Manifest
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.theme.ThemeUi
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.engine.CreateInput
import com.zerolab.checkin.engine.CreatePlan
import com.zerolab.checkin.engine.CreatePlanner
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.LocatePoint
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.theme.ThemeManager
import com.zerolab.checkin.ui.scan.ScanActivity
import com.zerolab.checkin.ui.detail.ItemCheckinActivity
import com.zerolab.checkin.util.DateUtils
import com.zerolab.checkin.util.formatLatLng
import com.zerolab.checkin.ui.settings.AdminMode
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import kotlin.concurrent.thread

/**
 * v1.4.2 重构：创建页「编辑回显」从 CreateItemActivity 抽出（约 150 行 UI 绑定）。
 *
 * 说明：这是过渡性拆分——先按职责把页面代码移出 Activity；
 * 下一步可把 loaded config → 表单字段的映射抽成纯数据对象（CreateFormState）并做单测。
 */
internal fun CreateItemActivity.loadForm() {
        val it = editing ?: return
        val loaded = ItemConfig.parse(it.configJson)
        // 拷贝到工作 cfg
        cfg.methods.clear(); cfg.methods.addAll(loaded.methods)
        cfg.dailyLimit = loaded.dailyLimit; cfg.negative = loaded.negative
        cfg.dailyAllRequired = loaded.dailyAllRequired // v1.4.0：编辑回显"全部完成"开关
        cfg.timeWindowEnabled = loaded.timeWindowEnabled; cfg.twStart = loaded.twStart; cfg.twEnd = loaded.twEnd
        cfg.photoFromCamera = loaded.photoFromCamera; cfg.photoFromAlbum = loaded.photoFromAlbum
        cfg.textMinWords = loaded.textMinWords; cfg.textNoRepeat = loaded.textNoRepeat
        cfg.locNegative = loaded.locNegative; cfg.locPoints.clear(); cfg.locPoints.addAll(loaded.locPoints)
        cfg.stepTarget = loaded.stepTarget; cfg.timerMinutes = loaded.timerMinutes
        cfg.qrContent = loaded.qrContent.ifBlank { "uuid:" + java.util.UUID.randomUUID() }
        cfg.nfcTagId = loaded.nfcTagId; cfg.voiceMaxSeconds = loaded.voiceMaxSeconds
        cfg.offset = loaded.offset
        // v6.1.0 日期配置回显
        schedule.mode = loaded.scheduleMode
        schedule.weekDays.clear(); schedule.weekDays.addAll(loaded.weekDays)
        if (schedule.weekDays.isEmpty()) schedule.weekDays.addAll(1..5) // 兜底：WEEKDAYS 模式默认周一~五
        schedule.bigSmallStart = loaded.bigSmallStart
        // v1.2.0 新字段回显：随心记 / 组合完成数 / 时间打卡模式
        journalMode = loaded.journalMode
        // v1.3.0 心情日记回显；v1.3.3：显式同时设置两个 RadioButton，避免 XML 默认 checked 残留
        moodMode = loaded.moodMode
        findViewById<RadioButton>(R.id.rb_journal_suixinsui).isChecked = !loaded.moodMode
        findViewById<RadioButton>(R.id.rb_journal_mood).isChecked = loaded.moodMode
        findViewById<CheckBox>(R.id.cb_mood_chart).isChecked = loaded.moodChart
        comboRequired = loaded.comboRequired
        // v1.3.19：弹窗化后控件不再常驻，配置直接回写 cfg（弹窗打开时读取展示）
        cfg.timerMode = loaded.timerMode
        cfg.timerPausable = loaded.timerPausable
        cfg.timerForce = loaded.timerForce
        if (loaded.timerMode == "COUNTUP") {
            rbTimerCountup?.isChecked = true
            rbTimerCountdown?.isChecked = false
        } else {
            rbTimerCountdown?.isChecked = true
            rbTimerCountup?.isChecked = false
        }
        cbTimerPausable?.isChecked = loaded.timerPausable
        // v1.3.6：强制模式回显；开启时隐藏暂停保存（互斥）
        cbTimerForce?.isChecked = loaded.timerForce
        if (loaded.timerForce) {
            cbTimerPausable?.isChecked = false
            cbTimerPausable?.visibility = View.GONE
        }
        // 负打卡开启时隐藏正计时/暂停选项（保持原倒计时）
        if (loaded.negative) hideTimerAdvancedOptions()

        // v1.3.12 时间分割回显
        findViewById<CompoundButton>(R.id.cb_day_cutoff).isChecked = loaded.dayCutoff > 0
        findViewById<View>(R.id.cutoff_panel).visibility = if (loaded.dayCutoff > 0) View.VISIBLE else View.GONE
        if (loaded.dayCutoff > 0) {
            findViewById<Button>(R.id.btn_day_cutoff).text = "%02d:%02d".format(loaded.dayCutoff / 60, loaded.dayCutoff % 60)
        }
        findViewById<EditText>(R.id.et_name).setText(it.name)
        selectedTheme = it.theme
        iconPicker.selected = if (com.zerolab.checkin.theme.IconManager.isKnown(it.icon)) it.icon else null
        iconPicker.expanded = false
        // v1.3.13 打卡组 / N天打卡 回显
        if (loaded.groupMode) {
            groupMode = true
            applySpecialModeVisibility()
            groupEditor.subs.clear()
            loaded.groupMembers.forEachIndexed { i, mid ->
                val m = repo.getItem(mid)
                val nm = m?.name ?: loaded.groupMemberNames.getOrNull(i) ?: "子项"
                // v1.3.15：子项携带完整 ItemConfig 回显（后续点击行可直接编辑全部规则）
                // v1.3.29：回显子项图标（isKnown 校验，未知保持 null → default）
                val mc = m?.let { ItemConfig.parse(it.configJson) } ?: ItemConfig()
                if (mc.methods.isEmpty()) mc.methods.add(Method.NORMAL.key)
                groupEditor.subs.add(GroupMemberEditor.GroupSub(mid, nm, mc, m?.icon?.let { k -> if (com.zerolab.checkin.theme.IconManager.isKnown(k)) k else null }))
            }
            renderGroupMembersSafe()
        }
        if (loaded.ndaysMode) {
            ndaysMode = true
            applySpecialModeVisibility()
            ndaysTarget = loaded.ndaysTarget.coerceAtLeast(1)
            findViewById<TextView>(R.id.tv_ndays_target).text = "$ndaysTarget 天"
            ndaysMethods.clear()
            val nms = loaded.methods.filter { it != Method.MOOD.key && it != Method.AUTO.key }.toMutableList()
            if (nms.isEmpty()) nms.add(Method.NORMAL.key)
            ndaysMethods.addAll(nms)
            buildNdaysMethods()
        }
        dailyLimit = cfg.dailyLimit
        findViewById<TextView>(R.id.tv_limit).text = if (dailyLimit < 0) "不限" else dailyLimit.toString()
        findViewById<CheckBox>(R.id.cb_all_required).isChecked = cfg.dailyAllRequired   // v1.4.0：每日全部完成开关回显
        findViewById<CompoundButton>(R.id.cb_negative).isChecked = cfg.negative
        findViewById<CompoundButton>(R.id.cb_time_window).isChecked = cfg.timeWindowEnabled
        findViewById<View>(R.id.tw_panel).visibility = if (cfg.timeWindowEnabled) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_tw_start).text = cfg.twStart
        findViewById<Button>(R.id.btn_tw_end).text = cfg.twEnd
        findViewById<CheckBox>(R.id.cb_offset).isChecked = cfg.offset.enabled
        findViewById<View>(R.id.offset_panel).visibility = if (cfg.offset.enabled) View.VISIBLE else View.GONE
        findViewById<EditText>(R.id.et_offset_n).setText(cfg.offset.nDays.toString())
        findViewById<EditText>(R.id.et_offset_k).setText(cfg.offset.k.toString())
        findViewById<CheckBox>(R.id.cb_offset_auto).isChecked = cfg.offset.autoConsume
        when (cfg.offset.mode) {
            "B" -> findViewById<RadioButton>(R.id.rb_mode_b).isChecked = true
            "C" -> findViewById<RadioButton>(R.id.rb_mode_c).isChecked = true
            else -> findViewById<RadioButton>(R.id.rb_mode_a).isChecked = true
        }
        // 修改策略不可改，按已有值展示并锁定
        findViewById<RadioButton>(R.id.rb_locked).isChecked = it.editPolicy == "LOCKED"
        findViewById<RadioButton>(R.id.rb_interval).isChecked = it.editPolicy == "INTERVAL_N"
        findViewById<View>(R.id.interval_panel).visibility = if (it.editPolicy == "INTERVAL_N") View.VISIBLE else View.GONE
        findViewById<RadioButton>(R.id.rb_locked).isEnabled = false
        findViewById<RadioButton>(R.id.rb_interval).isEnabled = false
        findViewById<EditText>(R.id.et_interval).setText((it.editInterval ?: 7).toString())

        // v1.3.11：编辑回显前先懒加载构建所有已开启方式的参数面板
        cfg.methods.forEach { k -> Method.of(k)?.let { ensurePanel(it) } }
        // 方式开关回显
        rows.forEach { (key, row) ->
            val on = key in cfg.methods
            row.switch.isChecked = on
            row.panel.visibility = if (on && !journalMode) View.VISIBLE else View.GONE
            row.gear?.visibility = if (on && !journalMode && methodPanelBuilder.hasConfig(Method.of(key) ?: Method.NORMAL)) View.VISIBLE else View.GONE
        }
        fillParamUi()
        tvLocPoints?.text = if (cfg.locPoints.isEmpty()) "" else
            "已设定 ${cfg.locPoints.size} 个标准点：\n" + cfg.locPoints.joinToString("\n") { p -> "· ${p.name} ${formatLatLng(p.lat, p.lng)} 半径${p.radius}m" }
        tvQr?.text = "长按上方二维码可保存到相册，用于打印张贴。\n专属内容：${cfg.qrContent}"
        ivQr?.setImageBitmap(com.zerolab.checkin.util.QrCode.bitmap(cfg.qrContent))
        tvNfc?.text = if (cfg.nfcTagId.isBlank()) "尚未绑定标签" else "已绑定标签：${cfg.nfcTagId}"

        // v1.3.0：日记项不设修改策略（始终可修改），即使历史 LOCKED 也放行
        val isDiary = cfg.journalMode || cfg.moodMode
        // v1.3.17：超级管理员模式放行所有锁定（改内容不改 editPolicy，退出后仍按原策略）
        // 不可修改策略：规则整体锁定（bug8：之前 LOCKED 仍可改，现在强制生效）
        if (it.editPolicy == "LOCKED" && !isDiary && !AdminMode.isOn) {
            lockRules("规则已锁定（创建后不可修改），仅可修改名称/主题")
        }
        // INTERVAL_N 锁定期：仅名称/主题可改，其余规则禁用
        if (it.editPolicy == "INTERVAL_N" && !isDiary && !AdminMode.isOn && it.lastEditDate != null &&
            DateUtils.daysSince(it.lastEditDate) < (it.editInterval ?: 0)) {
            lockRules("距上次修改不足 ${it.editInterval} 天，规则暂不可改")
        }
        // 自动方式核心规则锁定
        if (Method.AUTO.key in cfg.methods && !AdminMode.isOn) lockRules("自动打卡类型创建后核心规则不可改")
        // v6.1.0 日期区块回显
        schedule.render()
        iconPicker.themeId = selectedTheme; iconPicker.render()
        // v1.2.0：随心记模式回显 + 禁用态
        renderModeChips()
        refreshJournalVisibility()   // v1.3.0 双 tab 可见性
        refreshConflicts()
        refreshComboNRow()
        // v1.3.3：编辑已有日记项时，记录类型（随心记/心情日记）锁定不可切换；方式开关仍可改
        if (journalMode) {
            findViewById<RadioButton>(R.id.rb_journal_suixinsui).isEnabled = false
            findViewById<RadioButton>(R.id.rb_journal_mood).isEnabled = false
        }
    }
