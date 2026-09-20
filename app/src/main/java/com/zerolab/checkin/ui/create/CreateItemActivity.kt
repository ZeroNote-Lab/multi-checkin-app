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
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.LocatePoint
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.theme.ThemeManager
import com.zerolab.checkin.ui.scan.ScanActivity
import com.zerolab.checkin.util.DateUtils
import com.zerolab.checkin.util.formatLatLng
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import kotlin.concurrent.thread

class CreateItemActivity : AppCompatActivity() {

    private val repo get() = (application as CheckinApp).repository
    private var editId: Long = -1
    private var editing: CheckinItem? = null
    private val cfg = ItemConfig()
    private var selectedTheme = "sakura"

    private data class MethodRow(val switch: SwitchCompat, val panel: LinearLayout, val card: LinearLayout)
    private val rows = LinkedHashMap<String, MethodRow>()

    // 参数控件引用
    private var cbPhotoCamera: CheckBox? = null
    private var cbPhotoAlbum: CheckBox? = null
    private var etTextMin: EditText? = null
    private var cbTextNoRepeat: CheckBox? = null
    private var cbLocNeg: CheckBox? = null
    private var tvLocPoints: TextView? = null
    private var etSteps: EditText? = null
    private var etTimer: EditText? = null
    private var tvQr: TextView? = null
    private var ivQr: ImageView? = null
    private var tvNfc: TextView? = null
    private var etVoice: EditText? = null
    // v1.1.4：需在 lockRules 中一并置灰的控件引用
    private var btnLocFetch: Button? = null
    private var btnNfcBind: Button? = null
    private var btnLimitMinus: Button? = null
    private var btnLimitPlus: Button? = null
    private var qrBindBtn: Button? = null

    // v1.2.0 随心记模式：普通打卡 / 随心记 chip
    private var journalMode = false
    // v1.3.0 心情日记（日记 tab 内记录类型：false=随心记 true=心情日记）
    private var moodMode = false
    private var modeHint: TextView? = null
    private var comboNRow: LinearLayout? = null
    private var comboNLabel: TextView? = null
    private var comboNMinus: Button? = null
    private var comboNPlus: Button? = null
    private var comboRequired = 0   // 组合打卡：完成 N 个即完成（0=全部）
    // v1.2.0 时间打卡模式：倒计时 / 正计时 / 允许暂停
    private var rbTimerCountdown: RadioButton? = null
    private var rbTimerCountup: RadioButton? = null
    private var cbTimerPausable: CheckBox? = null
    // v1.2.0 扫码绑定现有二维码
    private val qrBindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val content = res.data?.getStringExtra("content")
            if (!content.isNullOrBlank()) {
                cfg.qrContent = content
                tvQr?.text = "已绑定现有二维码：\n${content.take(48)}${if (content.length > 48) "…" else ""}"
                ivQr?.setImageBitmap(makeQrBitmap(content))
                toast("二维码绑定成功 ✓")
            } else toast("未读取到二维码内容")
        } else toast("已取消扫码绑定")
    }

    // 真实 NFC 标签读取（enableReaderMode）
    private var nfcAdapter: NfcAdapter? = null
    private var nfcReader: NfcAdapter.ReaderCallback? = null

    private var dailyLimit = 1

    // v6.1.0 打卡日期配置
    private var scheduleMode = "DAILY"               // DAILY / WEEKDAYS / DOUBLE_REST / BIGSMALL
    private val weekDays = linkedSetOf<Int>()        // 1=周一 … 7=周日
    private var bigSmallStart = "BIG"                // BIG / SMALL
    private lateinit var weekDaysPanel: LinearLayout
    private lateinit var bigSmallPanel: LinearLayout
    private lateinit var scheduleHint: TextView
    private val weekDayChips = LinkedHashMap<Int, TextView>()

    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetchLocation() else toast("需要定位权限获取当前位置")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_item)
        editId = intent.getLongExtra(EXTRA_ID, -1)
        editing = if (editId > 0) repo.let { it.getItem(editId) } else null

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = if (editing != null) "编辑打卡项" else "新建打卡项"

        buildThemeChips()
        buildModeSection()   // v1.3.0 打卡类型双 tab（普通打卡 / 日记打卡），在方式开关之前构建
        buildMethodRows()
        buildScheduleSection()
        bindRuleControls()
        bindOffset()
        bindPolicy()
        bindJournalPanel()   // v1.3.0 日记 tab 记录类型单选 + 心情折线图开关

        if (editing != null) loadEditing() else {
            // 默认勾选普通
            rows[Method.NORMAL.key]?.switch?.isChecked = true
            refreshConflicts()
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
    }

    // ---------- 主题 ----------
    private fun buildThemeChips() {
        val container = findViewById<LinearLayout>(R.id.theme_container)
        ThemeManager.themes.forEach { t ->
            val tv = TextView(this)
            tv.text = " ${t.emoji} ${t.name} "
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER
            tv.setPadding(28, 18, 28, 18)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = 16
            tv.layoutParams = lp
            tv.setOnClickListener {
                selectedTheme = t.id
                ThemeManager.themes.forEachIndexed { i, _ -> container.getChildAt(i).invalidate() }
                renderThemeChips()
            }
            container.addView(tv)
        }
        renderThemeChips()
    }

    private fun renderThemeChips() {
        val container = findViewById<LinearLayout>(R.id.theme_container)
        ThemeManager.themes.forEachIndexed { i, t ->
            val tv = container.getChildAt(i) as TextView
            val selected = t.id == selectedTheme
            val bg = GradientDrawable()
            bg.cornerRadius = 24f
            // v6.1.0：取消主题色填充，选中态用深底浅字标识
            bg.setColor(if (selected) 0xFF3A4152.toInt() else 0xFFEEF1F6.toInt())
            bg.setStroke(if (selected) 0 else 2, 0xFFD6DBE6.toInt())
            tv.background = bg
            tv.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF4A5160.toInt())
            tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ---------- v1.3.0 打卡类型双 tab：普通打卡 / 日记打卡（v1.3.1 文件夹标签样式） ----------
    private fun buildModeSection() {
        modeHint = findViewById(R.id.tv_mode_hint)
        findViewById<View>(R.id.tab_normal).setOnClickListener { setJournalMode(false) }
        findViewById<View>(R.id.tab_journal).setOnClickListener { setJournalMode(true) }
        renderModeChips()
    }

    private fun renderModeChips() {
        val normalTab = findViewById<View>(R.id.tab_normal)
        val journalTab = findViewById<View>(R.id.tab_journal)
        val normalArrow = findViewById<View>(R.id.tab_normal_arrow)
        val journalArrow = findViewById<View>(R.id.tab_journal_arrow)
        val normalTv = findViewById<TextView>(R.id.tv_tab_normal)
        val journalTv = findViewById<TextView>(R.id.tv_tab_journal)

        normalTv.setBackgroundResource(if (!journalMode) R.drawable.bg_tab_selected else 0)
        normalArrow.visibility = View.GONE
        normalTv.setTextColor(if (!journalMode) 0xFFE5559B.toInt() else 0xFF8A90A0.toInt())
        normalTv.typeface = if (!journalMode) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        journalTv.setBackgroundResource(if (journalMode) R.drawable.bg_tab_selected else 0)
        journalArrow.visibility = View.GONE
        journalTv.setTextColor(if (journalMode) 0xFFE5559B.toInt() else 0xFF8A90A0.toInt())
        journalTv.typeface = if (journalMode) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        modeHint?.text = if (journalMode)
            "📔 日记打卡：日记式记录，只记成功、可多次记录，不记缺卡、不设排期 ο(=•ω＜=)ρ⌒☆"
            else "✅ 普通打卡：按规则打卡，有缺卡与连续天数。"
    }

    /** v1.3.0：日记 tab 下按记录类型/模式刷新区块与方式行可见性（纯视图，不改配置） */
    private fun refreshJournalVisibility() {
        val journal = journalMode
        findViewById<View>(R.id.tv_method_title).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_method_desc).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_rule_title).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.rule_card).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_schedule_title).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.schedule_container).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_offset_title).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.offset_card).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_policy_title).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.policy_card).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.journal_panel).visibility = if (journal) View.VISIBLE else View.GONE
        // 方式行：普通=全部显示；心情日记=全隐藏（无方式开关）；随心记=只留 PHOTO/TEXT/LOCATION/VOICE
        val forbidden = setOf(Method.NORMAL.key, Method.AUTO.key, Method.NFC.key, Method.STEPS.key, Method.TIMER.key, Method.QRCODE.key)
        rows.forEach { (k, row) ->
            row.card.visibility = when {
                !journal -> View.VISIBLE
                moodMode -> View.GONE
                k in forbidden -> View.GONE
                else -> View.VISIBLE
            }
        }
        findViewById<View>(R.id.cb_mood_chart).visibility = if (journal && moodMode) View.VISIBLE else View.GONE
        refreshComboNRow()
    }

    /** v1.3.0：日记 tab 记录类型单选（📔随心记 | 😊心情日记） */
    private fun setMoodMode(on: Boolean) {
        if (moodMode == on) return
        moodMode = on
        if (on) {
            // 心情日记：只保留 MOOD 一种"方式"，清空其余
            cfg.methods.clear()
            rows.values.forEach { it.switch.isChecked = false; it.panel.visibility = View.GONE }
            cfg.methods.add(Method.MOOD.key)
            dailyLimit = -1
            findViewById<TextView>(R.id.tv_limit).text = "不限"
        } else {
            // 回到随心记：移除 MOOD，恢复日记允许方式
            cfg.methods.remove(Method.MOOD.key)
            rows[Method.MOOD.key]?.switch?.isChecked = false
            rows[Method.MOOD.key]?.panel?.visibility = View.GONE
        }
        refreshJournalVisibility()
        refreshConflicts()
        refreshComboNRow()
    }

    private fun bindJournalPanel() {
        findViewById<RadioButton>(R.id.rb_journal_suixinsui).setOnClickListener { setMoodMode(false) }
        findViewById<RadioButton>(R.id.rb_journal_mood).setOnClickListener { setMoodMode(true) }
        // v1.3.0：折线图开关默认跟随 cfg.moodChart（默认开启），避免新建时 UI 与配置不一致
        findViewById<CheckBox>(R.id.cb_mood_chart).isChecked = cfg.moodChart
    }

    private fun setJournalMode(on: Boolean) {
        if (journalMode == on) return
        if (on) {
            // 切到日记：自动移除不允许的方式（NORMAL/AUTO/NFC/STEPS/TIMER/QRCODE）
            val forbidden = listOf(Method.NORMAL.key, Method.AUTO.key, Method.NFC.key, Method.STEPS.key, Method.TIMER.key, Method.QRCODE.key)
            forbidden.forEach { k ->
                if (k in cfg.methods) {
                    cfg.methods.remove(k)
                    rows[k]?.switch?.isChecked = false
                    rows[k]?.panel?.visibility = View.GONE
                }
            }
            // 固定时间段 / 负打卡 / 抵消机制一并关闭（日记不支持）
            findViewById<CompoundButton>(R.id.cb_negative).isChecked = false
            findViewById<CompoundButton>(R.id.cb_time_window).isChecked = false
            findViewById<View>(R.id.tw_panel).visibility = View.GONE
            findViewById<CheckBox>(R.id.cb_offset).isChecked = false
            findViewById<View>(R.id.offset_panel).visibility = View.GONE
            // 每日次数固定"不限"
            dailyLimit = -1
            findViewById<TextView>(R.id.tv_limit).text = "不限"
            comboRequired = 0
        } else {
            // 回到普通：恢复默认每日 1 次；日记记录类型复位随心记
            moodMode = false
            findViewById<RadioButton>(R.id.rb_journal_suixinsui).isChecked = true
            cfg.methods.remove(Method.MOOD.key)
            dailyLimit = 1
            findViewById<TextView>(R.id.tv_limit).text = "1"
        }
        journalMode = on
        renderModeChips()
        refreshJournalVisibility()
        refreshConflicts()
        refreshComboNRow()
    }

    /** v1.2.0 组合打卡：完成 N 个即完成（0=全部）。嵌在打卡方式卡片下，多选时显示 */
    private fun buildComboNRow() {
        val container = findViewById<LinearLayout>(R.id.method_container)
        comboNRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
            visibility = View.GONE
        }
        comboNLabel = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF6B7280.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        comboNMinus = Button(this).apply {
            text = "-"; textSize = 15f; setTextColor(0xFF1F2430.toInt())
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp)
        }
        comboNPlus = Button(this).apply {
            text = "+"; textSize = 15f; setTextColor(0xFF1F2430.toInt())
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(44.dp, 44.dp)
        }
        val count = TextView(this).apply {
            id = View.generateViewId(); textSize = 15f; setTextColor(0xFF1F2430.toInt())
            gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(56.dp, 44.dp)
            text = "全部"
        }
        // v1.2.2：组合完成数环形调节（1~total-1 显示数字，total 显示"全部"，双向循环不卡边界）
        // 内部 comboRequired：0=全部(total)，1~total-1=完成 N 项
        comboNMinus!!.setOnClickListener {
            val total = cfg.methods.filter { it != Method.AUTO.key }.size
            comboRequired = when {
                comboRequired == 0 -> (total - 1).coerceAtLeast(1)   // 全部 → total-1
                comboRequired <= 1 -> 0                                // 1 → 全部
                else -> comboRequired - 1
            }
            refreshComboNRow()
        }
        comboNPlus!!.setOnClickListener {
            val total = cfg.methods.filter { it != Method.AUTO.key }.size
            comboRequired = when {
                comboRequired == 0 -> 1                                // 全部 → 1
                comboRequired >= total - 1 -> 0                        // total-1 → 全部
                else -> comboRequired + 1
            }
            refreshComboNRow()
        }
        comboNRow!!.addView(comboNLabel)
        comboNRow!!.addView(comboNMinus)
        comboNRow!!.addView(count)
        comboNRow!!.addView(comboNPlus)
        container.addView(comboNRow, 0)
    }

    private fun refreshComboNRow() {
        val row = comboNRow ?: return
        val interactive = cfg.methods.filter { it != Method.AUTO.key }
        val n = interactive.size
        if (journalMode || n <= 1) { row.visibility = View.GONE; return }
        row.visibility = View.VISIBLE
        val cnt = (comboNRow?.getChildAt(2) as? TextView)
        val total = if (comboRequired == 0) "全部" else "$comboRequired"
        cnt?.text = total
        comboNLabel?.text = "组合打卡：完成 $total（共 $n 项）即视为完成"
        comboNMinus?.isEnabled = !locked
        comboNPlus?.isEnabled = !locked
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ---------- 方式开关行 ----------
    private fun buildMethodRows() {
        val container = findViewById<LinearLayout>(R.id.method_container)
        buildComboNRow()   // v1.2.0 组合完成数选择行（多选时显示）
        Method.values().forEach { m ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_card)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = 18
                layoutParams = lp
                setPadding(28, 8, 28, 20)
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply { text = "${m.emoji} ${m.label}"; textSize = 15f; setTextColor(0xFF1F2430.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val sw = SwitchCompat(this)
            head.addView(label); head.addView(sw)
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            buildMethodParam(m, panel)
            card.addView(head); card.addView(panel)
            sw.setOnCheckedChangeListener { _, on ->
                // v1.2.1：随心记禁用 NORMAL/AUTO/NFC/STEPS/TIMER/QRCODE，其余方式可正常开启
                val journalForbidden = setOf(Method.NORMAL.key, Method.AUTO.key, Method.NFC.key, Method.STEPS.key, Method.TIMER.key, Method.QRCODE.key)
                if (on && journalMode && m.key in journalForbidden) {
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("温馨提示：随心记暂不支持「${m.label}」哦 (｡•́︿•̀｡)")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (on && m.key == Method.AUTO.key && findViewById<CompoundButton>(R.id.cb_negative).isChecked) {
                    // v1.2.1：负打卡已开启时点自动打卡 → 互斥提示
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("温馨提示：负打卡与自动打卡互斥，无法同时开启 ο(=•ω＜=)ρ⌒☆")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (on && m.key == Method.STEPS.key && m.key !in cfg.methods) {
                    // v6.1.0：步数功能开发中，新建/新开启时固定关闭并提示
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("该功能还在开发 (ง •_•)ง")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (on && findViewById<CompoundButton>(R.id.cb_time_window).isChecked && m.key == Method.AUTO.key) {
                    // v1.1.7：固定时间段已开启时点自动打卡 → 互斥提示
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("温馨提示：固定时间段与自动打卡互斥，无法同时开启 ο(=•ω＜=)ρ⌒☆")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (on && m.key in Method.conflictsWith(cfg.methods)) {
                    // 尝试开启互斥方式：拒绝切换并提示
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("温馨提示：该方式与已选方式互斥，无法同时开启 ο(=•ω＜=)ρ⌒☆")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                panel.visibility = if (on) View.VISIBLE else View.GONE
                if (on) cfg.methods.add(m.key) else cfg.methods.remove(m.key)
                refreshConflicts()
            }
            container.addView(card)
            rows[m.key] = MethodRow(sw, panel, card)
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 16, 0, 6)
    }

    // ---------- 打卡日期（v6.1.0 新增） ----------
    private val scheduleModeChips = LinkedHashMap<String, TextView>()
    private lateinit var bigChip: TextView
    private lateinit var smallChip: TextView
    private lateinit var bsHint: TextView

    private fun scheduleChip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text; textSize = 13f; gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = 10
            layoutParams = lp
            setOnClickListener {
                // v1.2.0：随心记不设排期，点击弹提示
                if (journalMode) {
                    toast("温馨提示：随心记不设排期，暂不支持调整打卡日期哦 (｡•́︿•̀｡)")
                    return@setOnClickListener
                }
                onClick()
            }
            applyScheduleChipStyle(this, selected)
        }

    private fun applyScheduleChipStyle(tv: TextView, selected: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = 20f
        bg.setColor(if (selected) 0xFF3A4152.toInt() else 0xFFEEF1F6.toInt())
        tv.background = bg
        tv.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF4A5160.toInt())
        tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun buildScheduleSection() {
        val container = findViewById<LinearLayout>(R.id.schedule_container)
        // 模式选择行
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modes = listOf(
            "DAILY" to "每天",
            "WEEKDAYS" to "每周固定几天",
            "DOUBLE_REST" to "双休",
            "BIGSMALL" to "大小周"
        )
        modes.forEach { (mode, label) ->
            val tv = scheduleChip(label, mode == scheduleMode) {
                scheduleMode = mode
                scheduleModeChips.forEach { (k, v) -> applyScheduleChipStyle(v, k == mode) }
                renderSchedulePanels()
            }
            modeRow.addView(tv)
            scheduleModeChips[mode] = tv
        }
        container.addView(modeRow)

        scheduleHint = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 10, 0, 0)
        }
        container.addView(scheduleHint)

        // 每周固定几天：7 个圆形多选（选中绿色）
        weekDaysPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 2)
            visibility = View.GONE
        }
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { idx, label ->
            val day = idx + 1
            val tv = TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(0, (44 * resources.displayMetrics.density).toInt(), 1f)
                lp.marginEnd = 8
                layoutParams = lp
                setOnClickListener {
                    // v1.2.0：随心记不设排期
                    if (journalMode) {
                        toast("温馨提示：随心记不设排期，暂不支持选择星期哦 (｡•́︿•̀｡)")
                        return@setOnClickListener
                    }
                    if (day in weekDays) weekDays.remove(day) else weekDays.add(day)
                    renderWeekDayChips()
                }
            }
            weekDaysPanel.addView(tv)
            weekDayChips[day] = tv
        }
        container.addView(weekDaysPanel)
        renderWeekDayChips()

        // 大小周：选本周是大周还是小周（含解释）
        bigSmallPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        val bsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bigChip = scheduleChip("大周（单休）", bigSmallStart == "BIG") {
            bigSmallStart = "BIG"; renderBigSmallChips()
        }
        smallChip = scheduleChip("小周（双休）", bigSmallStart == "SMALL") {
            bigSmallStart = "SMALL"; renderBigSmallChips()
        }
        bsRow.addView(bigChip); bsRow.addView(smallChip)
        bigSmallPanel.addView(bsRow)
        bsHint = TextView(this).apply {
            textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 8, 0, 0)
        }
        bigSmallPanel.addView(bsHint)
        container.addView(bigSmallPanel)
        renderBigSmallChips()

        renderSchedulePanels()
    }

    private fun renderSchedulePanels() {
        scheduleHint.text = when (scheduleMode) {
            "WEEKDAYS" -> "仅选中的日期需要打卡，其余自动标记为「无需打卡」"
            "DOUBLE_REST" -> "周一至周五打卡，周六、周日休息（无需打卡）"
            "BIGSMALL" -> "大周=单休（周六也需打卡，周日休息）；小周=双休（周六、周日都休息），两种周交替"
            else -> "每天都需要打卡"
        }
        weekDaysPanel.visibility = if (scheduleMode == "WEEKDAYS") View.VISIBLE else View.GONE
        bigSmallPanel.visibility = if (scheduleMode == "BIGSMALL") View.VISIBLE else View.GONE
    }

    private fun renderWeekDayChips() {
        weekDayChips.forEach { (day, tv) ->
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
        applyScheduleChipStyle(bigChip, bigSmallStart == "BIG")
        applyScheduleChipStyle(smallChip, bigSmallStart == "SMALL")
        bsHint.text = if (bigSmallStart == "BIG")
            "当前周为大周：周六也需打卡，仅周日休息"
        else
            "当前周为小周：周六、周日都休息（下周六需打卡）"
    }

    private fun input(default: String, number: Boolean = false): EditText = EditText(this).apply {
        setText(default); textSize = 14f
        if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setTextColor(0xFF1F2430.toInt()); background = getDrawable(R.drawable.bg_input); setPadding(24, 18, 24, 18)
    }

    private fun buildMethodParam(m: Method, panel: LinearLayout) {
        when (m) {
            Method.NORMAL -> panel.addView(sectionLabel("点击打卡按钮即完成，无需额外材料。"))
            Method.PHOTO -> {
                cbPhotoCamera = CheckBox(this).apply {
                    text = "允许拍照"; isChecked = true; setTextColor(0xFF1F2430.toInt())
                    // v1.1.4：勾选状态实时同步到配置，保存校验才能读到真实值
                    setOnCheckedChangeListener { _, on -> cfg.photoFromCamera = on }
                }
                cbPhotoAlbum = CheckBox(this).apply {
                    text = "允许从相册选择"; isChecked = true; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> cfg.photoFromAlbum = on }
                }
                panel.addView(cbPhotoCamera); panel.addView(cbPhotoAlbum)
            }
            Method.TEXT -> {
                panel.addView(sectionLabel("最低字数"))
                etTextMin = input("1", true); panel.addView(etTextMin)
                cbTextNoRepeat = CheckBox(this).apply { text = "内容不可与上次重复"; setTextColor(0xFF1F2430.toInt()) }
                panel.addView(cbTextNoRepeat)
            }
            Method.LOCATION -> {
                cbLocNeg = CheckBox(this).apply { text = "位置负打卡：离开设定范围才算（如远离手机）"; setTextColor(0xFF1F2430.toInt()); textSize=13f }
                panel.addView(cbLocNeg)
                val addBtn = Button(this).apply {
                    text = "📍 获取当前位置作为标准点"; setTextColor(0xFFFFFFFF.toInt())
                    background?.setTint(0xFF39C5BB.toInt())
                    setOnClickListener { requestLocation() }
                }
                btnLocFetch = addBtn
                panel.addView(addBtn)
                tvLocPoints = TextView(this).apply { textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 10, 0, 0) }
                panel.addView(tvLocPoints)
            }
            Method.STEPS -> {
                panel.addView(sectionLabel("每日目标步数"))
                etSteps = input("5000", true); panel.addView(etSteps)
            }
            Method.TIMER -> {
                // v1.2.0：时间打卡（倒计时 / 正计时）+ 允许暂停保存续时
                panel.addView(sectionLabel("时长（分钟）"))
                etTimer = input("25", true); panel.addView(etTimer)
                panel.addView(sectionLabel("计时方式"))
                // v1.2.1：RadioButton 必须设唯一 id，否则 RadioGroup 单选失效（双选 bug）
                rbTimerCountdown = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = "倒计时：从设定时长倒数，时间到才能完成"; isChecked = true; textSize = 13f; setTextColor(0xFF1F2430.toInt())
                }
                rbTimerCountup = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = "正计时：从 0 开始计时，超过设定时长后才能完成"; textSize = 13f; setTextColor(0xFF1F2430.toInt())
                }
                val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
                rg.addView(rbTimerCountdown); rg.addView(rbTimerCountup)
                panel.addView(rg)
                cbTimerPausable = CheckBox(this).apply {
                    text = "允许暂停保存：随时暂停保存进度，下次打卡继续计时"; isChecked = false; textSize = 13f; setTextColor(0xFF1F2430.toInt())
                }
                panel.addView(cbTimerPausable)
                // v1.2.1：仅正计时支持暂停保存；倒计时不显示该勾选项
                cbTimerPausable?.visibility = View.GONE
                rg.setOnCheckedChangeListener { _, checkedId ->
                    val countUp = checkedId == rbTimerCountup?.id
                    cbTimerPausable?.visibility = if (countUp) View.VISIBLE else View.GONE
                    if (!countUp) cbTimerPausable?.isChecked = false
                }
            }
            Method.QRCODE -> {
                // v1.2.0：二维码打卡可扫现有二维码绑定，也可用专属二维码
                val bindBtn = Button(this).apply {
                    text = "📷 扫码绑定现有二维码"; setTextColor(0xFFFFFFFF.toInt())
                    background?.setTint(0xFF39C5BB.toInt())
                    setOnClickListener {
                        val i = Intent(this@CreateItemActivity, ScanActivity::class.java)
                        i.putExtra(ScanActivity.EXTRA_TITLE, "扫描要绑定的二维码")
                        qrBindLauncher.launch(i)
                    }
                }
                qrBindBtn = bindBtn
                panel.addView(bindBtn)
                cfg.qrContent = "uuid:" + java.util.UUID.randomUUID().toString()
                ivQr = ImageView(this).apply {
                    val px = (220 * resources.displayMetrics.density).toInt()
                    setImageBitmap(makeQrBitmap(cfg.qrContent))
                    layoutParams = LinearLayout.LayoutParams(px, px).apply { gravity = Gravity.CENTER_HORIZONTAL }
                    contentDescription = "专属二维码，长按保存到相册"
                    setOnLongClickListener {
                        makeQrBitmap(cfg.qrContent)?.let { saveQrToGallery(it, cfg.qrContent) }
                        true
                    }
                }
                panel.addView(ivQr)
                tvQr = sectionLabel("长按上方二维码可保存到相册，用于打印张贴。\n专属内容：${cfg.qrContent}")
                panel.addView(tvQr)
            }
            Method.NFC -> {
                val btn = Button(this).apply {
                    text = "📡 读取 NFC 标签并绑定"; setTextColor(0xFFFFFFFF.toInt())
                    background?.setTint(0xFF39C5BB.toInt())
                    setOnClickListener { bindNfcTag() }
                }
                btnNfcBind = btn
                panel.addView(btn)
                tvNfc = sectionLabel("尚未绑定标签"); panel.addView(tvNfc)
            }
            Method.VOICE -> {
                panel.addView(sectionLabel("最大录制时长（秒）"))
                etVoice = input("10", true); panel.addView(etVoice)
            }
            Method.MOOD -> panel.addView(sectionLabel("心情日记：打卡时选择 5 档心情，可附文字。"))
            Method.AUTO -> panel.addView(sectionLabel("App 回到前台/冷启动时自动完成，无需手动操作。"))
        }
    }

    /** 互斥置灰（v6.1.0：保持可点击，点击时在监听器里弹温馨提示） */
    private fun refreshConflicts() {
        val blocked = Method.conflictsWith(cfg.methods)
        val twOn = findViewById<CompoundButton>(R.id.cb_time_window).isChecked
        // v1.2.1：随心记额外禁用 NORMAL/AUTO/NFC/STEPS/TIMER/QRCODE
        val journalBlocked = if (journalMode)
            setOf(Method.NORMAL.key, Method.AUTO.key, Method.NFC.key, Method.STEPS.key, Method.TIMER.key, Method.QRCODE.key) else emptySet()
        // v1.2.1：负打卡与自动打卡互斥（双向置灰，点击时在监听器里弹提示）
        val negOn = findViewById<CompoundButton>(R.id.cb_negative).isChecked
        rows.forEach { (key, row) ->
            val isBlocked = key in blocked || key in journalBlocked
            val twBlock = twOn && key == Method.AUTO.key
            val negBlock = negOn && key == Method.AUTO.key
            row.switch.isEnabled = !locked // v1.1.7：保持可点击，点击时在监听器里弹互斥提示
            row.switch.alpha = if (isBlocked || twBlock || negBlock) 0.4f else 1f
            if (isBlocked && row.switch.isChecked) {
                row.switch.isChecked = false // 双保险
            }
            if (twBlock && row.switch.isChecked) {
                row.switch.isChecked = false // v1.1.6：时间段开启时自动打卡不可选
            }
            if (negBlock && row.switch.isChecked) {
                row.switch.isChecked = false // v1.2.1：负打卡开启时自动打卡不可选
            }
        }
        // v1.1.6 反向互斥：自动已选时固定时间段不可选
        val autoOn = Method.AUTO.key in cfg.methods
        findViewById<CompoundButton>(R.id.cb_time_window).isEnabled = !locked // v1.1.7：保持可点击，点击时在监听器里弹互斥提示
        if (autoOn) findViewById<CompoundButton>(R.id.cb_time_window).alpha = 0.4f
        else findViewById<CompoundButton>(R.id.cb_time_window).alpha = 1f
        // v1.1.4：组合打卡（多方式）每日次数固定为 1；v1.2.0 随心记固定"不限"；v1.2.2 自动打卡固定 1
        val multi = cfg.methods.count { it != Method.AUTO.key } > 1
        if (journalMode) {
            dailyLimit = -1
            findViewById<TextView>(R.id.tv_limit).text = "不限"
        } else if (multi || autoOn) {
            dailyLimit = 1
            findViewById<TextView>(R.id.tv_limit).text = "1"
        } else if (!locked) {
            btnLimitMinus?.isEnabled = true
            btnLimitPlus?.isEnabled = true
        }
        // v1.2.2：每日次数 +/− 在 随心记/组合/自动打卡 下视觉置灰（保持可点击，点击时在监听器里弹提示）
        val limitGrey = journalMode || multi || autoOn
        findViewById<Button>(R.id.btn_limit_minus).alpha = if (limitGrey) 0.4f else 1f
        findViewById<Button>(R.id.btn_limit_plus).alpha = if (limitGrey) 0.4f else 1f
        // v1.2.0：随心记下频率与规则保持可点击（点击时在监听器里弹提示），视觉置灰
        // v1.2.1：自动打卡已选时负打卡置灰（互斥）
        val ruleGrey = journalMode
        findViewById<CompoundButton>(R.id.cb_negative).isEnabled = !locked
        findViewById<CompoundButton>(R.id.cb_negative).alpha = if (ruleGrey || autoOn) 0.4f else 1f
        findViewById<CompoundButton>(R.id.cb_time_window).isEnabled = !locked
        findViewById<CompoundButton>(R.id.cb_time_window).alpha = if (ruleGrey || autoOn) 0.4f else 1f
        findViewById<Button>(R.id.btn_tw_start).isEnabled = !locked
        findViewById<Button>(R.id.btn_tw_end).isEnabled = !locked
        findViewById<CheckBox>(R.id.cb_offset).isEnabled = !locked
        findViewById<CheckBox>(R.id.cb_offset).alpha = if (ruleGrey) 0.4f else 1f
        findViewById<Button>(R.id.btn_limit_minus).isEnabled = !locked
        findViewById<Button>(R.id.btn_limit_plus).isEnabled = !locked
        findViewById<RadioButton>(R.id.rb_mode_a).isEnabled = !locked
        findViewById<RadioButton>(R.id.rb_mode_b).isEnabled = !locked
        findViewById<RadioButton>(R.id.rb_mode_c).isEnabled = !locked
        findViewById<EditText>(R.id.et_offset_n).isEnabled = !locked
        findViewById<EditText>(R.id.et_offset_k).isEnabled = !locked
        findViewById<CheckBox>(R.id.cb_offset_auto).isEnabled = !locked
        scheduleModeChips.values.forEach { it.isEnabled = !locked; it.alpha = if (ruleGrey) 0.4f else 1f }
        weekDayChips.values.forEach { it.isEnabled = !locked; it.alpha = if (ruleGrey) 0.4f else 1f }
        bigChip.isEnabled = !locked; bigChip.alpha = if (ruleGrey) 0.4f else 1f
        smallChip.isEnabled = !locked; smallChip.alpha = if (ruleGrey) 0.4f else 1f
        refreshComboNRow()
    }

    // ---------- 频率与规则 ----------
    private fun bindRuleControls() {
        val tvLimit = findViewById<TextView>(R.id.tv_limit)
        fun renderLimit() { tvLimit.text = if (dailyLimit < 0) "不限" else dailyLimit.toString() }
        btnLimitMinus = findViewById(R.id.btn_limit_minus)
        btnLimitPlus = findViewById(R.id.btn_limit_plus)
        btnLimitMinus!!.setOnClickListener {
            // v1.2.0：随心记每日次数固定"不限"
            if (journalMode) { toast("温馨提示：随心记每日不限次数，无需设置哦 (｡•́︿•̀｡)"); return@setOnClickListener }
            // v1.2.2：自动打卡每日固定 1 次
            if (Method.AUTO.key in cfg.methods) { toast("温馨提示：自动打卡每日固定 1 次，无需设置哦"); return@setOnClickListener }
            dailyLimit = when { dailyLimit == -1 -> 1; dailyLimit <= 1 -> -1; else -> dailyLimit - 1 }; renderLimit()
        }
        btnLimitPlus!!.setOnClickListener {
            if (journalMode) { toast("温馨提示：随心记每日不限次数，无需设置哦 (｡•́︿•̀｡)"); return@setOnClickListener }
            if (Method.AUTO.key in cfg.methods) { toast("温馨提示：自动打卡每日固定 1 次，无需设置哦"); return@setOnClickListener }
            dailyLimit = if (dailyLimit == -1) 1 else dailyLimit + 1; renderLimit()
        }
        val cbNeg = findViewById<CompoundButton>(R.id.cb_negative)
        val cbTw = findViewById<CompoundButton>(R.id.cb_time_window)
        // v1.2.1（bug4）：独立 RadioButton 点击已选中项默认不取消。
        // performClick 先 toggle 再回调 onClickListener：点击前未选中会触发 OnCheckedChangeListener
        // （JustToggled=true，表示刚选中，不取消）；点击前已选中不触发（JustToggled=false，手动取消）。
        var negJustToggled = false
        var twJustToggled = false
        // v1.1.6：负打卡 / 固定时间段打卡 互斥（圆形单选）；双时间自定义负打卡 v1.1.8 起移除
        cbNeg.setOnCheckedChangeListener { _, on ->
            negJustToggled = true
            if (on) {
                if (journalMode) {  // v1.2.0：随心记不支持负打卡
                    cbNeg.isChecked = false
                    toast("温馨提示：随心记不记录失败，暂不支持负打卡哦 (｡•́︿•̀｡)")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (Method.AUTO.key in cfg.methods) {  // v1.2.1：负打卡与自动打卡互斥
                    cbNeg.isChecked = false
                    toast("温馨提示：负打卡与自动打卡互斥，无法同时开启 ο(=•ω＜=)ρ⌒☆")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                cbTw.isChecked = false
                hideTimerAdvancedOptions()   // v1.2.0：负打卡开启时隐藏正计时/暂停选项（保持原倒计时）
            } else {
                showTimerAdvancedOptions()   // v1.2.1：取消负打卡后恢复正计时/暂停选项
            }
            refreshConflicts()
        }
        // v1.2.1（bug4）：已选中再点击 → 取消选中
        cbNeg.setOnClickListener {
            if (cbNeg.isChecked && !negJustToggled) cbNeg.isChecked = false
            negJustToggled = false
        }
        cbTw.setOnCheckedChangeListener { _, on ->
            twJustToggled = true
            if (on && journalMode) {  // v1.2.0：随心记不支持固定时间段
                cbTw.isChecked = false
                toast("温馨提示：随心记不设排期，暂不支持固定时间段哦 (｡•́︿•̀｡)")
                refreshConflicts()
                return@setOnCheckedChangeListener
            }
            if (on && Method.AUTO.key in cfg.methods) {
                // v1.1.7：自动打卡已开启时点固定时间段 → 互斥提示
                cbTw.isChecked = false
                toast("温馨提示：固定时间段与自动打卡互斥，无法同时开启 ο(=•ω＜=)ρ⌒☆")
                refreshConflicts()
                return@setOnCheckedChangeListener
            }
            if (on) { cbNeg.isChecked = false }
            findViewById<View>(R.id.tw_panel).visibility = if (on) View.VISIBLE else View.GONE
            refreshConflicts()
        }
        // v1.2.1（bug4）：已选中再点击 → 取消选中
        cbTw.setOnClickListener {
            if (cbTw.isChecked && !twJustToggled) cbTw.isChecked = false
            twJustToggled = false
        }
        findViewById<Button>(R.id.btn_tw_start).setOnClickListener { pickTwTime(findViewById(R.id.btn_tw_start), true) }
        findViewById<Button>(R.id.btn_tw_end).setOnClickListener { pickTwTime(findViewById(R.id.btn_tw_end), false) }
        findViewById<CheckBox>(R.id.cb_offset).setOnClickListener {  // v1.2.0：随心记不支持抵消机制
            if (journalMode && findViewById<CheckBox>(R.id.cb_offset).isChecked) {
                findViewById<CheckBox>(R.id.cb_offset).isChecked = false
                toast("温馨提示：随心记暂不支持抵消机制哦 (｡•́︿•̀｡)")
            }
        }
    }

    /** v1.2.0：负打卡开启时，时间打卡只保留倒计时（隐藏正计时/暂停选项） */
    private fun hideTimerAdvancedOptions() {
        rbTimerCountup?.isChecked = false
        rbTimerCountdown?.isChecked = true
        cbTimerPausable?.isChecked = false
        rbTimerCountup?.visibility = View.GONE
        cbTimerPausable?.visibility = View.GONE
    }

    /** v1.2.1：取消负打卡后恢复正计时/暂停选项（暂停仅正计时时显示） */
    private fun showTimerAdvancedOptions() {
        rbTimerCountup?.visibility = View.VISIBLE
        cbTimerPausable?.visibility = if (rbTimerCountup?.isChecked == true) View.VISIBLE else View.GONE
    }

    /** v1.1.6 固定时间段起止时间选择 */
    private fun pickTwTime(btn: Button, isStart: Boolean) {
        val cur = btn.text.toString().split(":")
        val h = cur[0].toInt(); val mi = cur[1].toInt()
        TimePickerDialog(this, { _, hour, minute ->
            btn.text = "%02d:%02d".format(hour, minute)
        }, h, mi, true).show()
    }
    // ---------- 抵消 ----------
    private fun bindOffset() {
        val cb = findViewById<CheckBox>(R.id.cb_offset)
        val panel = findViewById<View>(R.id.offset_panel)
        cb.setOnCheckedChangeListener { _, on -> panel.visibility = if (on) View.VISIBLE else View.GONE }
    }
    // ---------- 修改策略 ----------
    private fun bindPolicy() {
        findViewById<android.widget.RadioButton>(R.id.rb_locked).setOnClickListener {
            findViewById<View>(R.id.interval_panel).visibility = View.GONE
        }
        findViewById<android.widget.RadioButton>(R.id.rb_interval).setOnClickListener {
            findViewById<View>(R.id.interval_panel).visibility = View.VISIBLE
        }
    }

    // ---------- 位置取点 ----------
    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            fetchLocation() else locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loading = android.app.AlertDialog.Builder(this).setMessage("正在获取当前位置…").setCancelable(false).show()
        val best = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val providers = try {
            lm.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
                .ifEmpty { lm.getProviders(true) }
        } catch (_: Exception) { emptyList() }
        // 先取缓存
        for (p in providers) {
            try { val l = lm.getLastKnownLocation(p) ?: continue; if (best.get() == null || l.accuracy < (best.get()?.accuracy ?: 9999f)) best.set(l) } catch (_: Exception) {}
        }
        // 主动监听一次更新（真机/模拟器 geo fix 都会立即回调）
        // v1.1.5：始终等待实时回调，避免误用缓存旧点（此前缓存有值直接返回旧坐标）
        val latch = java.util.concurrent.CountDownLatch(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { best.set(l); latch.countDown() }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("deprecated") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        }
        for (p in providers) { try { lm.requestLocationUpdates(p, 0L, 0f, listener, mainLooper) } catch (_: Exception) {} }
        thread {
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            runOnUiThread {
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
                loading.dismiss()
                val l = best.get()
                if (l == null) { toast("定位失败，请到空旷处重试"); return@runOnUiThread }
                val etR = EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setText("200"); textSize = 14f; setPadding(24, 18, 24, 18)
                    background = getDrawable(R.drawable.bg_input); setTextColor(0xFF1F2430.toInt())
                }
                // v6.1.0：输入框与上方说明文字对齐，不再占满弹窗
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(52, 4, 52, 0)
                }
                box.addView(etR)
                android.app.AlertDialog.Builder(this)
                    .setTitle("确认作为标准位置？")
                    .setMessage("位置：${formatLatLng(l.latitude, l.longitude)}\n允许半径（米，50-5000，默认200）")
                    .setView(box)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("使用该位置") { _, _ ->
                        val r = (etR.text.toString().toIntOrNull() ?: 200).coerceIn(50, 5000)
                        cfg.locPoints.add(LocatePoint("位置${cfg.locPoints.size + 1}", l.latitude, l.longitude, r))
                        tvLocPoints?.text = "已设定 ${cfg.locPoints.size} 个标准点：\n" +
                            cfg.locPoints.joinToString("\n") { "· ${it.name} ${formatLatLng(it.lat, it.lng)} 半径${it.radius}m" }
                    }.show()

            }
        }
    }
    // ---------- 编辑加载 ----------
    private fun loadEditing() {
        val it = editing ?: return
        val loaded = ItemConfig.parse(it.configJson)
        // 拷贝到工作 cfg
        cfg.methods.clear(); cfg.methods.addAll(loaded.methods)
        cfg.dailyLimit = loaded.dailyLimit; cfg.negative = loaded.negative
        cfg.offsetBackfill = loaded.offsetBackfill
        cfg.timeWindowEnabled = loaded.timeWindowEnabled; cfg.twStart = loaded.twStart; cfg.twEnd = loaded.twEnd
        cfg.photoFromCamera = loaded.photoFromCamera; cfg.photoFromAlbum = loaded.photoFromAlbum
        cfg.textMinWords = loaded.textMinWords; cfg.textNoRepeat = loaded.textNoRepeat
        cfg.locNegative = loaded.locNegative; cfg.locPoints.clear(); cfg.locPoints.addAll(loaded.locPoints)
        cfg.stepTarget = loaded.stepTarget; cfg.timerMinutes = loaded.timerMinutes
        cfg.qrContent = loaded.qrContent.ifBlank { "uuid:" + java.util.UUID.randomUUID() }
        cfg.nfcTagId = loaded.nfcTagId; cfg.voiceMaxSeconds = loaded.voiceMaxSeconds
        cfg.offset = loaded.offset
        // v6.1.0 日期配置回显
        scheduleMode = loaded.scheduleMode
        weekDays.clear(); weekDays.addAll(loaded.weekDays)
        if (weekDays.isEmpty()) weekDays.addAll(1..5) // 兜底：WEEKDAYS 模式默认周一~五
        bigSmallStart = loaded.bigSmallStart
        // v1.2.0 新字段回显：随心记 / 组合完成数 / 时间打卡模式
        journalMode = loaded.journalMode
        // v1.3.0 心情日记回显
        moodMode = loaded.moodMode
        if (loaded.moodMode) findViewById<RadioButton>(R.id.rb_journal_mood).isChecked = true
        else findViewById<RadioButton>(R.id.rb_journal_suixinsui).isChecked = true
        findViewById<CheckBox>(R.id.cb_mood_chart).isChecked = loaded.moodChart
        comboRequired = loaded.comboRequired
        if (loaded.timerMode == "COUNTUP") {
            rbTimerCountup?.isChecked = true
            rbTimerCountdown?.isChecked = false
        } else {
            rbTimerCountdown?.isChecked = true
            rbTimerCountup?.isChecked = false
        }
        cbTimerPausable?.isChecked = loaded.timerPausable
        // 负打卡开启时隐藏正计时/暂停选项（保持原倒计时）
        if (loaded.negative) hideTimerAdvancedOptions()

        findViewById<EditText>(R.id.et_name).setText(it.name)
        selectedTheme = it.theme
        dailyLimit = cfg.dailyLimit
        findViewById<TextView>(R.id.tv_limit).text = if (dailyLimit < 0) "不限" else dailyLimit.toString()
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

        // 方式开关回显
        rows.forEach { (key, row) ->
            val on = key in cfg.methods
            row.switch.isChecked = on
            row.panel.visibility = if (on) View.VISIBLE else View.GONE
        }
        fillParamUi()
        tvLocPoints?.text = if (cfg.locPoints.isEmpty()) "" else
            "已设定 ${cfg.locPoints.size} 个标准点：\n" + cfg.locPoints.joinToString("\n") { p -> "· ${p.name} ${formatLatLng(p.lat, p.lng)} 半径${p.radius}m" }
        tvQr?.text = "长按上方二维码可保存到相册，用于打印张贴。\n专属内容：${cfg.qrContent}"
        ivQr?.setImageBitmap(makeQrBitmap(cfg.qrContent))
        tvNfc?.text = if (cfg.nfcTagId.isBlank()) "尚未绑定标签" else "已绑定标签：${cfg.nfcTagId}"

        // v1.3.0：日记项不设修改策略（始终可修改），即使历史 LOCKED 也放行
        val isDiary = cfg.journalMode || cfg.moodMode
        // 不可修改策略：规则整体锁定（bug8：之前 LOCKED 仍可改，现在强制生效）
        if (it.editPolicy == "LOCKED" && !isDiary) {
            lockRules("规则已锁定（创建后不可修改），仅可修改名称/主题")
        }
        // INTERVAL_N 锁定期：仅名称/主题可改，其余规则禁用
        if (it.editPolicy == "INTERVAL_N" && !isDiary && it.lastEditDate != null &&
            DateUtils.daysSince(it.lastEditDate) < (it.editInterval ?: 0)) {
            lockRules("距上次修改不足 ${it.editInterval} 天，规则暂不可改")
        }
        // 自动方式核心规则锁定
        if (Method.AUTO.key in cfg.methods) lockRules("自动打卡类型创建后核心规则不可改")
        // v6.1.0 日期区块回显
        scheduleModeChips.forEach { (k, v) -> applyScheduleChipStyle(v, k == scheduleMode) }
        renderSchedulePanels()
        renderWeekDayChips()
        renderBigSmallChips()
        renderThemeChips()
        // v1.2.0：随心记模式回显 + 禁用态
        renderModeChips()
        refreshJournalVisibility()   // v1.3.0 双 tab 可见性
        refreshConflicts()
        refreshComboNRow()
    }

    private var locked = false
    private fun lockRules(msg: String) {
        locked = true
        toast(msg)
        rows.values.forEach { it.switch.isEnabled = false }
        findViewById<CompoundButton>(R.id.cb_negative).isEnabled = false
        findViewById<CompoundButton>(R.id.cb_time_window).isEnabled = false
        findViewById<Button>(R.id.btn_tw_start).isEnabled = false
        findViewById<Button>(R.id.btn_tw_end).isEnabled = false
        findViewById<CheckBox>(R.id.cb_offset).isEnabled = false
        findViewById<Button>(R.id.btn_limit_minus).isEnabled = false
        findViewById<Button>(R.id.btn_limit_plus).isEnabled = false
        findViewById<RadioButton>(R.id.rb_mode_a).isEnabled = false
        findViewById<RadioButton>(R.id.rb_mode_b).isEnabled = false
        findViewById<RadioButton>(R.id.rb_mode_c).isEnabled = false
        findViewById<EditText>(R.id.et_offset_n).isEnabled = false
        findViewById<EditText>(R.id.et_offset_k).isEnabled = false
        findViewById<CheckBox>(R.id.cb_offset_auto).isEnabled = false
        // v1.1.4：补齐全部规则控件锁定（位置/NFC 读取按钮、图片来源、参数输入、排班）
        cbPhotoCamera?.isEnabled = false
        cbPhotoAlbum?.isEnabled = false
        etTextMin?.isEnabled = false
        cbTextNoRepeat?.isEnabled = false
        cbLocNeg?.isEnabled = false
        etSteps?.isEnabled = false
        etTimer?.isEnabled = false
        etVoice?.isEnabled = false
        btnLocFetch?.isEnabled = false
        btnNfcBind?.isEnabled = false
        scheduleModeChips.values.forEach { it.isEnabled = false; it.alpha = 0.4f }
        weekDayChips.values.forEach { it.isEnabled = false; it.alpha = 0.4f }
        bigChip.isEnabled = false; bigChip.alpha = 0.4f
        smallChip.isEnabled = false; smallChip.alpha = 0.4f
        // v1.2.0：模式切换、组合完成数、时间打卡选项、扫码绑定一并锁定
        findViewById<View>(R.id.tab_normal).isEnabled = false; findViewById<View>(R.id.tab_journal).isEnabled = false
        comboNMinus?.isEnabled = false
        comboNPlus?.isEnabled = false
        rbTimerCountdown?.isEnabled = false
        rbTimerCountup?.isEnabled = false
        cbTimerPausable?.isEnabled = false
        qrBindBtn?.isEnabled = false
    }

    private fun fillParamUi() {
        cbPhotoCamera?.isChecked = cfg.photoFromCamera
        cbPhotoAlbum?.isChecked = cfg.photoFromAlbum
        etTextMin?.setText(cfg.textMinWords.toString())
        cbTextNoRepeat?.isChecked = cfg.textNoRepeat
        cbLocNeg?.isChecked = cfg.locNegative
        etSteps?.setText(cfg.stepTarget.toString())
        etTimer?.setText(cfg.timerMinutes.toString())
        etVoice?.setText(cfg.voiceMaxSeconds.toString())
    }

    private fun collectConfigFromUi() {
        cfg.dailyLimit = dailyLimit
        cfg.negative = findViewById<CompoundButton>(R.id.cb_negative).isChecked
        cfg.timeWindowEnabled = findViewById<CompoundButton>(R.id.cb_time_window).isChecked
        cfg.twStart = findViewById<Button>(R.id.btn_tw_start).text.toString()
        cfg.twEnd = findViewById<Button>(R.id.btn_tw_end).text.toString()
        cbPhotoCamera?.let { cfg.photoFromCamera = it.isChecked }
        cbPhotoAlbum?.let { cfg.photoFromAlbum = it.isChecked }
        etTextMin?.let { cfg.textMinWords = (it.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99) }  // v1.3.0：最低字数 1~99
        cbTextNoRepeat?.let { cfg.textNoRepeat = it.isChecked }
        cbLocNeg?.let { cfg.locNegative = it.isChecked }
        etSteps?.let { cfg.stepTarget = it.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 5000 }
        etTimer?.let { cfg.timerMinutes = it.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 25 }
        etVoice?.let { cfg.voiceMaxSeconds = it.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10 }
        val offOn = findViewById<CheckBox>(R.id.cb_offset).isChecked
        cfg.offset.enabled = offOn
        cfg.offset.mode = when (findViewById<RadioGroup>(R.id.rg_offset_mode).checkedRadioButtonId) {
            R.id.rb_mode_b -> "B"; R.id.rb_mode_c -> "C"; else -> "A"
        }
        cfg.offset.nDays = findViewById<EditText>(R.id.et_offset_n).text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 3
        cfg.offset.k = findViewById<EditText>(R.id.et_offset_k).text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        cfg.offset.autoConsume = findViewById<CheckBox>(R.id.cb_offset_auto).isChecked
        // v6.1.0 日期配置收集
        cfg.scheduleMode = scheduleMode
        cfg.weekDays.clear(); cfg.weekDays.addAll(weekDays)
        cfg.bigSmallStart = bigSmallStart
        // v1.2.0 新字段收集
        cfg.journalMode = journalMode
        // v1.3.0 心情日记字段收集（心情折线图开关随时可改）
        cfg.moodMode = moodMode
        cfg.moodChart = findViewById<CheckBox>(R.id.cb_mood_chart).isChecked
        // v1.2.2：完成数越界兜底（历史脏数据/异常值归一为 0=全部），组合项 ≤1 时恒为全部
        val comboTotal = cfg.methods.filter { it != Method.AUTO.key }.size
        cfg.comboRequired = if (comboTotal <= 1) 0 else if (comboRequired !in 1 until comboTotal) 0 else comboRequired
        cfg.timerMode = if (rbTimerCountup?.isChecked == true) "COUNTUP" else "COUNTDOWN"
        // v1.2.1：仅正计时支持暂停保存，倒计时一律 false（防脏配置）
        cfg.timerPausable = if (rbTimerCountup?.isChecked == true) (cbTimerPausable?.isChecked ?: false) else false
    }

    /** 生成二维码位图（ZXing，600x600） */
    private fun makeQrBitmap(content: String): Bitmap? = try {
        val size = 600
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size)
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        bmp
    } catch (e: Exception) { null }

    /** 长按二维码保存到相册（API 29+ 走 MediaStore 无权限；API 24-28 走 insertImage） */
    private fun saveQrToGallery(bmp: Bitmap, content: String) {
        try {
            val name = "checkin_qr_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/打卡APP")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    toast("二维码已保存到相册（Pictures/打卡APP）")
                } else toast("保存失败")
            } else {
                val url = MediaStore.Images.Media.insertImage(contentResolver, bmp, name, content)
                toast(if (url != null) "二维码已保存到相册" else "保存失败")
            }
        } catch (e: Exception) { toast("保存失败：${e.message}") }
    }

    private fun save() {
        val name = findViewById<EditText>(R.id.et_name).text.toString().trim()
        if (name.isBlank()) { toast("请填写打卡名称"); return }
        if (!journalMode && scheduleMode == "WEEKDAYS" && weekDays.isEmpty()) { toast("「每周固定几天」至少选择一天"); return }
        // v1.1.4：先收集 UI 值再校验（此前校验读的是未同步的旧配置，导致"全取消也能保存"）
        collectConfigFromUi()
        // v1.3.0：心情日记强制规则（只保留 MOOD，隐含日记语义）
        if (cfg.moodMode) {
            cfg.methods.clear(); cfg.methods.add(Method.MOOD.key)
            cfg.journalMode = true
        }
        if (cfg.methods.isEmpty()) { toast("请至少打开一种打卡方式"); return }
        if (!Method.isValid(cfg.methods)) { toast("所选方式存在互斥冲突"); return }
        // v1.2.0：随心记强制规则（兜底，防脏配置）
        if (cfg.journalMode) {
            cfg.negative = false
            cfg.timeWindowEnabled = false
            cfg.offset.enabled = false
            cfg.offset.autoConsume = false
            cfg.dailyLimit = -1
            cfg.methods.remove(Method.NORMAL.key)
            cfg.methods.remove(Method.AUTO.key)
            cfg.methods.remove(Method.NFC.key)
            cfg.methods.remove(Method.STEPS.key)
            cfg.methods.remove(Method.TIMER.key)
            if (cfg.methods.isEmpty()) { toast("随心记需至少选择一种记录方式（如文字/图片）"); return }
        }
        // 组合打卡每日次数固定为 1（UI 已置灰，此处兜底）
        if (cfg.methods.count { it != Method.AUTO.key } > 1) { dailyLimit = 1; cfg.dailyLimit = 1 }
        if (Method.LOCATION.key in cfg.methods && cfg.locPoints.isEmpty()) { toast("位置打卡需先获取一个标准位置"); return }
        if (Method.PHOTO.key in cfg.methods && !cfg.photoFromCamera && !cfg.photoFromAlbum) { toast("拍照打卡需至少勾选一种图片来源"); return }
        if (Method.NFC.key in cfg.methods && cfg.nfcTagId.isBlank()) { toast("NFC打卡需先绑定 NFC 标签"); return }
        if (cfg.timeWindowEnabled) {
            val a = DateUtils.parseHHmm(cfg.twStart); val b = DateUtils.parseHHmm(cfg.twEnd)
            if (a < 0 || b < 0 || a >= b) { toast("时间段开始需早于结束（如 05:00 / 08:30），暂不支持跨天"); return }
        }

        val policyInterval = findViewById<RadioButton>(R.id.rb_interval).isChecked
        val intervalN = findViewById<EditText>(R.id.et_interval).text.toString().toIntOrNull()
        // v1.3.0：日记项不设修改策略（FLEX=可随时修改）
        val isDiary = cfg.journalMode || cfg.moodMode
        val editPolicy = if (isDiary) "FLEX" else if (policyInterval) "INTERVAL_N" else "LOCKED"
        val editInterval = if (!isDiary && policyInterval) (intervalN ?: 7) else null
        val now = System.currentTimeMillis()
        thread {
            if (editing != null) {
                val old = editing!!
                val updated = old.copy(
                    name = name, theme = selectedTheme,
                    type = ItemConfig.mainType(cfg.methods), configJson = cfg.toJson(),
                    editPolicy = editPolicy,
                    editInterval = editInterval,
                    lastEditDate = if (!locked || old.lastEditDate == null) DateUtils.today() else old.lastEditDate,
                    updatedAt = now
                )
                repo.updateItem(updated)
            } else {
                val item = CheckinItem(
                    name = name, type = ItemConfig.mainType(cfg.methods), configJson = cfg.toJson(),
                    icon = "default", theme = selectedTheme, sortOrder = repo.itemCount(),
                    editPolicy = editPolicy,
                    editInterval = editInterval,
                    lastEditDate = DateUtils.today(), createdAt = now, updatedAt = now
                )
                repo.insertItem(item)
            }
            runOnUiThread { finish() }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- 真实 NFC 标签读取绑定 ----------
    private fun bindNfcTag() {
        val nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc == null) {
            toast("此设备不支持 NFC，无法绑定标签")
            return
        }
        if (!nfc.isEnabled) {
            toast("系统 NFC 已关闭，请先在系统设置中开启")
            return
        }
        nfcAdapter = nfc
        tvNfc?.text = "请将 NFC 标签贴近手机背面…"
        nfcReader = NfcAdapter.ReaderCallback { tag ->
            runOnUiThread {
                val id = tag.id.joinToString("") { "%02X".format(it) }
                cfg.nfcTagId = id
                tvNfc?.text = "已绑定标签：$id"
                toast("已读取 NFC 标签并绑定")
            }
        }
        nfc.enableReaderMode(
            this, nfcReader!!,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        try { nfcAdapter?.disableReaderMode(this) } catch (_: Exception) {}
    }

    companion object { const val EXTRA_ID = "extra_item_id" }
}
