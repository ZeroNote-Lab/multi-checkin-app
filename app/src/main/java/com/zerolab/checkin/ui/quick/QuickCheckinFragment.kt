package com.zerolab.checkin.ui.quick

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.theme.ThemeUi
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.engine.DayInfo
import com.zerolab.checkin.engine.DayState
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.theme.ThemeManager
import com.zerolab.checkin.ui.detail.ItemCheckinActivity
import com.zerolab.checkin.ui.settings.AdminMode
import com.zerolab.checkin.ui.detail.ItemDetailActivity
import com.zerolab.checkin.ui.flow.CheckinFlow
import com.zerolab.checkin.util.DateUtils
import com.zerolab.checkin.util.formatLatLng
import java.io.File
import java.util.Calendar
import kotlin.math.min

class QuickCheckinFragment : Fragment() {

    private val repo get() = (requireActivity().application as CheckinApp).repository
    private var item: CheckinItem? = null
    private var overrideItemId: Long? = null
    private var cfg: ItemConfig = ItemConfig()
    private var showYear = 0; private var showMonth = 0
    private lateinit var root: View
    private lateinit var contentView: View
    private lateinit var emptyView: View
    private lateinit var calendar: MonthCalendarView
    private lateinit var btnCheckin: Button
    private lateinit var flow: CheckinFlow
    private var mediaPlayer: MediaPlayer? = null

    override fun onDestroyView() {
        super.onDestroyView()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        NfcHub.unregister(this)
    }

    /** MainActivity 读到 NFC 标签后转发给打卡流程 */
    fun notifyNfc(tagId: String) {
        if (::flow.isInitialized) flow.nfcDetected(tagId)
    }

    /** v1.3.13 打卡组：组页点子项/其它场景切换当前显示的打卡项（支持同 Activity 栈顶复用） */
    fun showItem(id: Long) {
        overrideItemId = id
        item = repo.getItem(id)
        if (item == null) return
        cfg = ItemConfig.parse(item!!.configJson)
        refresh()
    }

    companion object {
        fun newInstance(itemId: Long): QuickCheckinFragment =
            QuickCheckinFragment().apply { arguments = Bundle().apply { putLong("override_id", itemId) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideItemId = arguments?.getLong("override_id", -1L)?.takeIf { it > 0 }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View {
        root = inflater.inflate(R.layout.fragment_quick, c, false)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentView = view.findViewById(R.id.content_view)
        emptyView = view.findViewById(R.id.empty_view)
        calendar = view.findViewById(R.id.calendar)
        btnCheckin = view.findViewById(R.id.btn_checkin)
        flow = CheckinFlow(this) { refresh() }
        view.findViewById<Button>(R.id.btn_go_create).setOnClickListener {
            (requireActivity() as? com.zerolab.checkin.ui.main.MainActivity)?.gotoListTab()
        }
        view.findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { shiftMonth(-1) }
        view.findViewById<ImageButton>(R.id.btn_next).setOnClickListener { shiftMonth(1) }
        view.findViewById<ImageButton>(R.id.btn_menu).setOnClickListener {
            item?.let {
                // v1.3.2：⋮ 直接进详情页
                requireActivity().startActivity(android.content.Intent(requireActivity(), com.zerolab.checkin.ui.detail.ItemDetailActivity::class.java)
                    .putExtra(com.zerolab.checkin.ui.detail.ItemDetailActivity.EXTRA_ID, it.id))
            }
        }
        btnCheckin.setOnClickListener { flow.start(item!!, cfg) }
        // v1.3.14：全局主题换肤（根背景 / 主按钮）
        ThemeUi.apply(requireActivity(), view, listOf(R.id.btn_checkin, R.id.btn_go_create))
        // v1.1.6：列表进入打卡页（override）时隐藏 Fragment 内部顶栏，只留 Activity 顶栏一行（避免双名称/双设置）
        if (overrideItemId != null) view.findViewById<View>(R.id.header_bar).visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // 当前打卡页成为 NFC 回调唯一接收者（快捷页 / 列表进入的打卡页都走这里）
        NfcHub.register(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        NfcHub.unregister(this)
    }

    override fun onStop() {
        super.onStop()
        // v1.3.6：时间打卡强制模式——离开前台（屏幕仍亮）本次计时作废；熄屏（isInteractive=false）不算
        try {
            val pm = requireActivity().getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isInteractive == true && ::flow.isInitialized) flow.onTimerScreenLost()
        } catch (_: Exception) {}
    }

    fun refresh() {
        if (!isAdded || !::contentView.isInitialized) return
        val now = Calendar.getInstance()
        // 首次加载或「今天」已不在当前显示月份（跨月/改日期）时，回到当月
        if (showYear == 0 || showYear != now.get(Calendar.YEAR) || showMonth != now.get(Calendar.MONTH)) {
            showYear = now.get(Calendar.YEAR); showMonth = now.get(Calendar.MONTH)
        }
        val q = overrideItemId?.let { repo.getItem(it) } ?: repo.getQuickItem()
        item = q
        if (q == null) {
            contentView.visibility = View.GONE; emptyView.visibility = View.VISIBLE; return
        }
        contentView.visibility = View.VISIBLE; emptyView.visibility = View.GONE
        cfg = ItemConfig.parse(q.configJson)
        // v1.3.8：负打卡机会结算（页面同步跑，保证点击记录时盾牌已到位，避免与 onResume 异步线程竞态）
        try { CheckinEngine.settleNegativeOffsets(repo) } catch (_: Exception) {}
        autoBackfillMissing()
        val theme = ThemeManager.of(q.theme)
        calendar.themeColor = ThemeUi.current(requireActivity()).accent
        root.findViewById<TextView>(R.id.tv_emoji).text = theme.emoji
        root.findViewById<TextView>(R.id.tv_name).text = q.name
        btnCheckin.background?.setTint(ThemeUi.current(requireActivity()).accent)
        renderMonth()
        renderToday()
        // 记录栏随快捷项/刷新重置为今日，避免残留上一项
        showDayDetail(DateUtils.today())
    }

    private fun shiftMonth(delta: Int) {
        val c = Calendar.getInstance(); c.clear(); c.set(showYear, showMonth, 1)
        c.add(Calendar.MONTH, delta)
        showYear = c.get(Calendar.YEAR); showMonth = c.get(Calendar.MONTH)
        renderMonth()
    }

    private fun renderMonth() {
        val it = item ?: return
        root.findViewById<TextView>(R.id.tv_month).text = DateUtils.monthTitle(showYear, showMonth)
        val c = Calendar.getInstance(); c.clear(); c.set(showYear, showMonth, 1)
        val start = DateUtils.dateOf(c.timeInMillis)
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
        val end = DateUtils.dateOf(c.timeInMillis)
        val records = repo.recordsOfMonth(it.id, start, end)
        val byDay = records.groupBy { r -> r.checkinDate }
        val map = HashMap<String, DayInfo>()
        val groupMode = cfg.groupMode
        // v1.3.15：打卡组用小日历（紧凑高度），普通打卡保持大日历
        val lp = calendar.layoutParams
        lp.height = ((if (groupMode) 188 else 300) * resources.displayMetrics.density).toInt()
        calendar.layoutParams = lp
        // 预填本月每一天：SKIP/FAIL/SUCCESS 等状态对无记录日期同样要渲染（橙色/红色），不能只遍历有记录的天
        var d = start
        while (d <= end) {
            val list = byDay[d] ?: emptyList()
            // v1.3.15：打卡组走组专用状态（4 态，无 SKIP/FAIL 染色），普通/日记走 dayInfo
            map[d] = if (groupMode) CheckinEngine.groupDayInfo(it, d, repo)
                else CheckinEngine.dayInfo(it, d, list.sortedBy { x -> x.checkinTime })
            d = DateUtils.addDays(d, 1)
        }
        // v1.3.15：组日历不显示 ⚡/×n 圆外小标记
        calendar.setData(showYear, showMonth, map, CheckinEngine.isNegative(cfg), { date -> showDayDetail(date) }, showBadges = !groupMode)
        // 图例（v1.1.7：○今日未打卡第一位、彩色圆小号色点、两行间距加宽；v1.3.0：随心记/心情日记按实际改图例）
        val legendTv = root.findViewById<TextView>(R.id.tv_legend)
        if (groupMode) { legendTv.visibility = View.GONE; return }   // v1.3.15：打卡组日历不需要图注
        legendTv.visibility = View.VISIBLE
        val sb = SpannableStringBuilder()
        fun dot(color: Int, label: String) {
            val s = sb.length
            sb.append("●").append(label).append("  ")
            sb.setSpan(ForegroundColorSpan(color), s, s + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append("○今日  ")
        when {
            cfg.moodMode -> {
                // 心情日记：○今日 + 5 档心情色点
                dot(MonthCalendarView.MOOD_COLORS[1], "😄开心"); dot(MonthCalendarView.MOOD_COLORS[2], "🙂不错")
                dot(MonthCalendarView.MOOD_COLORS[3], "😐一般"); dot(MonthCalendarView.MOOD_COLORS[4], "😟低落")
                dot(MonthCalendarView.MOOD_COLORS[5], "😖很差")
            }
            cfg.journalMode -> {
                // 随心记：○今日 + ●已记录（没有缺卡/补签/破戒等）
                dot(0xFF2FBF71.toInt(), "已记录")
            }
            CheckinEngine.isNegative(cfg) -> {
                dot(0xFF2FBF71.toInt(), "已打卡"); dot(0xFFEF5350.toInt(), "破戒"); dot(0xFF4C8DFF.toInt(), "补签"); dot(0xFFF59E0B.toInt(), "无需打卡")
            }
            else -> {
                dot(0xFF2FBF71.toInt(), "已打卡"); dot(0xFFEF5350.toInt(), "缺卡"); dot(0xFF4C8DFF.toInt(), "补签"); dot(0xFFFFC53D.toInt(), "部分完成"); dot(0xFFF59E0B.toInt(), "无需打卡")
            }
        }
        sb.append("\n")
        sb.append(
            when {
                cfg.moodMode -> "⏳今天没记录  😊记录心情  📝可附文字"
                cfg.journalMode -> "⏳今天没记录  ●已记录"
                CheckinEngine.isNegative(cfg) -> "⏳今天尚未打卡  ✅已打卡  ↩补签  ⚡自动  ❌破戒/缺卡"
                else -> "⏳今天尚未打卡  ✅已打卡  ↩补签  ⚡自动  ❌缺卡"
            }
        )
        legendTv.text = sb
    }

    private fun renderToday() {
        val it = item ?: return
        val today = DateUtils.today()
        val todayRecs = repo.recordsOfDay(it.id, today)
        // v1.3.15：打卡组——隐藏日期备注栏，显示组子项区（小日历 + 子项列表，无 box）
        val isGroup = cfg.groupMode
        root.findViewById<View>(R.id.day_detail_bar).visibility = if (isGroup) View.GONE else View.VISIBLE
        val groupTitle = root.findViewById<TextView>(R.id.group_members_title)
        val groupBox = root.findViewById<LinearLayout>(R.id.group_members_box)
        groupTitle.visibility = if (isGroup) View.VISIBLE else View.GONE
        groupBox.visibility = if (isGroup) View.VISIBLE else View.GONE
        // v1.3.0：心情日记次数只计带心情的记录（4 次心情+3 次文字 = 4）
        val journal = cfg.journalMode
        val mood = cfg.moodMode
        val cnt = if (mood) todayRecs.count { r -> r.status == "SUCCESS" && moodOf(r) != null } else todayRecs.size
        val neg = CheckinEngine.isNegative(cfg)
        val interactive = cfg.methods.filter { m -> m != Method.AUTO.key }
        val paused = it.isActive == 0
        val isAuto = Method.AUTO.key in cfg.methods
        val statusView = root.findViewById<TextView>(R.id.tv_today_status)
        val streakView = root.findViewById<TextView>(R.id.tv_streak)
        // v1.2.0：随心记显示"记录天数"（有记录+1、断签不归零）；v1.3.0：心情日记同；普通模式保持连续天数
        val days = if (journal || mood) CheckinEngine.recordDays(it, repo) else CheckinEngine.streak(it, repo)
        val credits = repo.availableCredits(it.id)
        val sb = when {
            mood -> StringBuilder("😊 记录 $days 天")
            journal -> StringBuilder("📔 已记录 $days 天")
            else -> StringBuilder("🔥 连续 $days 天")
        }
        if (credits > 0) sb.append("    🛡️×$credits")
        if (cfg.dailyLimit > 1 && !neg && !journal && !mood) sb.append("    目标：每日${cfg.dailyLimit}次")
        streakView.text = sb.toString()
        // v1.3.0：🔥连续天数文字深灰（红=缺卡语义，用于成就违和）；🔥 emoji 自带橙红不动
        streakView.setTextColor(0xFF4A4A4A.toInt())

        btnCheckin.background?.setTint(ThemeManager.of(it.theme).primary)
        btnCheckin.isEnabled = true
        when {
            // v1.3.13 打卡组：展示子项进度（点击子项进入打卡页），完成全部子项后自动记组成功
            cfg.groupMode -> {
                val members = cfg.groupMembers
                // v1.3.15：组非排期日——今日无需打卡，子项列表仍展示；doneSet 提升到分支外供子项渲染复用
                val scheduled = CheckinEngine.isScheduledDay(it, today)
                val doneSet = if (members.isEmpty() || !scheduled) emptySet()
                    else members.filter { mid ->
                        // v1.3.16：统一 subDayDone——子项须当日全部完成（组合全部方式/单方式次数打满）才算完成
                        val m = repo.getItem(mid) ?: return@filter false
                        CheckinEngine.subDayDone(m, today, repo)
                    }.toSet()
                when {
                    members.isEmpty() -> {
                        statusView.text = "状态：组内暂无子项"
                        btnCheckin.text = "完成全部子项后自动成功"; btnCheckin.isEnabled = false; grayBtn()
                    }
                    !scheduled -> {
                        statusView.text = "状态：今日无需打卡 ✓"
                        btnCheckin.text = "今日无需打卡"; btnCheckin.isEnabled = false; grayBtn()
                    }
                    else -> {
                        val allDone = doneSet.size == members.size
                        statusView.text = if (allDone) "状态：打卡组今日已完成 ✓" else "状态：子项 ${doneSet.size}/${members.size} 已完成"
                        btnCheckin.text = if (allDone) "今日已完成 ✓" else "完成全部子项后自动成功"
                        btnCheckin.isEnabled = false; grayBtn()
                    }
                }
                // v1.3.17：组模式隐藏顶部状态卡（信息由子项卡片+底部按钮表达），连续天数并入子项标题行
                root.findViewById<View>(R.id.status_bar).visibility = View.GONE
                groupTitle.text = "打卡组子项 · 🔥 连续 $days 天"
                groupBox.removeAllViews()
                // v1.3.17：子项三档排序——部分完成(进行中)最上、未打卡居中、已完成沉底；同档保持原序（新一天全部未打卡即恢复默认）
                val sorted = members.map { mid ->
                    mid to (repo.getItem(mid)?.let { CheckinEngine.subDayProgress(it, today, repo) } ?: intArrayOf(0, 1))
                }.sortedWith(compareByDescending<Pair<Long, IntArray>> { p ->
                    val d = p.second[0]; val t = if (p.second[1] <= 0) 1 else p.second[1]
                    when { d > 0 && d < t -> 2; d <= 0 -> 1; else -> 0 }
                }).map { it.first }
                sorted.forEachIndexed { idx, mid ->
                    val m = repo.getItem(mid) ?: return@forEachIndexed
                    val ok = if (!scheduled) true else mid in doneSet
                    groupBox.addView(groupSubCard(m, ok, idx == 0))
                }
            }
            // v1.3.13 N天打卡：严格连续，达成目标后可继续超额
            cfg.ndaysMode -> {
                val streak = CheckinEngine.streak(it, repo)
                val target = cfg.ndaysTarget
                streakView.text = "🎯 ${target} 天挑战 · 已连续 $streak 天（中断清零）"
                if (streak >= target) {
                    statusView.text = "状态：目标 $target 天已达成 ✓"
                    btnCheckin.text = if (cnt == 0) "超额打卡" else "今日已打卡 ✓"
                    btnCheckin.isEnabled = cnt == 0
                } else {
                    statusView.text = "状态：连续 $streak/$target 天"
                    btnCheckin.text = if (cnt == 0) "打卡" else "今日已打卡 ✓"
                    btnCheckin.isEnabled = cnt == 0
                }
            }
            paused -> { statusView.text = "状态：已暂停"; btnCheckin.text = "已暂停"; btnCheckin.isEnabled = false; grayBtn() }
            // v1.2.0 随心记 / v1.3.0 心情日记：记录 / 继续记录（一天可多次，只记成功）
            journal || mood -> {
                statusView.text = if (cnt == 0)
                    (if (mood) "状态：心情日记 · 今天还没记录" else "状态：随心记 · 今天还没记录")
                    else "状态：今日已记录 $cnt 次 ✓"
                btnCheckin.text = if (cnt == 0) "记录" else "继续记录 ($cnt)"
            }
            !CheckinEngine.isScheduledDay(it, today) -> {
                // 无需打卡日：自动完成另一种打卡（橙色标注）
                statusView.text = "状态：今日无需打卡 ✓"
                btnCheckin.text = "今日无需打卡"; btnCheckin.isEnabled = false; grayBtn()
            }
            cfg.timeWindowEnabled && !CheckinEngine.inTimeWindow(cfg) -> {
                // v1.1.6 固定时间段打卡：窗口外禁用并提示（未到/已过）
                val a = DateUtils.parseHHmm(cfg.twStart); val b = DateUtils.parseHHmm(cfg.twEnd)
                val nowMin = DateUtils.nowMinutes()
                // v1.1.7：已过窗口结束时间 → 当天直接判定未打卡（红色），未到 → 保持等待
                val passed = nowMin >= b
                statusView.text = if (passed) "状态：今日未打卡（已过打卡时间）"
                    else "状态：未到打卡时间（${cfg.twStart}–${cfg.twEnd}）"
                btnCheckin.text = if (passed) "已过打卡时间" else "未到打卡时间"
                btnCheckin.isEnabled = false; grayBtn()
            }
            isAuto -> { statusView.text = "状态：自动打卡"; btnCheckin.text = "⚡ 自动打卡，无需操作"; btnCheckin.isEnabled = false; grayBtn() }
            cfg.customNeg -> {
                val hasFail = todayRecs.any { it.status == "FAIL" }
                val hasSucc = todayRecs.any { it.status == "SUCCESS" }
                statusView.text = when {
                    cnt == 0 -> "状态：坚持中（无操作=成功）"
                    hasFail -> "状态：今天未打卡（破戒 ${todayRecs.count { it.status == "FAIL" }} 次）"
                    else -> "状态：今天已打卡 ✓"
                }
                btnCheckin.text = "打卡（按当前时段判定）"
            }
            neg -> {
                // v1.3.8：破戒次数只数 FAIL；有盾牌抵消（OFFSET）时显示补卡状态，不算破戒
                val fails = todayRecs.count { it.status == "FAIL" }
                val shielded = todayRecs.any { it.status == "OFFSET" }
                statusView.text = when {
                    shielded && fails == 0 -> "状态：今日已补卡（破戒被盾牌抵消）"
                    shielded -> "状态：今日已补卡 + $fails 次破戒"
                    fails == 0 -> "状态：坚持中（无操作=成功）"
                    else -> "状态：今日已记录 $fails 次破戒"
                }
                btnCheckin.text = when {
                    fails == 0 && !shielded -> "记录一次（破戒）"
                    fails == 0 -> "再记录一次（破戒）"
                    else -> "再记录一次（$fails）"
                }
            }
            // v1.2.0：时间打卡暂停进行中——按钮继续计时（续 PAUSED 进度）
            todayRecs.any { it.status == "PAUSED" } && todayRecs.none { it.status == "SUCCESS" } && !isAuto -> {
                statusView.text = "状态：计时进行中（已暂停保存，可继续）"
                btnCheckin.text = "继续计时"
            }
            !neg && interactive.size > 1 -> {
                // v1.2.0：组合完成判定按 comboRequired（0=全部）
                val req = if (cfg.comboRequired in 1..interactive.size) cfg.comboRequired else interactive.size
                val done = interactive.count { m -> todayRecs.any { r -> r.status == "SUCCESS" && r.extraJson?.contains(m) == true } }
                if (done >= req) { statusView.text = "状态：今日已完成 ✓"; btnCheckin.text = "今日已完成 ✓"; btnCheckin.isEnabled = false; grayBtn() }
                else { statusView.text = "状态：$done/$req"; btnCheckin.text = if (done == 0) "打卡" else "继续打卡 ($done/$req)" }
            }
            cfg.dailyLimit == 1 -> {
                if (cnt == 0) { statusView.text = "状态：未打卡"; btnCheckin.text = "打卡" } else { statusView.text = "状态：已打卡 ✓"; btnCheckin.text = "已完成 ✓"; btnCheckin.isEnabled = false; grayBtn() }
            }
            cfg.dailyLimit < 0 -> {
                // v1.1.7：次数无限——打一次即完成当天（绿/红），按钮可继续打卡累加次数
                if (cnt == 0) { statusView.text = "状态：未打卡"; btnCheckin.text = "打卡" }
                else { statusView.text = "状态：已打卡 $cnt 次 ✓"; btnCheckin.text = "继续打卡（$cnt）" }
            }
            else -> {
                if (cnt >= cfg.dailyLimit) { statusView.text = "状态：已完成 $cnt/${cfg.dailyLimit} ✓"; btnCheckin.text = "今日已完成 ✓"; btnCheckin.isEnabled = false; grayBtn() }
                else { statusView.text = "状态：$cnt/${cfg.dailyLimit}"; btnCheckin.text = if (cnt == 0) "打卡" else "继续打卡 ($cnt/${cfg.dailyLimit})" }
            }
        }
    }

    private fun grayBtn() { btnCheckin.background?.setTint(0xFFB6BCC9.toInt()) }

    /** v1.3.0：记录的心情档位（extraJson.mood，1~5；无心情返回 null） */
    private fun moodOf(r: com.zerolab.checkin.data.entity.CheckinRecord): Int? = try {
        org.json.JSONObject(r.extraJson ?: "{}").optInt("mood", 0).takeIf { it in 1..5 }
    } catch (_: Exception) { null }

    /** v1.3.0：心情日记当天多条带心情记录时，备注栏第一行画心情折线图 */
    private fun moodLineView(recs: List<com.zerolab.checkin.data.entity.CheckinRecord>): View? {
        if (!cfg.moodMode || !cfg.moodChart) return null
        val moods = recs.filter { moodOf(it) != null }.mapNotNull { moodOf(it) }
        if (moods.size < 2) return null
        return MoodLineView(requireContext()).apply {
            setMoods(moods)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (76 * resources.displayMetrics.density).toInt())
            lp.bottomMargin = (6 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
    }

    /** 点击日期：备注栏显示次数/时间/文字/缩略图/语音（v1.1.6：媒体内联到对应记录行，不再统一堆底部） */
    /** v1.3.14 打卡组子项卡片：主题图标 + 名称 + 打卡方式 + 连续天数 + 状态徽章 */
    /** v1.3.15 去掉 box 背景框，排版参考打卡管理列表页（左右/上下间距） */
    /** v1.3.16 分布对齐第0级卡片：48dp 圆形图标底 + 16sp 名称 + 灰底方式小标签 + 连续天数 + 右侧状态徽章 */
    private fun groupSubCard(m: CheckinItem, ok: Boolean, first: Boolean): View {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        val subTheme = ThemeManager.of(m.theme)
        val subCfg = ItemConfig.parse(m.configJson)
        val streak = try { CheckinEngine.streak(m, repo) } catch (_: Exception) { 0 }
        // v1.3.16 方案D：白底圆角卡片（18dp 圆角 + 柔和阴影），卡片间 10dp 间距
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            elevation = dp(2).toFloat()
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (first) lp.topMargin = dp(14)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            setOnClickListener {
                startActivity(Intent(requireContext(), ItemCheckinActivity::class.java)
                    .putExtra(ItemCheckinActivity.EXTRA_ID, m.id))
            }
        }
        // 左侧：48dp 圆形图标底（主题软色，与第0级卡片同分布）
        val iconWrap = LinearLayout(requireContext()).apply {
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(subTheme.soft)
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        iconWrap.addView(TextView(requireContext()).apply { text = subTheme.emoji; textSize = 22f })
        row.addView(iconWrap)
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = dp(12)
            layoutParams = lp
        }
        col.addView(TextView(requireContext()).apply {
            text = m.name
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF1F2430.toInt())
        })
        // 第二行：灰底方式小标签 + 连续天数（同第0级卡片：bg_tag 小字标签 + 🔥 X 天）
        val metaRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        val typeTv = TextView(requireContext()).apply {
            val labels = subCfg.methods.mapNotNull { Method.of(it)?.label?.removeSuffix("打卡") }
            text = labels.joinToString("+").ifBlank { "普通" }
            textSize = 11f
            setTextColor(0xFF6E7F78.toInt())
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFFF1F3F7.toInt())
            }
        }
        metaRow.addView(typeTv)
        metaRow.addView(TextView(requireContext()).apply {
            text = "🔥 $streak 天"
            textSize = 12f
            setTextColor(0xFFEF5350.toInt())
            setPadding(dp(8), 0, 0, 0)
        })
        col.addView(metaRow)
        row.addView(col)
        // 右侧：40dp 进度环（方案D）——已打卡=绿色满环 ✓，未完成=橙色进度 done/total
        val pr = CheckinEngine.subDayProgress(m, DateUtils.today(), repo)
        val done = pr[0]
        val total = if (pr[1] <= 0) 1 else pr[1]
        val ring = RingView(requireContext()).apply {
            progress = if (ok) 1f else done.toFloat() / total
            centerText = if (ok) "✓" else "$done/$total"
            ringColor = if (ok) 0xFF2FBF71.toInt() else 0xFFF59E0B.toInt()
            textColor = if (ok) 0xFF2FBF71.toInt() else 0xFF4A4A4B.toInt()
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        row.addView(ring)
        return row
    }

    private fun showDayDetail(date: String) {
        val it = item ?: return
        // v1.3.13 打卡组：详情区常驻子项列表（由 renderToday 渲染），点击日历日期不覆盖
        if (ItemConfig.parse(it.configJson).groupMode) return
        val recs = repo.recordsOfDay(it.id, date)
        val title = root.findViewById<TextView>(R.id.tv_detail_title)
        val body = root.findViewById<TextView>(R.id.tv_detail_body)
        val recordsBox = root.findViewById<LinearLayout>(R.id.detail_records_box)
        // 先清空记录区，防止切换日期时残留上一日期内容
        recordsBox.removeAllViews()
        body.visibility = View.VISIBLE
        // 无需打卡日：单独展示
        if (!CheckinEngine.isScheduledDay(it, date)) {
            title.text = "📅 $date   ·   无需打卡"
            body.text = "🟠 该日无需打卡，自动视为完成，不中断连续天数"
            return
        }
        // 标题带状态符号（美化备注栏）；v1.3.0：心情日记次数只计带心情的记录；v1.3.8：负打卡补卡不算在次数里（只数破戒 FAIL）
        val neg = CheckinEngine.isNegative(cfg)
        val shownCount = when {
            cfg.moodMode -> recs.count { moodOf(it) != null }
            neg -> recs.count { it.status == "FAIL" }
            else -> recs.size
        }
        val statePrefix = when {
            recs.any { it.status == "OFFSET" } -> "↩"
            recs.any { it.isAuto == 1 } -> "⚡"
            recs.any { it.status == "FAIL" } -> "❌"
            recs.any { it.status == "PAUSED" } -> "⏸"
            else -> "✅"
        }
        title.text = if (neg) "$statePrefix $date  破戒 $shownCount 次"
            else "$statePrefix $date  共 $shownCount 条记录"
        if (recs.isEmpty()) {
            body.text = when {
                // v1.2.0：随心记 / v1.3.0：心情日记无记录日不显示缺卡文案（不染色）
                cfg.moodMode -> "😊 该日无记录（心情日记不记缺卡）"
                cfg.journalMode -> "📔 该日无记录（随心记不记缺卡）"
                date == DateUtils.today() -> if (neg) "⏳ 今天暂无操作（坚持中）" else "⏳ 今天尚未打卡"
                neg -> "✅ 已打卡（当天无操作）"
                else -> "❌ 未打卡（缺卡）"
            }
            // v1.3.7：负打卡无操作日=成功日，不提供补签；正常模式缺卡日提供手动补签
            if (!neg) offerManualBackfill(it, date, neg)
            return
        }
        // v1.3.0：心情折线图置于记录区第一行（当天 ≥2 条带心情记录且开关开启）
        moodLineView(recs)?.let { recordsBox.addView(it) }
        // 有记录：逐条渲染（文本 + 内联媒体）
        body.visibility = View.GONE
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        recs.forEach { r ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = (10 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }
            val prefix = when {
                r.status == "OFFSET" -> "↩ 补签"
                r.isAuto == 1 -> "⚡ 自动"
                r.status == "FAIL" -> "❌ 破戒"
                r.status == "PAUSED" -> "⏸ 暂停"   // v1.2.0：时间打卡暂停留痕
                else -> "✅"
            }
            val sb = StringBuilder("$prefix ${fmt.format(java.util.Date(r.checkinTime))}")
            // v1.3.0：心情日记——心情 emoji 紧跟时间（多次记录每条可见）
            moodOf(r)?.let { sb.append("   ${MonthCalendarView.MOOD_EMOJIS[it - 1]}") }
            // v1.2.0：时间打卡记录附带计时信息（暂停剩余/已走，完成用时）
            if (r.extraJson != null) {
                try {
                    val o = org.json.JSONObject(r.extraJson!!)
                    val tm = o.optString("timerMode", "")
                    if (tm == "COUNTUP") {
                        if (r.status == "PAUSED") sb.append("   正计时暂停 已走 %02d:%02d / 目标 %02d:%02d".format(
                            o.optInt("elapsedSec") / 60, o.optInt("elapsedSec") % 60, o.optInt("targetSec") / 60, o.optInt("targetSec") % 60))
                        else if (o.has("elapsedSec")) sb.append("   用时 %02d:%02d".format(o.optInt("elapsedSec") / 60, o.optInt("elapsedSec") % 60))
                    } else if (tm == "COUNTDOWN") {
                        if (r.status == "PAUSED") sb.append("   倒计时暂停 剩余 %02d:%02d".format(
                            o.optInt("remainSec") / 60, o.optInt("remainSec") % 60))
                        else if (o.has("totalSec")) sb.append("   倒计时 %02d:%02d".format(o.optInt("totalSec") / 60, o.optInt("totalSec") % 60))
                    }
                } catch (_: Exception) {}
            }
            // v1.2.2：无内容记录（无计时/文字/位置/媒体）追加完成方式说明，避免备注栏只有时间空荡荡
            val hasTimer = try { org.json.JSONObject(r.extraJson ?: "{}").has("timerMode") } catch (_: Exception) { false }
            val hasMedia = (r.photoPath?.isNotBlank() == true) || (r.voicePath?.isNotBlank() == true)
            if (!hasTimer && r.textContent.isNullOrBlank() && r.latitude == null && r.longitude == null && !hasMedia && moodOf(r) == null) {
                val label = when {
                    r.isAuto == 1 -> "自动打卡"
                    else -> {
                        val m = try { org.json.JSONObject(r.extraJson ?: "{}").optString("method", "") } catch (_: Exception) { "" }
                        if (m.isNotBlank()) Method.of(m)?.label ?: m
                        else cfg.methods.firstOrNull { it != Method.AUTO.key }?.let { Method.of(it)?.label ?: it } ?: "打卡"
                    }
                }
                sb.append("   完成$label")
            }
            // v1.3.0：去"文字："前缀；v1.3.1：心情日记的文字紧跟心情 emoji 同行，其余仍换行
            if (!r.textContent.isNullOrBlank()) {
                if (moodOf(r) != null) sb.append("  ${r.textContent}")
                else sb.append("\n   ${r.textContent}")
            }
            if (r.latitude != null && r.longitude != null) {
                val locName = try { org.json.JSONObject(r.extraJson ?: "{}").optString("locName", "") } catch (_: Exception) { "" }
                sb.append("\n   📍 位置：${if (locName.isNotBlank()) locName else formatLatLng(r.latitude!!, r.longitude!!)}")
            }
            // v1.3.17：超级管理员——长按记录弹出操作菜单
            if (AdminMode.isOn) {
                row.setOnLongClickListener {
                    showRecordAdminMenu(r, date); true
                }
            }
            val tv = TextView(requireContext()).apply {
                text = sb.toString(); textSize = 13f
                setTextColor(0xFF1F2430.toInt()); setLineSpacing(3f * resources.displayMetrics.scaledDensity, 1f)
                // v1.3.0：普通打卡文字超 5 行折叠（日记全文显示，不折叠）
                if (!cfg.journalMode && !cfg.moodMode && r.textContent?.length ?: 0 > 120) {
                    maxLines = 5
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }
            // v1.3.0：折叠时追加「…展开」点击看全文（仅普通打卡长文）
            if (!cfg.journalMode && !cfg.moodMode && (r.textContent?.length ?: 0) > 120) {
                val btnExpand = TextView(requireContext()).apply {
                    text = "…展开"
                    textSize = 12f
                    setTextColor(0xFF4C8DFF.toInt())
                    setPadding(0, 2, 0, 0)
                }
                btnExpand.setOnClickListener {
                    tv.maxLines = Int.MAX_VALUE
                    tv.ellipsize = null
                    btnExpand.visibility = View.GONE
                }
                row.addView(tv)
                row.addView(btnExpand)
            } else {
                row.addView(tv)
            }
            // 媒体内联：该条照片缩略图 + 语音按钮（位于本条文字下方，与其他记录对齐）
            val mediaRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 0)
            }
            if (r.photoPath?.isNotBlank() == true && File(r.photoPath).exists()) mediaRow.addView(thumbView(File(r.photoPath)))
            if (r.voicePath?.isNotBlank() == true && File(r.voicePath).exists()) mediaRow.addView(voicePlayButton(r.voicePath!!))
            if (mediaRow.childCount > 0) row.addView(mediaRow)
            recordsBox.addView(row)
        }
        // v1.3.7：负打卡破戒日（过去 + 有 FAIL 且未补签）提供手动补签（蓝色圆圈 ↩）
        if (neg && date < DateUtils.today() && recs.any { it.status == "FAIL" } && recs.none { it.status == "OFFSET" }) {
            offerManualBackfill(it, date, neg)
        }
    }

    /** 图片缩略图，点击弹大图 */
    private fun thumbView(f: File): View {
        val iv = ImageView(requireContext())
        val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
        iv.setImageBitmap(bmp)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val px = (92 * resources.displayMetrics.density).toInt()
        iv.layoutParams = LinearLayout.LayoutParams(px, px).apply { marginEnd = 8; gravity = Gravity.CENTER_VERTICAL }
        iv.setOnClickListener {
            if (bmp == null) { Toast.makeText(requireContext(), "图片无法解码", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val big = ImageView(requireContext()).apply {
                setImageBitmap(bmp); adjustViewBounds = true
                maxHeight = (860 * resources.displayMetrics.density).toInt(); maxWidth = (700 * resources.displayMetrics.density).toInt()
            }
            AlertDialog.Builder(requireContext()).setTitle("打卡照片").setView(big)
                .setPositiveButton("关闭", null).show()
        }
        return iv
    }

    /** 语音播放/停止按钮 */
    private fun voicePlayButton(path: String): View {
        val btn = Button(requireContext())
        btn.text = "🎤 播放"; btn.textSize = 12f
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF39C5BB.toInt())
        btn.setTextColor(android.graphics.Color.WHITE)
        btn.setPadding(28, 14, 28, 14)
        btn.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = 8; gravity = Gravity.CENTER_VERTICAL }
        btn.setOnClickListener {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                try { mp.stop(); mp.release() } catch (_: Exception) {}
                mediaPlayer = null; btn.text = "🎤 播放"
            } else {
                try {
                    val np = MediaPlayer()
                    np.setDataSource(path)
                    np.prepare()
                    np.setOnCompletionListener {
                        try { it.release() } catch (_: Exception) {}
                        if (mediaPlayer === it) mediaPlayer = null
                        btn.text = "🎤 播放"
                    }
                    np.start()
                    mediaPlayer = np
                    btn.text = "⏹ 停止"
                } catch (e: Exception) { Toast.makeText(requireContext(), "语音播放失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
        return btn
    }

    /** 过去缺卡/破戒日且有抵消机会时，提供手动补签（v1.3.7：负打卡破戒日也可补签） */
    private fun offerManualBackfill(it: CheckinItem, date: String, neg: Boolean) {
        if (date >= DateUtils.today()) return
        if (!cfg.offset.enabled) return
        val avail = repo.availableCredits(it.id)
        if (avail <= 0) return
        val what = if (neg) "破戒" else "缺卡"
        AlertDialog.Builder(requireContext())
            .setTitle("补签 $date")
            .setMessage("该日$what。消耗 1 次抵消机会（当前剩余 $avail 次）补签？")
            .setNegativeButton("取消", null)
            .setPositiveButton("补签") { _, _ ->
                if (CheckinEngine.offsetBackfill(it, repo, date)) {
                    Toast.makeText(requireContext(), "补签成功", Toast.LENGTH_SHORT).show()
                    refresh()
                } else Toast.makeText(requireContext(), "没有可用抵消机会", Toast.LENGTH_SHORT).show()
            }.show()
    }

    /** 自动消耗模式：刷新时把创建日至昨天的缺卡按可用机会依次补签 */
    private fun autoBackfillMissing() {
        val it = item ?: return
        if (!cfg.offset.enabled || !cfg.offset.autoConsume) return
        // v1.3.8：负打卡不自动补历史破戒日——破戒当天由盾牌直接抵消（蓝卡），历史破戒只支持手动补签
        if (CheckinEngine.isNegative(cfg)) return
        val created = DateUtils.dateOf(it.createdAt)
        var d = DateUtils.addDays(DateUtils.today(), -1)
        var guard = 0
        while (d >= created && guard++ < 400) {
            if (repo.availableCredits(it.id) <= 0) break
            val recs = repo.recordsOfDay(it.id, d)
            val st = CheckinEngine.dayInfo(it, d, recs).state
            if (st == DayState.FAIL) CheckinEngine.offsetBackfill(it, repo, d)
            d = DateUtils.addDays(d, -1)
        }
    }
    /** v1.3.17：超级管理员——记录操作菜单（按记录内容动态生成） */
    private fun showRecordAdminMenu(r: CheckinRecord, date: String) {
        val opts = mutableListOf<String>()
        opts += "修改状态"
        if (!r.textContent.isNullOrBlank()) opts += "修改文字"
        if (r.latitude != null && r.longitude != null) opts += "删除位置"
        if (r.photoPath?.isNotBlank() == true) opts += "删除图片"
        if (r.voicePath?.isNotBlank() == true) opts += "删除语音"
        opts += "删除整条记录"
        AlertDialog.Builder(requireContext())
            .setTitle("管理员 · 记录操作（$date）")
            .setItems(opts.toTypedArray()) { _, w ->
                when (opts[w]) {
                    "修改状态" -> changeRecordStatus(r)
                    "修改文字" -> editRecordText(r)
                    "删除位置" -> {
                        repo.updateRecord(r.copy(latitude = null, longitude = null, extraJson = clearLocName(r.extraJson)))
                        toast("已删除位置信息")
                        refresh()
                    }
                    "删除图片" -> {
                        try { File(r.photoPath!!).delete() } catch (_: Exception) {}
                        repo.updateRecord(r.copy(photoPath = null))
                        toast("已删除该条记录的图片")
                        refresh()
                    }
                    "删除语音" -> {
                        try { File(r.voicePath!!).delete() } catch (_: Exception) {}
                        repo.updateRecord(r.copy(voicePath = null))
                        toast("已删除该条记录的语音")
                        refresh()
                    }
                    "删除整条记录" -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("删除打卡记录")
                            .setMessage("确定删除 $date 这条打卡记录吗？\n删除后当天完成度会相应变化。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                repo.deleteRecord(r.id)
                                toast("已删除该条记录")
                                refresh()
                            }.show()
                    }
                }
            }.show()
    }

    /** 修改记录状态（成功 / 补签 / 破戒） */
    private fun changeRecordStatus(r: CheckinRecord) {
        val cur = when (r.status) {
            "OFFSET" -> "补签"
            "FAIL" -> "破戒"
            else -> "成功"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("修改状态（当前：$cur）")
            .setItems(arrayOf("成功", "补签", "破戒")) { _, w ->
                val st = when (w) { 1 -> "OFFSET"; 2 -> "FAIL"; else -> "SUCCESS" }
                repo.updateRecord(r.copy(status = st))
                toast("已改为「${arrayOf("成功", "补签", "破戒")[w]}」")
                refresh()
            }.show()
    }

    /** 修改记录文字内容 */
    private fun editRecordText(r: CheckinRecord) {
        val et = android.widget.EditText(requireContext()).apply {
            setText(r.textContent ?: "")
            hint = "输入新的打卡内容"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("修改文字内容")
            .setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val txt = et.text.toString()
                repo.updateRecord(r.copy(textContent = if (txt.isBlank()) null else txt))
                toast("文字内容已修改")
                refresh()
            }.show()
    }

    /** 删除位置时同步清理 extraJson 里的 locName */
    private fun clearLocName(extra: String?): String? {
        if (extra.isNullOrBlank()) return extra
        return try {
            val o = org.json.JSONObject(extra)
            if (o.has("locName")) o.remove("locName")
            if (o.length() == 0) null else o.toString()
        } catch (_: Exception) { extra }
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}

/** v1.3.16 方案D：打卡组子项进度环（浅灰背景环 + 前景进度弧 + 中心文字） */
private class RingView(ctx: android.content.Context) : View(ctx) {
    var progress: Float = 0f
    var centerText: String = ""
    var ringColor: Int = 0xFF2FBF71.toInt()
    var textColor: Int = 0xFF2FBF71.toInt()
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        color = 0xFFF1E9ED.toInt()
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = dp(10f)
    }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = (min(width, height) / 2f) - fgPaint.strokeWidth / 2f - dp(1f)
        canvas.drawCircle(cx, cy, r, bgPaint)
        fgPaint.color = ringColor
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep > 0f) canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, sweep, false, fgPaint)
        textPaint.color = textColor
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(centerText, cx, baseline, textPaint)
    }
}
