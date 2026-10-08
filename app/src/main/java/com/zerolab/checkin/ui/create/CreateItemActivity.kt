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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.theme.ThemeUi
import com.zerolab.checkin.data.entity.CheckinItem
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

class CreateItemActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "extra_item_id"
        /** 新建入口模式：从类型选择页进入时指定，编辑已有项忽略（由配置回显） */
        const val EXTRA_MODE = "create_mode"
        const val MODE_NORMAL = "normal"             // ✅ 普通打卡
        const val MODE_JOURNAL = "journal"           // 📔 日记打卡（页内选随心记 / 心情日记）
        const val MODE_GROUP = "group"               // 👥 打卡组（v1.3.13）
        const val MODE_NDAYS = "ndays"               // 🏁 N天打卡（v1.3.13）
        const val MODE_JOURNAL_FREE = "journal_free" // 兼容保留：直接进随心记
        const val MODE_JOURNAL_MOOD = "journal_mood" // 兼容保留：直接进心情日记
        /** v1.3.23：新建成功后回传给类型选择页的标记（选择页收到后一并退出，返回直达首页打卡选择页） */
        const val EXTRA_CREATED = "created"
    }

    private val repo get() = (application as CheckinApp).repository
    private var editId: Long = -1
    private var editing: CheckinItem? = null
    private val cfg = ItemConfig()
    private var selectedTheme = "sakura"
    // v1.3.18：图标选择（独立于配色主题）；null=未选（编辑旧数据时无高亮）
    private var selectedIcon: String? = null
    private var iconExpanded = false

    private data class MethodRow(val switch: SwitchCompat, val panel: LinearLayout, val card: LinearLayout, val gear: TextView? = null, var built: Boolean = false)
    private val rows = LinkedHashMap<String, MethodRow>()
    // v1.3.3：日记 tab 把 PHOTO/TEXT/VOICE 的 card 动态移到记录类型之间，记录它们在普通 method_container 里的原 index
    private val journalMethodOrigIndex = HashMap<String, Int>()
    private val journalMethods = setOf(Method.PHOTO.key, Method.TEXT.key, Method.VOICE.key, Method.LOCATION.key)

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
    // v1.3.22 自定义打卡「更多选项」折叠：false=只显示 名称/图标/方式；true=展开 频率/日期/抵消/锁定
    private var moreExpanded = false
    // v1.3.0 心情日记（日记 tab 内记录类型：false=随心记 true=心情日记）
    private var moodMode = false
    // v1.3.13 打卡组 / N天打卡 模式状态
    private var groupMode = false
    private var ndaysMode = false
    private var ndaysTarget = 21
    // v1.3.14 打卡组子项：仅支持新增（id 非空=已入库子项，编辑时更新而非新建）
    // v1.3.15：子项携带完整 ItemConfig（每日次数/负打卡/时间规则/抵消/方式参数/自动打卡，弹层更多区编辑）
    private data class GroupSub(var id: Long?, var name: String, val config: ItemConfig)
    private val groupSubs = mutableListOf<GroupSub>()
    // v1.3.14 N天打卡：新建时可选打卡方式（多选组合）
    private val ndaysMethods = linkedSetOf(Method.NORMAL.key)
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
    // v1.3.6 时间打卡强制模式：离开本页（非熄屏）本次计时作废
    private var cbTimerForce: CheckBox? = null
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
        val createMode = intent.getStringExtra(EXTRA_MODE)
        findViewById<TextView>(R.id.tv_title).text = when {
            editing != null -> "编辑打卡项"
            createMode == MODE_JOURNAL -> "新建日记打卡"
            createMode == MODE_JOURNAL_FREE -> "新建随心记"
            createMode == MODE_JOURNAL_MOOD -> "新建心情日记"
            createMode == MODE_GROUP -> "新建打卡组"
            createMode == MODE_NDAYS -> "新建习惯打卡"
            else -> "新建打卡项"
        }
        // 类型已在选择页（或编辑项配置）确定：隐藏打卡类型双 tab
        findViewById<View>(R.id.mode_container).visibility = View.GONE

        // 记录类型单选：普通新建隐藏；日记新建 / 编辑日记项显示（编辑时锁定，见 loadEditing）
        val showRecordType = editing != null && ItemConfig.parse(editing!!.configJson).journalMode ||
            (editing == null && (createMode == MODE_JOURNAL || createMode == MODE_JOURNAL_FREE || createMode == MODE_JOURNAL_MOOD))
        findViewById<View>(R.id.rb_journal_suixinsui).visibility = if (showRecordType) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rb_journal_mood).visibility = if (showRecordType) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_journal_type_label).visibility = if (showRecordType) View.VISIBLE else View.GONE

        buildThemeChips()
        // v1.3.11：同步构建；方式行外壳轻量构建 + 参数面板懒加载，保证页面快速且一次性完整出现
        buildModeSection()   // v1.3.0 打卡类型双 tab（普通打卡 / 日记打卡），在方式开关之前构建
        buildMethodRows()
        // v1.3.3：记录日记方式在普通列表里的原 index
        val mc = findViewById<LinearLayout>(R.id.method_container)
        journalMethods.forEach { k -> journalMethodOrigIndex[k] = mc.indexOfChild(rows[k]!!.card) }
        buildScheduleSection()
        bindRuleControls()
        bindOffset()
        bindPolicy()
        bindJournalPanel()   // v1.3.0 日记 tab 记录类型单选 + 心情折线图开关

        moreExpanded = editing != null   // v1.3.22：编辑默认展开高级卡，新建默认折叠
        if (editing != null) {
            loadEditing()
        } else {
            // v1.3.9：新建按类型选择页传入的模式初始化（普通 / 日记）
            when (createMode) {
                MODE_JOURNAL, MODE_JOURNAL_FREE -> setJournalMode(true)
                MODE_JOURNAL_MOOD -> { setJournalMode(true); setMoodMode(true) }
                MODE_GROUP -> {
                    groupMode = true
                    applySpecialModeVisibility()
                }
                MODE_NDAYS -> {
                    ndaysMode = true
                    applySpecialModeVisibility()
                }
                else -> {
                    // 默认勾选普通
                    rows[Method.NORMAL.key]?.switch?.isChecked = true
                    refreshConflicts()
                }
            }
        }
        bindSpecialModeControls()
        bindMoreButton()   // v1.3.22：自定义打卡「更多选项」折叠
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        // v1.3.14：全局主题换肤（根背景 / 保存按钮）
        ThemeUi.apply(this, findViewById(R.id.create_root), listOf(R.id.btn_save))
    }

    // ---------- v1.3.13 打卡组 / N天打卡 ----------
    private fun applySpecialModeVisibility() {
        if (!groupMode && !ndaysMode) return
        findViewById<View>(R.id.journal_panel).visibility = View.GONE
        findViewById<View>(R.id.method_card).visibility = View.GONE
        // v1.3.15：打卡组点「更多选项」展开打卡日期（schedule_card 初始收起）；N天保持隐藏
        findViewById<View>(R.id.schedule_card).visibility = View.GONE
        findViewById<View>(R.id.rule_card).visibility = View.GONE
        findViewById<View>(R.id.offset_card).visibility = View.GONE
        findViewById<View>(R.id.tv_policy_title).visibility = View.GONE
        findViewById<View>(R.id.policy_card).visibility = View.GONE
        findViewById<View>(R.id.ndays_card).visibility = if (ndaysMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.group_card).visibility = if (groupMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_group_more).visibility = if (groupMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_more).visibility = View.GONE   // v1.3.22：组/N天用不到主页面更多
        if (groupMode) renderGroupMembers()
    }

    private fun bindSpecialModeControls() {
        findViewById<Button>(R.id.btn_ndays_minus).setOnClickListener {
            ndaysTarget = (ndaysTarget - 1).coerceAtLeast(1)
            findViewById<TextView>(R.id.tv_ndays_target).text = "$ndaysTarget 天"
        }
        findViewById<Button>(R.id.btn_ndays_plus).setOnClickListener {
            ndaysTarget = (ndaysTarget + 1).coerceAtMost(999)
            findViewById<TextView>(R.id.tv_ndays_target).text = "$ndaysTarget 天"
        }
        findViewById<View>(R.id.btn_group_add_new).setOnClickListener { showGroupSubSheet(null) }
        // v1.3.16：更多选项（卡片外左下角链接）→ 展开/收起打卡日期卡
        findViewById<View>(R.id.btn_group_more).setOnClickListener {
            val card = findViewById<View>(R.id.schedule_card)
            val showing = card.visibility == View.VISIBLE
            card.visibility = if (showing) View.GONE else View.VISIBLE
            findViewById<TextView>(R.id.btn_group_more).text = if (showing) "更多选项" else "收起选项"
        }
        buildNdaysMethods()
    }

    private fun renderGroupMembers() {
        val list = findViewById<LinearLayout>(R.id.group_member_list)
        list.removeAllViews()
        groupSubs.forEachIndexed { i, s -> list.addView(groupMemberRow(s, i)) }
    }

    /** v1.3.14 子项卡片行：主题色图标底 + 名称 + 方式标签 + 删除 */
    /** v1.3.15：点击整行返回编辑（不用删掉重新创建）；规则摘要并入标签行 */
    private fun groupMemberRow(s: GroupSub, idx: Int): View {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        val theme = ThemeManager.of(selectedTheme)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_input)
            setPadding(10, 10, 8, 10)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 6
            layoutParams = lp
            // v1.3.15：点击已添加子项行 → 返回修改（无需删除重建）
            setOnClickListener { showGroupSubSheet(s) }
        }
        val iconWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(theme.soft) }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        }
        iconWrap.addView(TextView(this).apply { text = com.zerolab.checkin.theme.IconManager.emojiFor(selectedIcon, selectedTheme); textSize = 17f })
        row.addView(iconWrap)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(10)
            layoutParams = lp
        }
        col.addView(TextView(this).apply {
            text = s.name
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F2430.toInt())
        })
        col.addView(TextView(this).apply {
            // v1.3.15：方式标签 + 规则摘要（每日次数/负打卡/时间窗口等）
            val c = s.config
            val base = methodLabel(c.methods)
            val extra = mutableListOf<String>()
            if (c.dailyLimit > 1) extra.add("每日${c.dailyLimit}次")
            if (c.negative) extra.add("负打卡")
            if (c.timeWindowEnabled) extra.add("时段")
            if (c.dayCutoff >= 0) extra.add("时间分割")
            if (c.offset.enabled) extra.add("🛡️抵消")
            if (Method.AUTO.key in c.methods) extra.add("自动")
            text = if (extra.isEmpty()) base else "$base   ·  ${extra.joinToString(" / ")}"
            textSize = 11f
            setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = " ✕"
            textSize = 14f
            setTextColor(0xFFB0534C.toInt())
            setPadding(16, 0, 6, 0)
            setOnClickListener { groupSubs.removeAt(idx); renderGroupMembers() }
        })
        return row
    }

    private fun methodLabel(methods: Set<String>): String =
        methods.mapNotNull { Method.of(it) }.joinToString("  ") { "${it.emoji} ${it.label}" }

    /** v1.3.14 新增/编辑子项底部弹层：名称 + 打卡方式多选（互斥置灰） */
    /** v1.3.15：方式区下方加「更多选项」展开普通打卡除日期外的全部功能；步数方式禁用 */
    private fun showGroupSubSheet(s: GroupSub?) {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        // v1.3.16：禁 BottomSheet 拖拽手势，滚动完全交给内部 ScrollView（修复拉到底再回拉顶部空白消失）
        try {
            sheet.behavior.isDraggable = false
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        } catch (_: Exception) {}
        // v1.3.15：编辑已有子项直接操作 s.config；新建用局部 ItemConfig，确定时写入 groupSubs
        val cfgSub = s?.config ?: ItemConfig()
        val cur = cfgSub.methods
        if (cur.isEmpty()) cur.add(Method.NORMAL.key)
        // v1.3.15：弹层内容超高（更多选项展开）时需可滚动，外层套 ScrollView
        val scroll = ScrollView(this).apply { isFillViewport = false }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        wrap.addView(TextView(this).apply {
            text = if (s == null) "新增打卡项" else "编辑打卡项"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F2430.toInt())
        })
        val et = EditText(this).apply {
            hint = "子项名称，如：喝水"
            textSize = 14f
            setPadding(24, 18, 24, 18)
            background = getDrawable(R.drawable.bg_input)
            setTextColor(0xFF1F2430.toInt())
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
            if (s != null) setText(s.name)
        }
        et.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        (et.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        wrap.addView(et)
        wrap.addView(TextView(this).apply {
            text = "打卡方式"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(14), 0, dp(8))
        })
        val methodBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val candidates = listOf(Method.NORMAL, Method.PHOTO, Method.TEXT, Method.VOICE, Method.LOCATION, Method.STEPS, Method.TIMER, Method.QRCODE, Method.NFC)
        // v1.3.23：竖排方式行（对齐自定义打卡布局，无⚙）；勾选可配置方式立即弹配置弹窗
        buildSwitchMethodRows(methodBox, candidates, cur, cfgSub) {
            chipsClickListenerRefresh?.invoke()
        }
        wrap.addView(methodBox)
        // v1.3.15：更多选项入口（展开高级功能）
        val moreBtn = TextView(this).apply {
            text = "更多选项 ▾"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(10), 0, dp(2))
        }
        wrap.addView(moreBtn)
        val moreBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        buildGroupMoreSection(moreBox, cfgSub, cur)
        wrap.addView(moreBox)
        moreBtn.setOnClickListener {
            val showing = moreBox.visibility == View.VISIBLE
            moreBox.visibility = if (showing) View.GONE else View.VISIBLE
            moreBtn.text = if (showing) "更多选项 ▾" else "收起 ▴"
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        btnRow.addView(TextView(this).apply {
            text = "取消"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { sheet.dismiss() }
        })
        btnRow.addView(TextView(this).apply {
            text = "确定"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val nm = et.text.toString().trim()
                if (nm.isBlank()) { toast("子项名称不能为空"); return@setOnClickListener }
                if (groupSubs.any { it !== s && it.name == nm }) { toast("组内已有同名子项"); return@setOnClickListener }
                if (Method.LOCATION.key in cfgSub.methods && cfgSub.locPoints.isEmpty()) { toast("位置打卡需先配置一个标准位置"); return@setOnClickListener }
                if (Method.NFC.key in cfgSub.methods && cfgSub.nfcTagId.isBlank()) { toast("NFC打卡需先绑定 NFC 标签"); return@setOnClickListener }
                if (Method.QRCODE.key in cfgSub.methods && cfgSub.qrContent.isBlank()) { toast("扫码打卡需先生成专属二维码"); return@setOnClickListener }
                if (Method.PHOTO.key in cfgSub.methods && !cfgSub.photoFromCamera && !cfgSub.photoFromAlbum) { toast("拍照打卡需至少勾选一种图片来源"); return@setOnClickListener }
                if (s == null) groupSubs.add(GroupSub(null, nm, cfgSub)) else s.name = nm
                renderGroupMembers()
                sheet.dismiss()
            }
        })
        wrap.addView(btnRow)
        scroll.addView(wrap)
        sheet.setContentView(scroll)
        sheet.show()
    }

    /** v1.3.15：子项弹层更多区——普通打卡除「日期选择」外的全部功能（每日次数/负打卡/时间规则/抵消/自动/方式参数） */
    private fun buildGroupMoreSection(box: LinearLayout, c: ItemConfig, cur: MutableSet<String>) {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        // v1.3.16：更多区可用性刷新（对齐普通打卡页：负打卡/自动打卡锁次数，次数<2 禁用全部完成）
        var refreshMoreConflicts: () -> Unit = {}
        fun divider() { box.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(6) }
            background = getDrawable(R.color.divider)
        }) }
        fun sectionTitle(t: String) { box.addView(TextView(this).apply {
            text = t; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(10), 0, dp(4))
        }) }
        fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
            val cb = CheckBox(this).apply {
                text = label; textSize = 13f; setTextColor(0xFF1F2430.toInt())
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
            box.addView(cb)
            return cb
        }
        divider()
        // —— 频率 ——
        sectionTitle("频率与规则")
        val limitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        limitRow.addView(TextView(this).apply {
            text = "每日打卡次数"; textSize = 13f; setTextColor(0xFF1F2430.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvLimit = TextView(this).apply {
            text = "${c.dailyLimit}"; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F2430.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val btnLimitMinus = Button(this).apply {
            text = "-"; textSize = 14f; setPadding(0, 0, 0, 0)
            setOnClickListener { c.dailyLimit = (c.dailyLimit - 1).coerceAtLeast(1); tvLimit.text = "${c.dailyLimit}"; refreshMoreConflicts() }
        }
        limitRow.addView(btnLimitMinus)
        limitRow.addView(tvLimit)
        val btnLimitPlus = Button(this).apply {
            text = "+"; textSize = 14f; setPadding(0, 0, 0, 0)
            setOnClickListener { c.dailyLimit = (c.dailyLimit + 1).coerceAtMost(99); tvLimit.text = "${c.dailyLimit}"; refreshMoreConflicts() }
        }
        limitRow.addView(btnLimitPlus)
        box.addView(limitRow)
        // v1.3.16：全部完成开关——对齐普通打卡页可用性（次数>=2 且 单方式 且 非负打卡 且 非自动打卡），否则整行置灰
        val cbAll = CheckBox(this).apply {
            text = "需完成全部次数才算打卡成功"
            textSize = 13f; setTextColor(0xFF1F2430.toInt())
            isChecked = c.dailyAllRequired
            setOnCheckedChangeListener { _, on -> c.dailyAllRequired = on }
        }
        box.addView(cbAll)
        divider()
        // —— 负打卡 ——
        toggleRow("负打卡：无操作=成功，主动记录=破戒失败", c.negative) { on ->
            c.negative = on
            if (on && Method.AUTO.key in c.methods) {
                c.methods.remove(Method.AUTO.key); cur.remove(Method.AUTO.key)
                toast("负打卡与自动打卡互斥，已关闭自动")
            }
            refreshMoreConflicts()
        }
        divider()
        // —— 时间规则（先建两个面板与开关，再统一绑定互斥监听，避免前向引用） ——
        sectionTitle("时间规则（二选一）")
        val twPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = if (c.timeWindowEnabled) View.VISIBLE else View.GONE
            setPadding(dp(12), 0, 0, 0)
        }
        twPanel.addView(TextView(this).apply { text = "开始 "; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        twPanel.addView(Button(this).apply {
            text = c.twStart; textSize = 13f
            setOnClickListener {
                val hm = c.twStart.split(":")
                TimePickerDialog(this@CreateItemActivity, { _, h, m ->
                    c.twStart = "%02d:%02d".format(h, m); (this as Button).text = c.twStart
                }, hm[0].toInt(), hm[1].toInt(), true).show()
            }
        })
        twPanel.addView(TextView(this).apply { text = " 结束 "; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        twPanel.addView(Button(this).apply {
            text = c.twEnd; textSize = 13f
            setOnClickListener {
                val hm = c.twEnd.split(":")
                TimePickerDialog(this@CreateItemActivity, { _, h, m ->
                    c.twEnd = "%02d:%02d".format(h, m); (this as Button).text = c.twEnd
                }, hm[0].toInt(), hm[1].toInt(), true).show()
            }
        })
        box.addView(twPanel)
        val cutoffPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = if (c.dayCutoff >= 0) View.VISIBLE else View.GONE
            setPadding(dp(12), 0, 0, 0)
        }
        cutoffPanel.addView(TextView(this).apply { text = "分割时间 00:00 – "; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        cutoffPanel.addView(Button(this).apply {
            text = "%02d:%02d".format(if (c.dayCutoff >= 0) c.dayCutoff / 60 else 3, if (c.dayCutoff >= 0) c.dayCutoff % 60 else 0)
            textSize = 13f
            setOnClickListener {
                val h = if (c.dayCutoff >= 0) c.dayCutoff / 60 else 3
                val m = if (c.dayCutoff >= 0) c.dayCutoff % 60 else 0
                TimePickerDialog(this@CreateItemActivity, { _, hh, mm ->
                    c.dayCutoff = hh * 60 + mm; (this as Button).text = "%02d:%02d".format(hh, mm)
                }, h, m, true).show()
            }
        })
        cutoffPanel.addView(TextView(this).apply { text = " 打卡算前一天"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        box.addView(cutoffPanel)
        val twCb = CheckBox(this).apply {
            text = "固定时间段打卡"; textSize = 13f; setTextColor(0xFF1F2430.toInt())
            isChecked = c.timeWindowEnabled
        }
        val cbCutoff = CheckBox(this).apply {
            text = "时间分割：该时间前打卡算前一天"; textSize = 13f; setTextColor(0xFF1F2430.toInt())
            isChecked = c.dayCutoff >= 0
        }
        box.addView(twCb); box.addView(cbCutoff)
        twCb.setOnCheckedChangeListener { _, on ->
            c.timeWindowEnabled = on
            if (on) { c.dayCutoff = -1; cbCutoff.isChecked = false; cutoffPanel.visibility = View.GONE }
            if (on && Method.AUTO.key in c.methods) {
                c.methods.remove(Method.AUTO.key); cur.remove(Method.AUTO.key)
                toast("固定时间段与自动打卡互斥，已关闭自动")
            }
            twPanel.visibility = if (on) View.VISIBLE else View.GONE
        }
        cbCutoff.setOnCheckedChangeListener { _, on ->
            c.dayCutoff = if (on) 180 else -1
            if (on) { c.timeWindowEnabled = false; twCb.isChecked = false; twPanel.visibility = View.GONE }
            cutoffPanel.visibility = if (on) View.VISIBLE else View.GONE
        }
        divider()
        // —— 抵消机制 ——
        val offPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = if (c.offset.enabled) View.VISIBLE else View.GONE
            setPadding(dp(12), 0, 0, 0)
        }
        val etOffN = EditText(this).apply {
            setText("${c.offset.nDays}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f; background = getDrawable(R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val etOffK = EditText(this).apply {
            setText("${c.offset.k}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f; background = getDrawable(R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        offPanel.addView(TextView(this).apply { text = "连续"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        offPanel.addView(etOffN)
        offPanel.addView(TextView(this).apply { text = " 天得 "; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        offPanel.addView(etOffK)
        offPanel.addView(TextView(this).apply { text = " 次（可累积）"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        box.addView(offPanel)
        // v1.3.15：合并为单一监听（visibility 与数字回写同在一个 onChange，避免被后设 listener 覆盖）
        toggleRow("连续打卡抵消机制（🛡️可补漏签）", c.offset.enabled) { on ->
            c.offset.enabled = on
            offPanel.visibility = if (on) View.VISIBLE else View.GONE
            if (on) {
                val n = etOffN.text.toString().toIntOrNull() ?: 7
                val k = etOffK.text.toString().toIntOrNull() ?: 1
                c.offset.nDays = n; c.offset.k = k
            } else { etOffN.clearFocus(); etOffK.clearFocus() }
        }
        // —— 自动打卡 ——
        toggleRow("自动打卡（前台打开 App 自动完成）", Method.AUTO.key in c.methods) { on ->
            if (on) {
                if (c.negative) { toast("负打卡与自动打卡互斥，无法开启自动") }
                else if (c.timeWindowEnabled) { toast("固定时间段与自动打卡互斥，无法开启自动") }
                else { c.methods.add(Method.AUTO.key); cur.add(Method.AUTO.key) }
            } else { c.methods.remove(Method.AUTO.key); cur.remove(Method.AUTO.key) }
            refreshMoreConflicts()
        }
        divider()
        // v1.3.19：方式参数改弹窗式（点已选方式上的 ⚙ 配置），不再内嵌参数区
        // v1.3.16：可用性刷新实现（负打卡/自动打卡 → 次数锁定；次数<2/多方式/负/自动 → 全部完成禁用）
        refreshMoreConflicts = {
            val negOn = c.negative
            val autoOn = Method.AUTO.key in c.methods
            val limitLocked = negOn || autoOn
            btnLimitMinus.isEnabled = !limitLocked; btnLimitPlus.isEnabled = !limitLocked
            btnLimitMinus.alpha = if (limitLocked) 0.4f else 1f; btnLimitPlus.alpha = if (limitLocked) 0.4f else 1f
            val singleMethod = c.methods.count { it != Method.AUTO.key } <= 1
            val canAllReq = !negOn && !autoOn && singleMethod && c.dailyLimit >= 2
            cbAll.isEnabled = canAllReq
            cbAll.alpha = if (canAllReq) 1f else 0.4f
            if (!canAllReq) { cbAll.isChecked = false; c.dailyAllRequired = false }
        }
        refreshMoreConflicts()
        // 方式 chips 变化时刷新可用性
        chipsClickListenerRefresh = { refreshMoreConflicts() }
    }

    /** v1.3.15：子项弹层方式点击后刷新参数区（由 buildGroupMoreSection 注入） */
    private var chipsClickListenerRefresh: (() -> Unit)? = null

    // v1.3.19：renderChips / addChipsToGrid 已由 GearChip 机制取代，移除

    /** v1.3.23 习惯打卡方式选择：竖排方式行（对齐自定义打卡布局，无⚙），替代 3×3 GearChips 网格 */
    private fun buildNdaysMethods() {
        val container = findViewById<LinearLayout>(R.id.ndays_method_container)
        container.removeAllViews()
        val candidates = listOf(Method.NORMAL, Method.PHOTO, Method.TEXT, Method.VOICE, Method.LOCATION, Method.STEPS, Method.TIMER, Method.QRCODE, Method.NFC)
        buildSwitchMethodRows(container, candidates, ndaysMethods, cfg)
    }

    private fun saveGroup() {
        val name = findViewById<EditText>(R.id.et_name).text.toString().trim()
        if (name.isBlank()) { toast("请填写打卡组名称"); return }
        if (groupSubs.isEmpty()) { toast("请至少添加一个子项"); return }
        val now = System.currentTimeMillis()
        cfg.groupMode = true
        cfg.methods.clear(); cfg.methods.add(Method.NORMAL.key)
        cfg.dailyLimit = 1
        // v1.3.15：组可设置打卡日期（模式A每天/B每周几/C双休/D大小周），不再固定每日
        cfg.scheduleMode = scheduleMode
        cfg.weekDays.clear(); cfg.weekDays.addAll(weekDays)
        cfg.bigSmallStart = bigSmallStart
        cfg.negative = false; cfg.timeWindowEnabled = false; cfg.offset.enabled = false; cfg.dayCutoff = -1
        thread {
            val gid: Long
            if (editing != null) {
                gid = editing!!.id
                // v1.3.14：编辑时不再保留的旧成员解除 groupTag，恢复为独立普通打卡项
                val keepIds = groupSubs.mapNotNull { it.id }.toSet()
                cfg.groupMembers.filter { it !in keepIds }.forEach { mid ->
                    repo.getItem(mid)?.let { repo.updateItem(it.copy(groupTag = null, updatedAt = now)) }
                }
            } else {
                val group = CheckinItem(
                    name = name, type = "GROUP", configJson = cfg.toJson(),
                    icon = finalIconKey(), theme = selectedTheme, sortOrder = repo.itemCount(),
                    editPolicy = "FLEX", editInterval = null,
                    lastEditDate = DateUtils.today(), createdAt = now, updatedAt = now
                )
                gid = repo.insertItem(group)
            }
            val memberIds = mutableListOf<Long>()
            groupSubs.forEach { s ->
                if (s.id != null && repo.getItem(s.id!!) != null) {
                    // v1.3.15：编辑已有子项——全量覆盖配置（每日次数/负打卡/时间规则/抵消/方式参数/自动），不限于方式
                    val sub = repo.getItem(s.id!!)!!
                    val subCfg = ItemConfig.parse(s.config.toJson())
                    if (subCfg.methods.isEmpty()) subCfg.methods.add(Method.NORMAL.key)
                    repo.updateItem(sub.copy(name = s.name, configJson = subCfg.toJson(), groupTag = gid.toString(), updatedAt = now))
                    memberIds.add(s.id!!)
                } else {
                    // v1.3.15：新建子项——携带弹层内完整配置（原固定 dailyLimit=1 取消，按用户设置保存）
                    val subCfg = ItemConfig.parse(s.config.toJson())
                    if (subCfg.methods.isEmpty()) subCfg.methods.add(Method.NORMAL.key)
                    val sub = CheckinItem(
                        name = s.name, type = "NORMAL", configJson = subCfg.toJson(),
                        icon = "default", theme = autoThemeFor(), sortOrder = repo.itemCount(),
                        groupTag = gid.toString(), editPolicy = "LOCKED",
                        lastEditDate = DateUtils.today(), createdAt = now, updatedAt = now
                    )
                    memberIds.add(repo.insertItem(sub))
                }
            }
            cfg.groupMembers.clear(); cfg.groupMembers.addAll(memberIds)
            cfg.groupMemberNames.clear(); cfg.groupMemberNames.addAll(groupSubs.map { it.name })
            if (editing != null) {
                repo.updateItem(editing!!.copy(name = name, theme = selectedTheme, icon = finalIconKey(), configJson = cfg.toJson(), updatedAt = now))
                runOnUiThread { finish() }
            } else {
                repo.getItem(gid)?.let { repo.updateItem(it.copy(configJson = cfg.toJson())) }
                // v1.3.23：新建打卡组完成后直接进入组打卡页；回传标记让类型选择页一并退出
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CREATED, true))
                    startActivity(Intent(this, ItemCheckinActivity::class.java)
                        .putExtra(ItemCheckinActivity.EXTRA_ID, gid))
                    finish()
                }
            }
        }
    }

    // ---------- v1.3.13 打卡组 / N天打卡 ----------
    // ---------- 主题 ----------
    private fun buildThemeChips() {
        // v1.3.19：图标选择（六列；收起态一行 5 常用 + 更多格；展开全部 21）
        val container = findViewById<LinearLayout>(R.id.theme_container)
        renderIconGrid(container)
    }

    private fun renderIconGrid(container: LinearLayout) {
        container.removeAllViews()
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 6
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // v1.3.19：收起态只显示前 5 个常用图标 + 更多格（一行六列）；扩展图标不提前露出
        // 编辑回显：当前图标不在常用 5 个时，替换第 5 格使其可见（保证用户能看到已选图标）
        val shown = if (iconExpanded) com.zerolab.checkin.theme.IconManager.all
                    else {
                        val commons = com.zerolab.checkin.theme.IconManager.common.take(5).toMutableList()
                        val selIcon = selectedIcon?.let { s -> com.zerolab.checkin.theme.IconManager.all.firstOrNull { it.key == s } }
                        if (selIcon != null && commons.none { it.key == selIcon.key }) commons[4] = selIcon
                        commons
                    }
        shown.forEach { ic ->
            val cell = iconCell(ic)
            cell.setOnClickListener {
                selectedIcon = ic.key
                renderIconGrid(container)
            }
            grid.addView(cell)
        }
        val more = iconMoreCell(iconExpanded)
        more.setOnClickListener {
            iconExpanded = !iconExpanded
            renderIconGrid(container)
        }
        grid.addView(more)
        container.addView(grid)

        // v1.3.24：图标下方新增主题选择（8 套点缀色；打卡按钮跟随全局主题，主题仅做卡片/点缀）
        fun tdp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        container.addView(TextView(this).apply {
            text = "主题"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF6E7F78.toInt())
            setPadding(0, tdp(12), 0, tdp(6))
        })
        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        com.zerolab.checkin.theme.ThemeManager.themes.forEach { t ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.bottomMargin = tdp(4)
                layoutParams = lp
            }
            val dot = View(this).apply {
                val d = tdp(26)
                layoutParams = LinearLayout.LayoutParams(d, d)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(t.primary)
                    setStroke(tdp(2), if (t.id == selectedTheme) 0xFF1F2430.toInt() else 0x00000000)
                }
            }
            cell.addView(dot)
            cell.addView(TextView(this).apply {
                text = t.name; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(if (t.id == selectedTheme) 0xFF1F2430.toInt() else 0xFF9AA1B2.toInt())
            })
            cell.setOnClickListener {
                selectedTheme = t.id
                renderIconGrid(container)
            }
            themeRow.addView(cell)
        }
        container.addView(themeRow)
    }

    /** v1.3.19：图标格——纯图标居中、无文字 */
    private fun iconCell(ic: com.zerolab.checkin.theme.ItemIcon): View {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        val sel = ic.key == selectedIcon
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(48)   // v1.3.22：圆角正方形；v1.3.23：列宽均分随容器自适应，防溢出
                setMargins(dp(4), dp(4), dp(4), dp(4))
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            }
            layoutParams = lp
        }
        v.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (sel) 0xFFE4F6EC.toInt() else 0xFFFFFFFF.toInt())
            setStroke(if (sel) dp(2) else dp(1), if (sel) 0xFF2FBF71.toInt() else 0xFFE3EAE5.toInt())
        }
        v.addView(TextView(this).apply { text = ic.emoji; textSize = 22f; gravity = Gravity.CENTER })
        return v
    }

    /** v1.3.19：更多/收起格——纯图标无文字 */
    private fun iconMoreCell(expanded: Boolean): View {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = dp(48)   // v1.3.22：圆角正方形；v1.3.23：列宽均分随容器自适应，防溢出
                setMargins(dp(4), dp(4), dp(4), dp(4))
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            }
            layoutParams = lp
        }
        v.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (expanded) 0xFFFFFFFF.toInt() else 0x00000000)
            setStroke(dp(1), 0xFFC4D2CA.toInt())
        }
        v.addView(TextView(this).apply {
            text = if (expanded) "▴" else "+"; textSize = 22f
            setTextColor(0xFF7E9188.toInt()); gravity = Gravity.CENTER
        })
        return v
    }

    /** 最终保存的 icon key：优先选中项；编辑未重选时保持原值；新建未选择时随机挑一个（v1.3.19） */
    private fun finalIconKey(): String =
        selectedIcon ?: editing?.icon ?: com.zerolab.checkin.theme.IconManager.all.random().key

    /** v1.3.24：按列表序轮转 FloraTheme——仅用于打卡组子项的自动配色（组子项无独立主题选择） */
    private fun autoThemeFor(): String = ThemeManager.themes[(repo.itemCount()) % ThemeManager.themes.size].id

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
        val normalTv = findViewById<TextView>(R.id.tv_tab_normal)
        val journalTv = findViewById<TextView>(R.id.tv_tab_journal)

        // v1.3.3：分段胶囊，选中项白底滑块
        normalTab.setBackgroundResource(if (!journalMode) R.drawable.bg_seg_item else 0)
        normalTv.setTextColor(if (!journalMode) 0xFFE5559B.toInt() else 0xFF8A90A0.toInt())
        normalTv.typeface = if (!journalMode) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        journalTab.setBackgroundResource(if (journalMode) R.drawable.bg_seg_item else 0)
        journalTv.setTextColor(if (journalMode) 0xFFE5559B.toInt() else 0xFF8A90A0.toInt())
        journalTv.typeface = if (journalMode) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        modeHint?.text = if (journalMode)
            "📔 日记打卡：日记式记录，只记成功、可多次记录，不记缺卡、不设排期"
            else "✅ 普通打卡：按规则打卡，有缺卡与连续天数。"
    }

    /** v1.3.0：日记 tab 下按记录类型/模式刷新区块与方式行可见性（纯视图，不改配置） */
    private fun refreshJournalVisibility() {
        if (groupMode || ndaysMode) return
        val journal = journalMode
        findViewById<View>(R.id.tv_method_title).visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tv_method_desc).visibility = if (journal) View.GONE else View.VISIBLE
        refreshMoreVisibility()   // v1.3.22：高级卡由「更多选项」折叠控制（日记一律隐藏）
        findViewById<View>(R.id.journal_panel).visibility = if (journal) View.VISIBLE else View.GONE

        // v1.3.3：普通方式容器仅普通 tab 显示；日记 tab 下 PHOTO/TEXT/VOICE 的 card 移到记录类型之间
        val methodContainer = findViewById<LinearLayout>(R.id.method_container)
        val journalMethodsBox = findViewById<LinearLayout>(R.id.journal_methods)
        methodContainer.visibility = if (journal) View.GONE else View.VISIBLE
        findViewById<View>(R.id.method_card).visibility = if (journal) View.GONE else View.VISIBLE
        if (journal) {
            // 移到 journal_methods
            journalMethods.forEach { k ->
                val card = rows[k]!!.card
                (card.parent as? android.view.ViewGroup)?.removeView(card)
                journalMethodsBox.addView(card)
            }
        } else {
            // 移回 method_container 原位置
            journalMethods.forEach { k ->
                val card = rows[k]!!.card
                (card.parent as? android.view.ViewGroup)?.removeView(card)
                methodContainer.addView(card, journalMethodOrigIndex[k] ?: methodContainer.childCount)
            }
        }

        // 方式行：普通=全部显示（MOOD 除外）；心情日记=全隐藏；随心记=只留 PHOTO/TEXT/VOICE
        val forbidden = setOf(Method.NORMAL.key, Method.AUTO.key, Method.NFC.key, Method.STEPS.key, Method.TIMER.key, Method.QRCODE.key)
        rows.forEach { (k, row) ->
            row.card.visibility = when {
                k == Method.MOOD.key -> View.GONE
                !journal -> View.VISIBLE
                moodMode -> View.GONE
                k in forbidden -> View.GONE
                // v1.3.4：随心记的位置打卡需要联网增强，未开启则隐藏
                k == Method.LOCATION.key && journal && !com.zerolab.checkin.util.NetGeo.enabled(this) -> View.GONE
                else -> View.VISIBLE
            }
            // v1.3.4：随心记模式下所有方式只显示一级开关，二级 panel 全部隐藏
            // v1.3.19：日记模式下不提供 ⚙ 配置弹窗
            if (journal) { row.panel?.visibility = View.GONE; row.gear?.visibility = View.GONE }
        }
        // 随心记方式区仅在随心记（非心情日记）时显示
        journalMethodsBox.visibility = if (journal && !moodMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cb_mood_chart).visibility = if (journal && moodMode) View.VISIBLE else View.GONE
        refreshComboNRow()
    }

    /** v1.3.0：日记 tab 记录类型单选（📔随心记 | 😊心情日记） */
    private fun setMoodMode(on: Boolean) {
        // v1.3.3：无论是否提前 return，都先同步两个 RadioButton 的互斥勾选（编辑页回显时也需要）
        findViewById<RadioButton>(R.id.rb_journal_suixinsui).isChecked = !on
        findViewById<RadioButton>(R.id.rb_journal_mood).isChecked = on
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
        // v1.3.2：新建时也要刷一次方式行可见性（隐藏 MOOD 方式行等）
        refreshJournalVisibility()
    }

    private fun setJournalMode(on: Boolean) {
        if (journalMode == on) return
        if (on) {
            // 切到日记：自动移除不允许的方式（NORMAL/AUTO/NFC/STEPS/TIMER/QRCODE）
            val forbidden = listOf(Method.NORMAL.key, Method.AUTO.key, Method.NFC.key, Method.STEPS.key, Method.TIMER.key, Method.QRCODE.key, Method.MOOD.key)
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
            // 回到普通：恢复默认每日 1 次；日记记录类型复位随心记（v1.3.3：同时清 mood 勾选，避免双选残留）
            moodMode = false
            findViewById<RadioButton>(R.id.rb_journal_suixinsui).isChecked = true
            findViewById<RadioButton>(R.id.rb_journal_mood).isChecked = false
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

    // ---------- v1.3.22 自定义打卡「更多选项」折叠 ----------
    private fun bindMoreButton() {
        findViewById<View>(R.id.btn_more).setOnClickListener {
            moreExpanded = !moreExpanded
            refreshMoreVisibility()
        }
    }

    private fun refreshMoreVisibility() {
        if (groupMode || ndaysMode) {
            findViewById<View>(R.id.btn_more).visibility = View.GONE
            return
        }
        findViewById<View>(R.id.btn_group_more).visibility = View.GONE   // v1.3.22：组更多仅打卡组创建页显示（普通/自定义/日记模式隐藏）
        val show = !journalMode && moreExpanded
        findViewById<View>(R.id.btn_more).visibility = if (journalMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.rule_card).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.schedule_card).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.offset_card).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.policy_card).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_rule_title).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_offset_title).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_policy_title).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.btn_more).text = if (moreExpanded) "收起选项 ▴" else "更多选项 ▾"
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
            // v1.3.11：外壳代码构建（轻量）；v1.3.24：参数配置改为内联展开（方案B）
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
            // v1.3.24：删除 ⚙ 弹窗入口，配置改为开启后内联展开
            val sw = SwitchCompat(this)
            head.addView(label); head.addView(sw)
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            card.addView(head); card.addView(panel)
            sw.setOnCheckedChangeListener { _, on ->
                // v1.2.1：随心记禁用 NORMAL/AUTO/NFC/STEPS/TIMER/QRCODE，其余方式可正常开启
                val journalForbidden = setOf(Method.NORMAL.key, Method.AUTO.key, Method.NFC.key, Method.STEPS.key, Method.TIMER.key, Method.QRCODE.key)
                if (on && journalMode && m.key in journalForbidden) {
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("随心记暂不支持「${m.label}」")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (on && m.key == Method.AUTO.key && findViewById<CompoundButton>(R.id.cb_negative).isChecked) {
                    // v1.2.1：负打卡已开启时点自动打卡 → 互斥提示
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("负打卡与自动打卡互斥，无法同时开启")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (on && m.key == Method.STEPS.key && m.key !in cfg.methods) {
                    // v6.1.0：步数功能开发中，新建/新开启时固定关闭并提示
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("该功能开发中")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (on && findViewById<CompoundButton>(R.id.cb_time_window).isChecked && m.key == Method.AUTO.key) {
                    // v1.1.7：固定时间段已开启时点自动打卡 → 互斥提示
                    sw.isChecked = false
                    panel.visibility = View.GONE
                    cfg.methods.remove(m.key)
                    toast("固定时间段与自动打卡互斥，无法同时开启")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (on && m.key in Method.conflictsWith(cfg.methods)) {
                    // v1.3.24：切换式互斥——取消与其互斥的已选方式，选中当前方式并提示
                    val cancel = cfg.methods.filter { it in Method.conflictsWith(setOf(m.key)) }
                    cancel.forEach { k ->
                        cfg.methods.remove(k)
                        rows[k]?.let { r -> r.switch.isChecked = false; r.panel.visibility = View.GONE }
                    }
                    cfg.methods.add(m.key)
                    if (!journalMode && hasMethodConfig(m)) { ensurePanel(m); panel.visibility = View.VISIBLE }
                    val names = cancel.mapNotNull { Method.of(it)?.label }.joinToString("、")
                    toast(if (names.isEmpty()) "已切换为「${m.label}」" else "已切换为「${m.label}」，${names}已关闭")
                    refreshConflicts()
                    refreshComboNRow()
                    return@setOnCheckedChangeListener
                }
                // v1.3.24：开启方式后配置内联展开在卡片下方（方案B，无 ⚙）
                if (on && !journalMode) { ensurePanel(m); panel.visibility = View.VISIBLE } else panel.visibility = View.GONE
                if (on) cfg.methods.add(m.key) else cfg.methods.remove(m.key)
                refreshConflicts()
            }
            container.addView(card)
            rows[m.key] = MethodRow(sw, panel, card)
        }
    }

    /** v1.3.24：方式参数内联面板（方案B）——首次开启时构建配置视图 */
    private fun ensurePanel(m: Method) {
        val row = rows[m.key] ?: return
        if (row.built) return
        row.built = true
        row.panel.addView(buildMethodPanel(m, cfg))
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
                    toast("随心记不设排期，暂不支持调整打卡日期")
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
                        toast("随心记不设排期，暂不支持选择星期")
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

    /** 是否有可配置项（决定 ⚙ 是否显示） */
    private fun hasMethodConfig(m: Method): Boolean = when (m) {
        Method.PHOTO, Method.TEXT, Method.LOCATION, Method.TIMER, Method.QRCODE, Method.NFC, Method.VOICE -> true
        else -> false
    }

    /** v1.3.24：方式规则配置内联面板（方案B，替代弹窗）；直接读写 target（cfg / cfgSub），返回视图由调用方挂载 */
    private fun buildMethodPanel(m: Method, target: ItemConfig): LinearLayout {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        when (m) {
            Method.PHOTO -> {
                box.addView(CheckBox(this).apply {
                    text = "允许拍照"; isChecked = target.photoFromCamera; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> target.photoFromCamera = on }
                })
                box.addView(CheckBox(this).apply {
                    text = "允许相册"; isChecked = target.photoFromAlbum; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> target.photoFromAlbum = on }
                })
            }
            Method.TEXT -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply { text = "最低字数 "; textSize = 14f; setTextColor(0xFF1F2430.toInt()) })
                val et = EditText(this).apply {
                    setText("${target.textMinWords}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    textSize = 14f; background = getDrawable(R.drawable.bg_input)
                    layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                et.setOnFocusChangeListener { _, has -> if (!has) target.textMinWords = et.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: target.textMinWords }
                row.addView(et)
                row.addView(TextView(this).apply { text = " 字"; textSize = 14f; setTextColor(0xFF6E7F78.toInt()) })
                box.addView(row)
                box.addView(CheckBox(this).apply {
                    text = "不可与上次内容重复"; isChecked = target.textNoRepeat; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> target.textNoRepeat = on }
                })
            }
            Method.LOCATION -> {
                box.addView(CheckBox(this).apply {
                    text = "负打卡：离开标准点即破戒"; isChecked = target.locNegative; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> target.locNegative = on }
                })
                val tv = TextView(this).apply {
                    textSize = 12f; setTextColor(0xFF6E7F78.toInt()); setPadding(0, 4, 0, 0)
                }
                fun refreshLoc() {
                    tv.text = if (target.locPoints.isEmpty()) "尚未设定标准位置"
                        else "已设定 ${target.locPoints.size} 个标准点：\n" + target.locPoints.joinToString("\n") { p -> "· ${p.name} ${formatLatLng(p.lat, p.lng)} 半径${p.radius}m" }
                }
                refreshLoc()
                box.addView(tv)
                box.addView(Button(this).apply {
                    text = "获取当前位置作为标准点"; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                    setOnClickListener { fetchLocationInDialog(target, tv) { refreshLoc() } }
                })
            }
            Method.TIMER -> {
                box.addView(TextView(this).apply { text = "计时方式"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()); setPadding(0, 8, 0, 4) })
                val rbCount = RadioButton(this).apply { text = "倒计时"; isChecked = target.timerMode != "COUNTUP"; setTextColor(0xFF1F2430.toInt()) }
                val rbUp = RadioButton(this).apply { text = "正计时"; isChecked = target.timerMode == "COUNTUP"; setTextColor(0xFF1F2430.toInt()) }
                box.addView(rbCount); box.addView(rbUp)
                rbCount.setOnCheckedChangeListener { _, on -> if (on) target.timerMode = "COUNTDOWN" }
                rbUp.setOnCheckedChangeListener { _, on -> if (on) target.timerMode = "COUNTUP" }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply { text = "时长 "; textSize = 14f; setTextColor(0xFF1F2430.toInt()) })
                val et = EditText(this).apply {
                    setText("${target.timerMinutes}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    textSize = 14f; background = getDrawable(R.drawable.bg_input)
                    layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                et.setOnFocusChangeListener { _, has -> if (!has) target.timerMinutes = et.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: target.timerMinutes }
                row.addView(et)
                row.addView(TextView(this).apply { text = " 分钟"; textSize = 14f; setTextColor(0xFF6E7F78.toInt()) })
                box.addView(row)
                val cbPause = CheckBox(this).apply {
                    text = "可暂停保存（仅正计时）"; isChecked = target.timerPausable; setTextColor(0xFF1F2430.toInt())
                }
                val cbForce = CheckBox(this).apply {
                    text = "强制模式（离开本页即失败，熄屏除外）"; isChecked = target.timerForce; setTextColor(0xFF1F2430.toInt())
                }
                box.addView(cbPause); box.addView(cbForce)
                cbPause.setOnCheckedChangeListener { _, on ->
                    if (on && target.timerMode != "COUNTUP") { cbPause.isChecked = false; toast("暂停保存仅支持正计时") }
                    else target.timerPausable = on
                }
                cbForce.setOnCheckedChangeListener { _, on ->
                    target.timerForce = on
                    if (on) { target.timerPausable = false; cbPause.isChecked = false; cbPause.visibility = View.GONE }
                    else cbPause.visibility = View.VISIBLE
                }
                if (target.timerForce) { cbPause.isChecked = false; cbPause.visibility = View.GONE }
                // 负打卡开启时强制倒计时（对齐主页面 hideTimerAdvancedOptions）
                if (target.negative) { rbUp.isChecked = false; rbUp.isEnabled = false; cbPause.visibility = View.GONE }
            }
            Method.QRCODE -> {
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL }
                }
                fun renderQr() { iv.setImageBitmap(makeQrBitmap(target.qrContent.ifBlank { "uuid:" + java.util.UUID.randomUUID() })) }
                renderQr()
                box.addView(iv, 0)
                box.addView(TextView(this).apply {
                    text = "长按二维码保存到相册，可打印张贴"; textSize = 12f; setTextColor(0xFF6E7F78.toInt()); gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 6, 0, 0)
                })
                iv.setOnLongClickListener {
                    val content = target.qrContent.ifBlank { "uuid:" + java.util.UUID.randomUUID() }
                    if (target.qrContent.isBlank()) { target.qrContent = content }
                    makeQrBitmap(content)?.let { saveQrToGallery(it, content) }
                    true
                }
                box.addView(Button(this).apply {
                    text = "重新绑定新二维码"; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                    setOnClickListener {
                        target.qrContent = "uuid:" + java.util.UUID.randomUUID()
                        renderQr()
                        toast("已生成新二维码")
                    }
                })
            }
            Method.NFC -> {
                val tv = TextView(this).apply {
                    textSize = 13f; setTextColor(0xFF1F2430.toInt()); setPadding(0, 4, 0, 0)
                    text = if (target.nfcTagId.isBlank()) "尚未绑定 NFC 标签" else "已绑定标签：${target.nfcTagId}"
                }
                box.addView(tv)
                box.addView(Button(this).apply {
                    text = if (target.nfcTagId.isBlank()) "绑定 NFC 标签" else "重新绑定 NFC 标签"; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                    setOnClickListener { bindNfcInDialog(target, tv) }
                })
            }
            Method.VOICE -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply { text = "最长 "; textSize = 14f; setTextColor(0xFF1F2430.toInt()) })
                val et = EditText(this).apply {
                    setText("${target.voiceMaxSeconds}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    textSize = 14f; background = getDrawable(R.drawable.bg_input)
                    layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                et.setOnFocusChangeListener { _, has -> if (!has) target.voiceMaxSeconds = et.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: target.voiceMaxSeconds }
                row.addView(et)
                row.addView(TextView(this).apply { text = " 秒"; textSize = 14f; setTextColor(0xFF6E7F78.toInt()) })
                box.addView(row)
            }
            else -> box.addView(TextView(this).apply { text = "点击打卡即完成，无需额外设置。"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        }
        // v1.3.24：内联面板无保存按钮——配置即写；完整性校验由 save()/子项确定时兜底
        return box
    }

    // ---------- v1.3.23 竖排方式选择行（对齐自定义打卡布局，替代 GearChip 3×3 网格） ----------
    /**
     * 每个打卡方式一张卡片、一行（左 emoji+名称，右 Switch），竖排；无 ⚙。
     * v1.3.24：勾选可配置方式后配置内联展开在卡片下方（方案B）；普通打卡与其它方式互斥（只按钮置灰但可点，
     * 点击切换并提示）；空选择兜底普通；步数打卡开发中禁用。
     * @param sel 已选方式集合（直接读写）
     * @param target 配置对象（内联配置写入）
     * @param onChanged 方式变化后的回调（如刷新更多区可用性）
     */
    private fun buildSwitchMethodRows(
        container: LinearLayout,
        candidates: List<Method>,
        sel: MutableSet<String>,
        target: ItemConfig,
        onChanged: () -> Unit = {}
    ) {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        val switches = LinkedHashMap<String, SwitchCompat>()
        val labels = LinkedHashMap<String, TextView>()
        val panels = LinkedHashMap<String, LinearLayout>()
        var refreshing = false   // 程序化刷新开关状态时不触发业务回调

        fun refreshAll() {
            refreshing = true
            try {
                candidates.forEach { m ->
                    val sw = switches[m.key] ?: return@forEach
                    val lb = labels[m.key] ?: return@forEach
                    val selected = m.key in sel
                    val blocked = !selected && m.key != Method.NORMAL.key && Method.NORMAL.key in sel
                    val stepDisabled = m.key == Method.STEPS.key
                    val disabled = blocked || stepDisabled
                    sw.isEnabled = !stepDisabled   // v1.3.24：互斥仅视觉置灰（只按钮灰、文字不变灰），保持可点击
                    sw.alpha = if (disabled) 0.4f else 1f
                    if (sw.isChecked != selected) sw.isChecked = selected
                    lb.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    lb.setTextColor(if (selected) 0xFF1F7A4D.toInt() else 0xFF1F2430.toInt())
                    // v1.3.24：配置面板随选中状态展开/收起
                    panels[m.key]?.visibility = if (selected && hasMethodConfig(m)) View.VISIBLE else View.GONE
                }
            } finally { refreshing = false }
        }

        candidates.forEach { m ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_card)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(10)
                layoutParams = lp
                setPadding(dp(16), dp(6), dp(16), dp(8))
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = "${m.emoji} ${m.label}"
                textSize = 15f
                setTextColor(0xFF1F2430.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = SwitchCompat(this)
            head.addView(label); head.addView(sw)
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            card.addView(head); card.addView(panel)
            container.addView(card)
            switches[m.key] = sw
            labels[m.key] = label
            panels[m.key] = panel
            sw.setOnCheckedChangeListener { _, on ->
                if (refreshing) return@setOnCheckedChangeListener
                val mk = m.key
                if (on) {
                    if (mk == Method.STEPS.key) {
                        toast("该功能开发中")
                        refreshing = true; sw.isChecked = false; refreshing = false
                        refreshAll()
                        return@setOnCheckedChangeListener
                    }
                    if (mk == Method.NORMAL.key) {
                        sel.clear(); sel.add(Method.NORMAL.key)
                    } else {
                        // v1.3.24：互斥切换提示（从普通切到其它方式时）
                        val switched = Method.NORMAL.key in sel
                        if (switched) {
                            sel.clear()
                            toast("已切换为「${m.label}」，普通打卡已关闭")
                        }
                        sel.add(mk)
                        // v1.3.24：配置内联展开在方式卡片下方（方案B，不再弹窗）
                        if (hasMethodConfig(m)) {
                            if (panels[mk]?.childCount == 0) panels[mk]?.addView(buildMethodPanel(m, target))
                            panels[mk]?.visibility = View.VISIBLE
                        }
                    }
                } else {
                    if (mk == Method.NORMAL.key) {
                        toast("至少保留一种打卡方式")
                        refreshing = true; sw.isChecked = true; refreshing = false
                        refreshAll()
                        return@setOnCheckedChangeListener
                    } else {
                        sel.remove(mk)
                        panels[mk]?.visibility = View.GONE
                        if (sel.isEmpty()) sel.add(Method.NORMAL.key)
                    }
                }
                refreshAll()
                onChanged()
            }
        }
        refreshAll()
    }

    /** v1.3.19：弹窗内获取当前位置并加入 target 的标准点列表 */
    private fun fetchLocationInDialog(target: ItemConfig, tv: TextView, onDone: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast("需要定位权限")
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loading = android.app.AlertDialog.Builder(this).setMessage("正在获取当前位置…").setCancelable(false).show()
        val best = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val providers = try {
            lm.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
                .ifEmpty { lm.getProviders(true) }
        } catch (_: Exception) { emptyList() }
        for (p in providers) {
            try { val l = lm.getLastKnownLocation(p) ?: continue; if (best.get() == null || l.accuracy < (best.get()?.accuracy ?: 9999f)) best.set(l) } catch (_: Exception) {}
        }
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
                val box2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(52, 4, 52, 0) }
                box2.addView(etR)
                android.app.AlertDialog.Builder(this)
                    .setTitle("确认作为标准位置？")
                    .setMessage("位置：${formatLatLng(l.latitude, l.longitude)}\n允许半径（米，50-5000，默认200）")
                    .setView(box2)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("使用该位置") { _, _ ->
                        val r = (etR.text.toString().toIntOrNull() ?: 200).coerceIn(50, 5000)
                        target.locPoints.add(LocatePoint("位置${target.locPoints.size + 1}", l.latitude, l.longitude, r))
                        tv.text = "已设定 ${target.locPoints.size} 个标准点：\n" +
                            target.locPoints.joinToString("\n") { "· ${it.name} ${formatLatLng(it.lat, it.lng)} 半径${it.radius}m" }
                        onDone()
                    }.show()
            }
        }
    }

    /** v1.3.19：弹窗内绑定 NFC 标签（写 target） */
    private fun bindNfcInDialog(target: ItemConfig, tv: TextView) {
        val nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc == null) { toast("此设备不支持 NFC，无法绑定标签"); return }
        if (!nfc.isEnabled) { toast("系统 NFC 已关闭，请先在系统设置中开启"); return }
        nfcAdapter = nfc
        tv.text = "请将 NFC 标签贴近手机背面…"
        nfcReader = NfcAdapter.ReaderCallback { tag ->
            runOnUiThread {
                val id = tag.id.joinToString("") { "%02X".format(it) }
                target.nfcTagId = id
                tv.text = "已绑定标签：$id"
                toast("已读取 NFC 标签并绑定")
            }
        }
        nfc.enableReaderMode(this, nfcReader!!,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
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
        // v1.4.0：每日"全部完成"开关可用性：单方式 + 每日次数>=2 + 非负打卡 + 非自动打卡 + 非日记
        val canAllReq = !journalMode && !moodMode && Method.AUTO.key !in cfg.methods &&
            cfg.methods.count { it != Method.AUTO.key } <= 1 && dailyLimit >= 2 && !negOn
        findViewById<CheckBox>(R.id.cb_all_required).isEnabled = !locked && canAllReq
        findViewById<CheckBox>(R.id.cb_all_required).alpha = if (canAllReq) 1f else 0.4f
        if (!canAllReq) findViewById<CheckBox>(R.id.cb_all_required).isChecked = false
        refreshComboNRow()
    }

    // ---------- 频率与规则 ----------
    private fun bindRuleControls() {
        val tvLimit = findViewById<TextView>(R.id.tv_limit)
        fun renderLimit() {
            tvLimit.text = if (dailyLimit < 0) "不限" else dailyLimit.toString()
            refreshConflicts()   // v1.4.0：次数变化后刷新"全部完成"开关可用性
        }
        btnLimitMinus = findViewById(R.id.btn_limit_minus)
        btnLimitPlus = findViewById(R.id.btn_limit_plus)
        btnLimitMinus!!.setOnClickListener {
            // v1.2.0：随心记每日次数固定"不限"
            if (journalMode) { toast("随心记每日不限次数，无需设置"); return@setOnClickListener }
            // v1.2.2：自动打卡每日固定 1 次
            if (Method.AUTO.key in cfg.methods) { toast("自动打卡每日固定 1 次，无需设置"); return@setOnClickListener }
            dailyLimit = when { dailyLimit == -1 -> 1; dailyLimit <= 1 -> -1; else -> dailyLimit - 1 }; renderLimit()
        }
        btnLimitPlus!!.setOnClickListener {
            if (journalMode) { toast("随心记每日不限次数，无需设置"); return@setOnClickListener }
            if (Method.AUTO.key in cfg.methods) { toast("自动打卡每日固定 1 次，无需设置"); return@setOnClickListener }
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
                    toast("随心记不记录失败，暂不支持负打卡")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
                if (Method.AUTO.key in cfg.methods) {  // v1.2.1：负打卡与自动打卡互斥
                    cbNeg.isChecked = false
                    toast("负打卡与自动打卡互斥，无法同时开启")
                    refreshConflicts()
                    return@setOnCheckedChangeListener
                }
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
                toast("随心记不设排期，暂不支持固定时间段")
                refreshConflicts()
                return@setOnCheckedChangeListener
            }
            if (on && Method.AUTO.key in cfg.methods) {
                // v1.1.7：自动打卡已开启时点固定时间段 → 互斥提示
                cbTw.isChecked = false
                toast("固定时间段与自动打卡互斥，无法同时开启")
                refreshConflicts()
                return@setOnCheckedChangeListener
            }
            if (on) { findViewById<CompoundButton>(R.id.cb_day_cutoff).isChecked = false }  // v1.3.12b：与时间分割互斥单选
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
                toast("随心记暂不支持抵消机制")
            }
        }
        // v1.3.12 时间分割：凌晨该时间前打卡归属前一天
        val cbCutoff = findViewById<CompoundButton>(R.id.cb_day_cutoff)
        val btnCutoff = findViewById<Button>(R.id.btn_day_cutoff)
        var cutoffJustToggled = false
        cbCutoff.setOnCheckedChangeListener { _, on ->
            cutoffJustToggled = true
            findViewById<View>(R.id.cutoff_panel).visibility = if (on) View.VISIBLE else View.GONE
            if (on) { findViewById<CompoundButton>(R.id.cb_time_window).isChecked = false }  // v1.3.12b：与固定时间段互斥单选
        }
        cbCutoff.setOnClickListener {
            if (cbCutoff.isChecked && !cutoffJustToggled) cbCutoff.isChecked = false
            cutoffJustToggled = false
        }
        btnCutoff.setOnClickListener { pickCutoffTime() }
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
    /** v1.3.12 时间分割时间选择（凌晨分割点） */
    private fun pickCutoffTime() {
        val cur = findViewById<Button>(R.id.btn_day_cutoff).text.toString().split(":")
        val h = cur[0].toIntOrNull() ?: 3
        val mi = cur[1].toIntOrNull() ?: 0
        TimePickerDialog(this, { _, hh, mm ->
            findViewById<Button>(R.id.btn_day_cutoff).text = "%02d:%02d".format(hh, mm)
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
        cfg.dailyAllRequired = loaded.dailyAllRequired // v1.4.0：编辑回显"全部完成"开关
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
        selectedIcon = if (com.zerolab.checkin.theme.IconManager.isKnown(it.icon)) it.icon else null
        iconExpanded = false
        // v1.3.13 打卡组 / N天打卡 回显
        if (loaded.groupMode) {
            groupMode = true
            applySpecialModeVisibility()
            groupSubs.clear()
            loaded.groupMembers.forEachIndexed { i, mid ->
                val m = repo.getItem(mid)
                val nm = m?.name ?: loaded.groupMemberNames.getOrNull(i) ?: "子项"
                // v1.3.15：子项携带完整 ItemConfig 回显（后续点击行可直接编辑全部规则）
                val mc = m?.let { ItemConfig.parse(it.configJson) } ?: ItemConfig()
                if (mc.methods.isEmpty()) mc.methods.add(Method.NORMAL.key)
                groupSubs.add(GroupSub(mid, nm, mc))
            }
            renderGroupMembers()
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
            row.gear?.visibility = if (on && !journalMode && hasMethodConfig(Method.of(key) ?: Method.NORMAL)) View.VISIBLE else View.GONE
        }
        fillParamUi()
        tvLocPoints?.text = if (cfg.locPoints.isEmpty()) "" else
            "已设定 ${cfg.locPoints.size} 个标准点：\n" + cfg.locPoints.joinToString("\n") { p -> "· ${p.name} ${formatLatLng(p.lat, p.lng)} 半径${p.radius}m" }
        tvQr?.text = "长按上方二维码可保存到相册，用于打印张贴。\n专属内容：${cfg.qrContent}"
        ivQr?.setImageBitmap(makeQrBitmap(cfg.qrContent))
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
        scheduleModeChips.forEach { (k, v) -> applyScheduleChipStyle(v, k == scheduleMode) }
        renderSchedulePanels()
        renderWeekDayChips()
        renderBigSmallChips()
        findViewById<LinearLayout>(R.id.theme_container)?.let { renderIconGrid(it) }
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

    private var locked = false
    private fun lockRules(msg: String) {
        locked = true
        toast(msg)
        // v1.3.27：内联方式面板内部控件一并锁定（v1.3.24 内联化后 panel 内控件此前未被锁定，
        // 导致 LOCKED 下仍可改定位标准点/字数/来源等参数）；管理员模式不调用本方法
        rows.values.forEach { row ->
            row.switch.isEnabled = false
            row.gear?.isEnabled = false
            row.gear?.alpha = 0.4f
            row.panel?.let { disableSubtree(it) }
        }
        findViewById<CompoundButton>(R.id.cb_negative).isEnabled = false
        findViewById<CompoundButton>(R.id.cb_time_window).isEnabled = false
        findViewById<Button>(R.id.btn_tw_start).isEnabled = false
        findViewById<Button>(R.id.btn_tw_end).isEnabled = false
        findViewById<CheckBox>(R.id.cb_offset).isEnabled = false
        findViewById<CompoundButton>(R.id.cb_day_cutoff).isEnabled = false
        findViewById<Button>(R.id.btn_day_cutoff).isEnabled = false
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
        cbTimerForce?.isEnabled = false
        qrBindBtn?.isEnabled = false
    }

    /** v1.3.27：递归禁用视图子树（内联方式面板锁定；TextView 类附带降透明度提示不可用） */
    private fun disableSubtree(v: View) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) disableSubtree(v.getChildAt(i))
        } else {
            v.isEnabled = false
            if (v is android.widget.TextView) v.alpha = 0.45f
        }
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
        // v1.4.0：每日全部完成开关（仅在单方式 + 次数>=2 + 非负打卡 + 非自动打卡时有效）
        cfg.dailyAllRequired = findViewById<CheckBox>(R.id.cb_all_required).isChecked &&
            dailyLimit >= 2 && !findViewById<CompoundButton>(R.id.cb_negative).isChecked &&
            Method.AUTO.key !in cfg.methods && cfg.methods.count { it != Method.AUTO.key } <= 1
        cfg.negative = findViewById<CompoundButton>(R.id.cb_negative).isChecked
        cfg.timeWindowEnabled = findViewById<CompoundButton>(R.id.cb_time_window).isChecked
        cfg.twStart = findViewById<Button>(R.id.btn_tw_start).text.toString()
        cfg.twEnd = findViewById<Button>(R.id.btn_tw_end).text.toString()
        // v1.3.19：方式参数（拍照来源/文字字数/位置点/计时/语音等）改由配置弹窗直接写入 cfg，此处不再读取面板控件
        val offOn = findViewById<CheckBox>(R.id.cb_offset).isChecked
        cfg.offset.enabled = offOn
        cfg.offset.mode = when (findViewById<RadioGroup>(R.id.rg_offset_mode).checkedRadioButtonId) {
            R.id.rb_mode_b -> "B"; R.id.rb_mode_c -> "C"; else -> "A"
        }
        cfg.offset.nDays = findViewById<EditText>(R.id.et_offset_n).text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 3
        cfg.offset.k = findViewById<EditText>(R.id.et_offset_k).text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        cfg.offset.autoConsume = findViewById<CheckBox>(R.id.cb_offset_auto).isChecked
        // v1.3.12 时间分割收集
        cfg.dayCutoff = if (findViewById<CompoundButton>(R.id.cb_day_cutoff).isChecked)
            DateUtils.parseHHmm(findViewById<Button>(R.id.btn_day_cutoff).text.toString()).coerceAtLeast(1) else -1
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
        // v1.3.19：计时模式/暂停保存/强制模式由配置弹窗直接写入 cfg，此处不再读取面板控件
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
        // v1.3.13 打卡组走独立保存流程
        if (groupMode) { saveGroup(); return }
        val name = findViewById<EditText>(R.id.et_name).text.toString().trim()
        if (name.isBlank()) { toast("请填写打卡名称"); return }
        // v1.3.14 N天打卡强制规则：每日 1 次、每天排期，不参与负打卡/时间段/时间分割/抵消；打卡方式由 ndays 区选择
        if (ndaysMode) {
            cfg.ndaysMode = true
            cfg.ndaysTarget = ndaysTarget
            cfg.dailyLimit = 1
            cfg.scheduleMode = "DAILY"
            cfg.negative = false
            cfg.timeWindowEnabled = false
            cfg.offset.enabled = false
            cfg.dayCutoff = -1
        }
        if (!journalMode && scheduleMode == "WEEKDAYS" && weekDays.isEmpty()) { toast("「每周固定几天」至少选择一天"); return }
        // v1.1.4：先收集 UI 值再校验（此前校验读的是未同步的旧配置，导致"全取消也能保存"）
        collectConfigFromUi()
        // v1.3.14：N天打卡方式以 ndays 区选择为准（collectConfigFromUi 读的是普通方式行，此处覆盖）
        if (ndaysMode) {
            if (ndaysMethods.isEmpty()) ndaysMethods.add(Method.NORMAL.key)
            cfg.methods.clear(); cfg.methods.addAll(ndaysMethods)
        }
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
        if (Method.LOCATION.key in cfg.methods && !cfg.journalMode && cfg.locPoints.isEmpty()) { toast("位置打卡需先获取一个标准位置"); return }
        if (Method.PHOTO.key in cfg.methods && !cfg.photoFromCamera && !cfg.photoFromAlbum) { toast("拍照打卡需至少勾选一种图片来源"); return }
        if (Method.NFC.key in cfg.methods && cfg.nfcTagId.isBlank()) { toast("NFC打卡需先绑定 NFC 标签"); return }
        if (Method.QRCODE.key in cfg.methods && cfg.qrContent.isBlank()) { toast("扫码打卡需先生成专属二维码"); return }
        if (cfg.timeWindowEnabled) {
            val a = DateUtils.parseHHmm(cfg.twStart); val b = DateUtils.parseHHmm(cfg.twEnd)
            if (a < 0 || b < 0 || a >= b) { toast("时间段开始需早于结束（如 05:00 / 08:30），暂不支持跨天"); return }
        }

        val policyInterval = findViewById<RadioButton>(R.id.rb_interval).isChecked
        val intervalN = findViewById<EditText>(R.id.et_interval).text.toString().toIntOrNull()
        // v1.3.0：日记项不设修改策略（FLEX=可随时修改）
        val isDiary = cfg.journalMode || cfg.moodMode
        // v1.3.6：迁移解锁的旧项（非日记 FLEX）编辑保存后保持 FLEX，避免被写回 LOCKED
        val editPolicy = if (isDiary) "FLEX"
            else if (editing?.editPolicy == "FLEX") "FLEX"
            else if (policyInterval) "INTERVAL_N" else "LOCKED"
        val editInterval = if (!isDiary && policyInterval) (intervalN ?: 7) else null
        val now = System.currentTimeMillis()
        thread {
            if (editing != null) {
                val old = editing!!
                val updated = old.copy(
                    name = name, theme = selectedTheme, icon = finalIconKey(),
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
                    icon = finalIconKey(), theme = selectedTheme, sortOrder = repo.itemCount(),
                    editPolicy = editPolicy,
                    editInterval = editInterval,
                    lastEditDate = DateUtils.today(), createdAt = now, updatedAt = now
                )
                val newId = repo.insertItem(item)
                // v1.3.23：新建完成后直接进入该打卡项；回传标记让类型选择页一并退出（返回直达首页打卡选择页）
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_CREATED, true))
                    startActivity(Intent(this, ItemCheckinActivity::class.java)
                        .putExtra(ItemCheckinActivity.EXTRA_ID, newId))
                    finish()
                }
                return@thread
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

}
