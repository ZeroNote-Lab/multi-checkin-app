package com.zerolab.checkin.ui.create

import android.app.TimePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zerolab.checkin.R
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.theme.ThemeManager

/**
 * v1.4.2 优化：打卡组子项编辑器（原 CreateItemActivity 内约 470 行，抽出为独立类）。
 *
 * 组成：RecyclerView 子项列表（长按 ≡ 拖动排序、✕ 删除、点击编辑）
 *  + 新增/编辑底部弹层（名称 / 图标 / 打卡方式 / 更多选项）
 *  + 更多选项区（每日次数、负打卡、时间规则、抵消、自动打卡）。
 *
 * 页面能力通过构造参数回注：主题取当前选中的打卡项主题，Toast 与方式行构建交给宿主。
 */
class GroupMemberEditor(
    private val host: CreateItemActivity,
    private val themeProvider: () -> String,
    private val toast: (String) -> Unit,
    private val buildSwitchRows: (
        LinearLayout, List<Method>, MutableSet<String>, ItemConfig, () -> Unit
    ) -> Unit
) {

    /** 子项草稿：id 非空 = 已入库子项（编辑时更新而非新建） */
    data class GroupSub(var id: Long?, var name: String, val config: ItemConfig, var icon: String? = null)

    val subs = mutableListOf<GroupSub>()
    private var methodChangedRefresh: (() -> Unit)? = null
    private lateinit var adapter: Adapter
    private lateinit var touch: ItemTouchHelper

    fun init() {
        val list = host.findViewById<RecyclerView>(R.id.group_member_list)
        list.layoutManager = LinearLayoutManager(host)
        adapter = Adapter()
        list.adapter = adapter
        touch = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            override fun onMove(rv: RecyclerView, src: RecyclerView.ViewHolder, dst: RecyclerView.ViewHolder): Boolean {
                val from = src.bindingAdapterPosition; val to = dst.bindingAdapterPosition
                if (from < 0 || to < 0 || from == to) return false
                val s = subs.removeAt(from); subs.add(to, s)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = false // 由子项行拖动手柄触发
        })
        touch.attachToRecyclerView(list)
    }

    fun render() {
        if (!::adapter.isInitialized) return
        adapter.notifyDataSetChanged()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        /** 子项行：拖动手柄 ≡ + 主题色图标底 + 名称 + 规则摘要 + ✕ 删除；点击整行编辑 */
        inner class VH(
            val row: View,
            val icon: TextView,
            val name: TextView,
            val summary: TextView,
            val handle: TextView,
            val del: TextView
        ) : RecyclerView.ViewHolder(row)

        override fun getItemCount() = subs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
            val theme = ThemeManager.of(themeProvider())
            val row = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = host.getDrawable(R.drawable.bg_input)
                setPadding(10, 10, 8, 10)
                val lp = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                lp.topMargin = 6
                layoutParams = lp
            }
            val handle = TextView(host).apply {
                text = "≡"
                textSize = 18f
                setTextColor(0xFF9AA5B0.toInt())
                setPadding(0, 0, 8, 0)
            }
            row.addView(handle)
            val iconWrap = LinearLayout(host).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(theme.soft) }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            }
            val icon = TextView(host).apply { textSize = 17f }
            iconWrap.addView(icon)
            row.addView(iconWrap)
            val col = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = dp(10)
                layoutParams = lp
            }
            val name = TextView(host).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1F2430.toInt())
            }
            val summary = TextView(host).apply {
                textSize = 11f
                setTextColor(0xFF6E7F78.toInt())
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(name); col.addView(summary)
            row.addView(col)
            val del = TextView(host).apply {
                text = " ✕"
                textSize = 14f
                setTextColor(0xFFB0534C.toInt())
                setPadding(16, 0, 6, 0)
            }
            row.addView(del)
            return VH(row, icon, name, summary, handle, del)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = subs[position]
            // v1.3.29：子项行显示子项自身图标（未选 default → 主题 emoji 回退）
            h.icon.text = com.zerolab.checkin.theme.IconManager.emojiFor(s.icon ?: "default", themeProvider())
            h.name.text = s.name
            val c = s.config
            val base = methodLabel(c.methods)
            val extra = mutableListOf<String>()
            if (c.dailyLimit > 1) extra.add("每日${c.dailyLimit}次")
            if (c.negative) extra.add("负打卡")
            if (c.timeWindowEnabled) extra.add("时段")
            if (c.dayCutoff >= 0) extra.add("时间分割")
            if (c.offset.enabled) extra.add("🛡️抵消")
            if (Method.AUTO.key in c.methods) extra.add("自动")
            h.summary.text = if (extra.isEmpty()) base else "$base   ·  ${extra.joinToString(" / ")}"
            h.row.setOnClickListener { showSheet(s) }
            h.handle.setOnLongClickListener { touch.startDrag(h); true }
            h.del.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) { subs.removeAt(p); notifyItemRemoved(p) }
            }
        }
    }

    private fun methodLabel(methods: Set<String>): String =
        methods.mapNotNull { Method.of(it) }.joinToString("  ") { "${it.emoji} ${it.label}" }

    /** v1.3.14 新增/编辑子项底部弹层：名称 + 打卡方式多选（互斥置灰） */
    /** v1.3.15：方式区下方加「更多选项」展开普通打卡除日期外的全部功能；步数方式禁用 */
    fun showSheet(s: GroupSub?) {
        fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(host)
        // v1.3.16：禁 BottomSheet 拖拽手势，滚动完全交给内部 ScrollView（修复拉到底再回拉顶部空白消失）
        try {
            sheet.behavior.isDraggable = false
            sheet.behavior.skipCollapsed = true
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        } catch (_: Exception) {}
        // v1.3.15：编辑已有子项直接操作 s.config；新建用局部 ItemConfig，确定时写入 subs
        val cfgSub = s?.config ?: ItemConfig()
        val cur = cfgSub.methods
        if (cur.isEmpty()) cur.add(Method.NORMAL.key)
        // v1.3.15：弹层内容超高（更多选项展开）时需可滚动，外层套 ScrollView
        val scroll = ScrollView(host).apply { isFillViewport = false }
        val wrap = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        wrap.addView(TextView(host).apply {
            text = if (s == null) "新增打卡项" else "编辑打卡项"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F2430.toInt())
        })
        val et = EditText(host).apply {
            hint = "子项名称，如：喝水"
            textSize = 14f
            setPadding(24, 18, 24, 18)
            background = host.getDrawable(R.drawable.bg_input)
            setTextColor(0xFF1F2430.toInt())
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
            if (s != null) setText(s.name)
        }
        et.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        (et.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        wrap.addView(et)
        // v1.3.29：子项自主图标——名称下方图标网格（收起常用 5 个 + 展开全部；复用 IconPicker 的格子构建器）
        wrap.addView(TextView(host).apply {
            text = "图标"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(14), 0, dp(8))
        })
        var subIcon = s?.icon
        var iconExpanded = false
        val iconGrid = android.widget.GridLayout(host).apply {
            columnCount = 6
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun renderSubIcons() {
            iconGrid.removeAllViews()
            val shown = if (iconExpanded) com.zerolab.checkin.theme.IconManager.all
                        else {
                            val commons = com.zerolab.checkin.theme.IconManager.common.take(5).toMutableList()
                            val selIcon = subIcon?.let { k -> com.zerolab.checkin.theme.IconManager.all.firstOrNull { it.key == k } }
                            if (selIcon != null && commons.none { it.key == selIcon.key }) commons[4] = selIcon
                            commons
                        }
            shown.forEach { ic ->
                val cell = IconPicker.cell(host, ic, subIcon)
                cell.setOnClickListener { subIcon = ic.key; renderSubIcons() }
                iconGrid.addView(cell)
            }
            val more = IconPicker.moreCell(host, iconExpanded)
            more.setOnClickListener { iconExpanded = !iconExpanded; renderSubIcons() }
            iconGrid.addView(more)
        }
        renderSubIcons()
        wrap.addView(iconGrid)
        wrap.addView(TextView(host).apply {
            text = "打卡方式"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(14), 0, dp(8))
        })
        val methodBox = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val candidates = listOf(Method.NORMAL, Method.PHOTO, Method.TEXT, Method.VOICE, Method.LOCATION, Method.STEPS, Method.TIMER, Method.QRCODE, Method.NFC)
        // v1.3.23：竖排方式行（对齐自定义打卡布局，无⚙）；勾选可配置方式立即弹配置弹窗
        buildSwitchRows(methodBox, candidates, cur, cfgSub) {
            methodChangedRefresh?.invoke()
        }
        wrap.addView(methodBox)
        // v1.3.15：更多选项入口（展开高级功能）
        val moreBtn = TextView(host).apply {
            text = "更多选项 ▾"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(10), 0, dp(2))
        }
        wrap.addView(moreBtn)
        val moreBox = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        buildMoreSection(moreBox, cfgSub, cur)
        wrap.addView(moreBox)
        moreBtn.setOnClickListener {
            val showing = moreBox.visibility == View.VISIBLE
            moreBox.visibility = if (showing) View.GONE else View.VISIBLE
            moreBtn.text = if (showing) "更多选项 ▾" else "收起 ▴"
        }
        val btnRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        btnRow.addView(TextView(host).apply {
            text = "取消"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { sheet.dismiss() }
        })
        btnRow.addView(TextView(host).apply {
            text = "确定"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val nm = et.text.toString().trim()
                if (nm.isBlank()) { toast("子项名称不能为空"); return@setOnClickListener }
                if (subs.any { it !== s && it.name == nm }) { toast("组内已有同名子项"); return@setOnClickListener }
                if (Method.LOCATION.key in cfgSub.methods && cfgSub.locPoints.isEmpty()) { toast("位置打卡需先配置一个标准位置"); return@setOnClickListener }
                if (Method.NFC.key in cfgSub.methods && cfgSub.nfcTagId.isBlank()) { toast("NFC打卡需先绑定 NFC 标签"); return@setOnClickListener }
                if (Method.QRCODE.key in cfgSub.methods && cfgSub.qrContent.isBlank()) { toast("扫码打卡需先生成专属二维码"); return@setOnClickListener }
                if (Method.PHOTO.key in cfgSub.methods && !cfgSub.photoFromCamera && !cfgSub.photoFromAlbum) { toast("拍照打卡需至少勾选一种图片来源"); return@setOnClickListener }
                if (s == null) subs.add(GroupSub(null, nm, cfgSub, subIcon)) else { s.name = nm; s.icon = subIcon }
                render()
                sheet.dismiss()
            }
        })
        wrap.addView(btnRow)
        scroll.addView(wrap)
        sheet.setContentView(scroll)
        sheet.show()
    }

    /** v1.3.15：子项弹层更多区——普通打卡除「日期选择」外的全部功能（每日次数/负打卡/时间规则/抵消/自动/方式参数） */
    private fun buildMoreSection(box: LinearLayout, c: ItemConfig, cur: MutableSet<String>) {
        fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
        // v1.3.16：更多区可用性刷新（对齐普通打卡页：负打卡/自动打卡锁次数，次数<2 禁用全部完成）
        var refreshMoreConflicts: () -> Unit = {}
        fun divider() { box.addView(View(host).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(6) }
            background = host.getDrawable(R.color.divider)
        }) }
        fun sectionTitle(t: String) { box.addView(TextView(host).apply {
            text = t; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF6E7F78.toInt())
            setPadding(0, dp(10), 0, dp(4))
        }) }
        fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
            val cb = CheckBox(host).apply {
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
        val limitRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        limitRow.addView(TextView(host).apply {
            text = "每日打卡次数"; textSize = 13f; setTextColor(0xFF1F2430.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvLimit = TextView(host).apply {
            text = "${c.dailyLimit}"; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F2430.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val btnLimitMinus = Button(host).apply {
            text = "-"; textSize = 14f; setPadding(0, 0, 0, 0)
            setOnClickListener { c.dailyLimit = (c.dailyLimit - 1).coerceAtLeast(1); tvLimit.text = "${c.dailyLimit}"; refreshMoreConflicts() }
        }
        limitRow.addView(btnLimitMinus)
        limitRow.addView(tvLimit)
        val btnLimitPlus = Button(host).apply {
            text = "+"; textSize = 14f; setPadding(0, 0, 0, 0)
            setOnClickListener { c.dailyLimit = (c.dailyLimit + 1).coerceAtMost(99); tvLimit.text = "${c.dailyLimit}"; refreshMoreConflicts() }
        }
        limitRow.addView(btnLimitPlus)
        box.addView(limitRow)
        // v1.3.16：全部完成开关——对齐普通打卡页可用性（次数>=2 且 单方式 且 非负打卡 且 非自动打卡），否则整行置灰
        val cbAll = CheckBox(host).apply {
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
        val twPanel = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = if (c.timeWindowEnabled) View.VISIBLE else View.GONE
            setPadding(dp(12), 0, 0, 0)
        }
        twPanel.addView(TextView(host).apply { text = "开始 "; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        twPanel.addView(Button(host).apply {
            text = c.twStart; textSize = 13f
            setOnClickListener {
                val hm = c.twStart.split(":")
                TimePickerDialog(host, { _, h, m ->
                    c.twStart = "%02d:%02d".format(h, m); (this as Button).text = c.twStart
                }, hm[0].toInt(), hm[1].toInt(), true).show()
            }
        })
        twPanel.addView(TextView(host).apply { text = " 结束 "; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        twPanel.addView(Button(host).apply {
            text = c.twEnd; textSize = 13f
            setOnClickListener {
                val hm = c.twEnd.split(":")
                TimePickerDialog(host, { _, h, m ->
                    c.twEnd = "%02d:%02d".format(h, m); (this as Button).text = c.twEnd
                }, hm[0].toInt(), hm[1].toInt(), true).show()
            }
        })
        box.addView(twPanel)
        val cutoffPanel = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = if (c.dayCutoff >= 0) View.VISIBLE else View.GONE
            setPadding(dp(12), 0, 0, 0)
        }
        cutoffPanel.addView(TextView(host).apply { text = "分割时间 00:00 – "; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        cutoffPanel.addView(Button(host).apply {
            text = "%02d:%02d".format(if (c.dayCutoff >= 0) c.dayCutoff / 60 else 3, if (c.dayCutoff >= 0) c.dayCutoff % 60 else 0)
            textSize = 13f
            setOnClickListener {
                val h = if (c.dayCutoff >= 0) c.dayCutoff / 60 else 3
                val m = if (c.dayCutoff >= 0) c.dayCutoff % 60 else 0
                TimePickerDialog(host, { _, hh, mm ->
                    c.dayCutoff = hh * 60 + mm; (this as Button).text = "%02d:%02d".format(hh, mm)
                }, h, m, true).show()
            }
        })
        cutoffPanel.addView(TextView(host).apply { text = " 打卡算前一天"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        box.addView(cutoffPanel)
        val twCb = CheckBox(host).apply {
            text = "固定时间段打卡"; textSize = 13f; setTextColor(0xFF1F2430.toInt())
            isChecked = c.timeWindowEnabled
        }
        val cbCutoff = CheckBox(host).apply {
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
        val offPanel = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = if (c.offset.enabled) View.VISIBLE else View.GONE
            setPadding(dp(12), 0, 0, 0)
        }
        val etOffN = EditText(host).apply {
            setText("${c.offset.nDays}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f; background = host.getDrawable(R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val etOffK = EditText(host).apply {
            setText("${c.offset.k}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 13f; background = host.getDrawable(R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        offPanel.addView(TextView(host).apply { text = "连续"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        offPanel.addView(etOffN)
        offPanel.addView(TextView(host).apply { text = " 天得 "; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        offPanel.addView(etOffK)
        offPanel.addView(TextView(host).apply { text = " 次（可累积）"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
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
        methodChangedRefresh = { refreshMoreConflicts() }
    }
}
