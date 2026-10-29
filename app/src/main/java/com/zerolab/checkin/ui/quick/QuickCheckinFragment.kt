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
import com.zerolab.checkin.util.ImageUtil
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
    private var playingVoice: VoicePlayUi? = null  // v1.4.0：详情页语音播放全局状态（按钮+进度条）
    private var progressHandler: android.os.Handler? = null
    private var progressRunnable: Runnable? = null

    // v1.4.2 优化：页面数据快照。数据库计算（结算 / 自动补签 / 连续天数 / 月历状态）
    // 全部放在单线程后台队列里串行执行，主线程只做渲染，避免长连续或打卡组时进页面卡顿。
    private val snapshotExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "checkin-snapshot").apply { isDaemon = true }
        }
    private var snapshotGen = 0

    // v1.4.0：管理员「添加图片」必须在 STARTED 前注册 launcher，用目标记录挂起
    private var addPhotoTarget: CheckinRecord? = null
    private val addPhotosLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
        val r = addPhotoTarget
        addPhotoTarget = null
        if (r == null || uris.isNullOrEmpty()) return@registerForActivityResult
        val existing = recordPhotoList(r)
        val picked = uris.take((9 - existing.size).coerceAtLeast(0))
        if (picked.isEmpty()) { toast("最多 9 张"); return@registerForActivityResult }
        Thread {
            val outDir = File(requireContext().filesDir, "checkin_photos/${r.itemId}")
            val saved = mutableListOf<String>()
            picked.forEachIndexed { i, uri ->
                try {
                    val src = File(requireContext().cacheDir, "admin_album_${System.currentTimeMillis()}_$i.jpg")
                    requireContext().contentResolver.openInputStream(uri).use { it?.copyTo(src.outputStream()) }
                    ImageUtil.compressWebp(src, outDir)?.let { saved.add(it.absolutePath) }
                } catch (_: Exception) {}
            }
            requireActivity().runOnUiThread {
                if (saved.isEmpty()) { toast("图片读取失败"); return@runOnUiThread }
                applyPhotoList(r, existing + saved)
                toast("已添加 ${saved.size} 张")
                refresh()
            }
        }.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        NfcHub.unregister(this)
    }

    override fun onDestroy() {
        // v1.4.2 优化：停止后台快照线程，避免持有已销毁页面
        snapshotExec.shutdownNow()
        super.onDestroy()
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

        /** 最近打开过的打卡页快照：同一项/同一月再次进入时先秒开，再后台刷新 */
        private val snapshotCache = android.util.LruCache<String, PageSnapshot>(8)

        /** 进程被杀后的磁盘快照：冷启动也能先按上次界面秒开 */
        private fun snapshotFile(ctx: android.content.Context, key: String): File =
            File(File(ctx.cacheDir, "checkin_snapshots"), key.replace('|', '_') + ".snap")

        private fun loadSnapshotDisk(ctx: android.content.Context, key: String): PageSnapshot? {
            val f = snapshotFile(ctx, key)
            if (!f.exists()) return null
            return try {
                java.io.ObjectInputStream(f.inputStream().buffered()).use {
                    @Suppress("UNCHECKED_CAST")
                    it.readObject() as PageSnapshot
                }
            } catch (_: Exception) {
                try { f.delete() } catch (_: Exception) {}
                null
            }
        }

        private fun saveSnapshotDisk(ctx: android.content.Context, key: String, snap: PageSnapshot) {
            try {
                val f = snapshotFile(ctx, key)
                f.parentFile?.mkdirs()
                java.io.ObjectOutputStream(f.outputStream().buffered()).use { it.writeObject(snap) }
            } catch (e: Exception) {
                android.util.Log.w("SnapshotCache", "save failed: $key", e)
            }
        }
    }

    /** 当前 UI 已经画出来的快照（用于后台刷新结果未变化时跳过第二次绘制） */
    private var appliedKey: String? = null
    private var appliedSnap: PageSnapshot? = null

    /** 快照缓存 key：打卡项 + 年月 + 周视图锚点 */
    private fun snapshotKey(itemId: Long, year: Int, month: Int, weekAnchor: String?): String =
        "$itemId|$year|$month|${weekAnchor ?: ""}"

    /** 无缓存时只显示占位，避免同步查询 / 构建记录视图造成卡顿；真实记录由快照回来渲染一次 */
    private fun showDayDetailPlaceholder(date: String) {
        val title = root.findViewById<TextView>(R.id.tv_detail_title)
        val body = root.findViewById<TextView>(R.id.tv_detail_body)
        val recordsBox = root.findViewById<LinearLayout>(R.id.detail_records_box)
        recordsBox.removeAllViews()
        title.text = "📅 $date"
        body.visibility = View.VISIBLE
        body.text = "正在加载记录…"
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
        // v1.4.0：随心记悬浮"写一条"按钮（LinearLayout容器）
        val btnWrite = view.findViewById<android.view.View>(R.id.btn_journal_write)
        btnWrite.setOnClickListener { flow.start(item!!, cfg) }
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
        // v1.4.0：周/月视图切换折线
        view.findViewById<TextView>(R.id.btn_toggle_week)?.setOnClickListener { v ->
            calendar.weekMode = !calendar.weekMode
            (v as TextView).text = if (calendar.weekMode) "⌄" else "⌃"
            updateCalendarHeight()
            // v1.4.0：立即重绘标题与日历（周显示日期范围，月显示月份），无需再点箭头
            if (!calendar.weekMode) {
                // 展开月视图：同步到锚点所在月，避免月历与周不一致
                val ac = java.util.Calendar.getInstance()
                ac.time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(calendar.weekAnchor)
                showYear = ac.get(java.util.Calendar.YEAR); showMonth = ac.get(java.util.Calendar.MONTH)
            }
            requestSnapshot(resetSelection = false)
        }
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
        // v1.4.0：离开页面（切tab/进编辑页/退出）立即停止语音播放并复位UI，避免声音继续、按钮状态错乱
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        playingVoice?.let {
            it.btn.text = "▶"
            it.seekBar.progress = 0
        }
        playingVoice = null
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        progressRunnable = null
        progressHandler = null
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
            contentView.visibility = View.GONE; emptyView.visibility = View.VISIBLE
            btnCheckin.visibility = View.GONE
            return
        }
        contentView.visibility = View.VISIBLE; emptyView.visibility = View.GONE
        cfg = ItemConfig.parse(q.configJson)
        // v1.4.0：随心记内部主题（默认日记本风，后续可扩展多套）
        val journalTheme = object {
            val bg = 0xFFFAF7F2.toInt()
            val accent = 0xFFC97B4A.toInt()
            val card = 0xFFFFFFFF.toInt()
            val textMain = 0xFF3D342B.toInt()
            val textSub = 0xFF8A7B6B.toInt()
            val lightBg = 0xFFF0E8DC.toInt()
            val voiceTrack = 0xFFE8D5C0.toInt()
        }
        if (cfg.journalMode && !cfg.moodMode) {
            root.setBackgroundColor(journalTheme.bg)
            // 状态栏同色
            val window = requireActivity().window
            window.statusBarColor = journalTheme.bg
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            // 日历默认周视图 + 动态高度（ScrollView里布局写死300dp，需代码覆盖）
            calendar.weekMode = true
            calendar.compactMonth = true
            updateCalendarHeight()
            // 折线按钮只在随心记显示
            view?.findViewById<TextView>(R.id.btn_toggle_week)?.visibility = View.VISIBLE
            view?.findViewById<TextView>(R.id.btn_toggle_week)?.text = "⌄"
            // v1.4.0：悬浮"写一条"按钮替代内嵌打卡按钮；v1.4.2：暂停项不显示
            view?.findViewById<android.view.View>(R.id.btn_journal_write)?.visibility =
                if (q.isActive == 1) View.VISIBLE else View.GONE
            btnCheckin.visibility = View.GONE
            // 滚动区底部留白，避免最后一条记录被悬浮按钮挡住
            val pad = resources.displayMetrics.density.toInt()
            view?.findViewById<LinearLayout>(R.id.scroll_content)?.setPadding(14 * pad, 14 * pad, 14 * pad, 96 * pad)
        } else {
            calendar.compactMonth = false
            calendar.weekMode = false
            updateCalendarHeight()
            view?.findViewById<TextView>(R.id.btn_toggle_week)?.visibility = View.GONE
            view?.findViewById<android.view.View>(R.id.btn_journal_write)?.visibility = View.GONE
            btnCheckin.visibility = View.VISIBLE
            // v1.4.2：从随心记切回普通项时恢复悬浮按钮的圆角背景 / 白字 / 阴影
            btnCheckin.setBackgroundResource(R.drawable.bg_float_button)
            btnCheckin.setTextColor(0xFFFFFFFF.toInt())
            btnCheckin.elevation = 8f * resources.displayMetrics.density
            // v1.4.2：打卡按钮改为底部悬浮，滚动区留出底部空间，避免最后一条记录被挡住
            val pad = resources.displayMetrics.density.toInt()
            view?.findViewById<LinearLayout>(R.id.scroll_content)?.setPadding(14 * pad, 14 * pad, 14 * pad, 96 * pad)
        }
        // v1.4.2 优化：负打卡结算与自动补签改到后台快照里执行（原来在主线程逐日查库）
        val theme = ThemeManager.of(q.theme)
        // v1.3.26：日历选中描边=打卡项主题色；打卡按钮同样用打卡项主题色（与描边一致）
        calendar.themeColor = if (cfg.journalMode && !cfg.moodMode) journalTheme.accent else theme.primary
        root.findViewById<TextView>(R.id.tv_emoji).text = com.zerolab.checkin.theme.IconManager.emojiFor(q.icon, q.theme)
        root.findViewById<TextView>(R.id.tv_name).text = q.name
        btnCheckin.background?.setTint(theme.primary)
        // v1.4.0：随心记日记本风——按钮改白色悬浮胶囊（在主题色设置之后覆盖）
        if (cfg.journalMode && !cfg.moodMode) {
            btnCheckin.setBackgroundColor(journalTheme.card)
            btnCheckin.setTextColor(journalTheme.accent)
            btnCheckin.elevation = 6f * resources.displayMetrics.density
            btnCheckin.text = "记录"
        }
        // v1.4.2 优化：月历 / 今日状态 / 记录栏的数据统一由后台快照计算完成后一次性渲染，
        // 选中日期默认落在「归属日」（时间分割时凌晨为前一天）
        // v1.4.3 优化：先把日历格子 / 标题 / 记录栏画出来，状态颜色与统计等后台快照回来再补，
        // 避免进入页面时先看到一片空白
        val effTodayNow = CheckinEngine.effectiveToday(cfg)
        calendar.selectedDate = effTodayNow
        calendar.setData(
            showYear, showMonth, emptyMap(), CheckinEngine.isNegative(cfg),
            ::onCalendarDate, showBadges = !cfg.groupMode
        )
        renderMonthTitle()
        val key = snapshotKey(q.id, showYear, showMonth, if (calendar.weekMode) calendar.weekAnchor else null)
        val cached = snapshotCache.get(key)
            ?: loadSnapshotDisk(requireContext().applicationContext, key)?.also { snapshotCache.put(key, it) }
        if (cached != null && cached.effToday == effTodayNow) {
            // 命中缓存：立即按上次内容绘制，后台快照刷新时若数据没变就不重复绘制
            appliedKey = key
            appliedSnap = cached
            applySnapshot(cached, resetSelection = true)
        } else {
            appliedKey = null
            appliedSnap = null
            showDayDetailPlaceholder(effTodayNow)
        }
        requestSnapshot(resetSelection = true)
    }

    /** 日历日期点击：周视图点到相邻月份时切换显示月份，再刷新记录栏 */
    private fun onCalendarDate(date: String) {
        calendar.weekAnchor = date
        if (calendar.weekMode) {
            try {
                val dc = Calendar.getInstance()
                dc.time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)
                val cy = dc.get(Calendar.YEAR); val cm = dc.get(Calendar.MONTH)
                if (cy != showYear || cm != showMonth) {
                    showYear = cy; showMonth = cm
                    requestSnapshot(resetSelection = false)
                }
            } catch (_: Exception) {}
        }
        showDayDetail(date)
    }

    /** 月历标题：周视图显示本周日期范围，月视图显示月份 */
    private fun renderMonthTitle() {
        val tv = root.findViewById<TextView>(R.id.tv_month)
        if (calendar.weekMode) {
            try {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val cal = Calendar.getInstance(); cal.time = fmt.parse(calendar.weekAnchor)
                val dow = cal.get(Calendar.DAY_OF_WEEK) // 周日=1
                val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
                cal.add(Calendar.DAY_OF_MONTH, -back)
                val monday = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 6)
                val sunday = cal.timeInMillis
                val mFmt = java.text.SimpleDateFormat("M月d日", java.util.Locale.US)
                val mondayStr = mFmt.format(java.util.Date(monday))
                val sundayCal = Calendar.getInstance(); sundayCal.timeInMillis = sunday
                val mondayCal = Calendar.getInstance(); mondayCal.timeInMillis = monday
                tv.text = if (sundayCal.get(Calendar.MONTH) == mondayCal.get(Calendar.MONTH)) {
                    "$mondayStr – ${sundayCal.get(Calendar.DAY_OF_MONTH)}日"
                } else {
                    "$mondayStr – ${mFmt.format(java.util.Date(sunday))}"
                }
            } catch (_: Exception) {}
        } else {
            tv.text = DateUtils.monthTitle(showYear, showMonth)
        }
    }

    // ---------- v1.4.2 优化：页面数据快照 ----------

    /** 组子项在快照里算好的进度，渲染时不再查库 */
    private data class GroupMemberSnap(
        val item: CheckinItem,
        val done: Int,
        val total: Int,
        val streak: Int,
        val doneToday: Boolean
    ) : java.io.Serializable

    /** 一屏所需的所有数据：全部在后台线程计算，UI 线程只负责画 */
    private data class PageSnapshot(
        val item: CheckinItem,
        val effToday: String,
        val year: Int,
        val month: Int,
        val streak: Int,
        val credits: Int,
        val todayRecs: List<CheckinRecord>,
        val monthMap: Map<String, DayInfo>,
        val groupScheduled: Boolean,
        val groupMembers: List<GroupMemberSnap>
    ) : java.io.Serializable

    /**
     * 触发一次后台快照。多次调用以最后一次为准（generation 校验丢弃过期结果），
     * 队列为单线程，避免并发写抵消 / 补签记录。
     * @param resetSelection 是否把日期选中与记录栏重置到归属日（整页刷新时 true，翻月时 false）
     */
    private fun requestSnapshot(resetSelection: Boolean) {
        val q = item ?: return
        val c = cfg
        val year = showYear
        val month = showMonth
        // v1.4.2 修复：周视图可能跨月，把显示周也带进快照
        val weekAnchor = if (calendar.weekMode) calendar.weekAnchor else null
        val cacheKey = snapshotKey(q.id, year, month, weekAnchor)
        val gen = ++snapshotGen
        snapshotExec.execute {
            if (gen != snapshotGen) return@execute
            val snap = try {
                gatherSnapshot(q, c, year, month, weekAnchor)
            } catch (e: Exception) {
                android.util.Log.w("SnapshotCache", "gather failed", e)
                null
            }
            if (snap == null) {
                // 快照失败也不能让记录栏一直停在「正在加载」：退回直接读取当天记录
                activity?.runOnUiThread {
                    if (isAdded && gen == snapshotGen) {
                        try { showDayDetail(CheckinEngine.effectiveToday(c)) } catch (_: Exception) {}
                    }
                }
                return@execute
            }
            // 写入磁盘快照：杀进程后再打开同一项/同一月可先秒开
            context?.applicationContext?.let { try { saveSnapshotDisk(it, cacheKey, snap) } catch (_: Exception) {} }
            if (gen != snapshotGen) return@execute
            activity?.runOnUiThread {
                if (!isAdded || gen != snapshotGen) return@runOnUiThread
                if (item?.id != snap.item.id) return@runOnUiThread
                snapshotCache.put(cacheKey, snap)
                // 命中的缓存与刷新结果完全一致时不再重建视图，避免“缓存画一次 + 刷新再画一次”
                if (appliedKey == cacheKey && appliedSnap == snap) return@runOnUiThread
                appliedKey = cacheKey
                appliedSnap = snap
                applySnapshot(snap, resetSelection)
            }
        }
    }

    /** 后台线程：执行写库结算，并算出整屏需要的只读数据 */
    private fun gatherSnapshot(q: CheckinItem, c: ItemConfig, year: Int, month: Int, weekAnchor: String?): PageSnapshot {
        // 负打卡机会结算（幂等补发）与自动补签（可能写记录），原来都在主线程，这里统一后台
        try { CheckinEngine.settleNegativeOffsets(repo) } catch (_: Exception) {}
        autoBackfillMissing(q, c)

        val effToday = CheckinEngine.effectiveToday(c)
        val streak = CheckinEngine.streak(q, repo)
        val credits = repo.availableCredits(q.id)
        val todayRecs = repo.recordsOfDay(q.id, effToday)

        // 月历状态：普通项逐日走 dayInfo（纯内存计算，月份记录只查一次），打卡组逐日走 groupDayInfo
        val startCal = Calendar.getInstance()
        startCal.clear(); startCal.set(year, month, 1)
        val start = DateUtils.dateOf(startCal.timeInMillis)
        startCal.set(Calendar.DAY_OF_MONTH, startCal.getActualMaximum(Calendar.DAY_OF_MONTH))
        val end = DateUtils.dateOf(startCal.timeInMillis)
        val monthMap = HashMap<String, DayInfo>()
        if (c.groupMode) {
            var d = start
            while (d <= end) {
                monthMap[d] = CheckinEngine.groupDayInfo(q, d, repo)
                d = DateUtils.addDays(d, 1)
            }
        } else {
            val byDay = repo.recordsOfMonth(q.id, start, end).groupBy { r -> r.checkinDate }
            var d = start
            while (d <= end) {
                monthMap[d] = CheckinEngine.dayInfo(q, d, (byDay[d] ?: emptyList()).sortedBy { r -> r.checkinTime })
                d = DateUtils.addDays(d, 1)
            }
        }

        // v1.4.2 修复：周视图可能跨月，把当前显示周（周一~周日）里不属于本月的日期也补进状态表，
        // 否则跨月那几天没有 DayInfo，日历不显示颜色、点击后也像「没反应」。
        if (weekAnchor != null) {
            try {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val anchorTs = fmt.parse(weekAnchor)?.time
                if (anchorTs != null) {
                    val wc = Calendar.getInstance()
                    wc.timeInMillis = anchorTs
                    val dow = wc.get(Calendar.DAY_OF_WEEK)
                    val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
                    wc.add(Calendar.DAY_OF_MONTH, -back)
                    val ws = DateUtils.dateOf(wc.timeInMillis)
                    wc.add(Calendar.DAY_OF_MONTH, 6)
                    val we = DateUtils.dateOf(wc.timeInMillis)
                    if (c.groupMode) {
                        var d = ws
                        while (d <= we) {
                            if (monthMap[d] == null) monthMap[d] = CheckinEngine.groupDayInfo(q, d, repo)
                            d = DateUtils.addDays(d, 1)
                        }
                    } else {
                        val weekByDay = repo.recordsOfMonth(q.id, ws, we).groupBy { r -> r.checkinDate }
                        var d = ws
                        while (d <= we) {
                            if (monthMap[d] == null) {
                                monthMap[d] = CheckinEngine.dayInfo(
                                    q, d, (weekByDay[d] ?: emptyList()).sortedBy { r -> r.checkinTime }
                                )
                            }
                            d = DateUtils.addDays(d, 1)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val isGroup = c.groupMode && q.isActive == 1
        val scheduled = if (!c.groupMode) true else CheckinEngine.isScheduledDay(q, effToday)
        val members = if (!isGroup) emptyList() else c.groupMembers.mapNotNull { mid ->
            val m = repo.getItem(mid) ?: return@mapNotNull null
            val pr = CheckinEngine.subDayProgress(m, effToday, repo)
            GroupMemberSnap(
                item = m,
                done = pr[0],
                total = pr[1],
                streak = try { CheckinEngine.streak(m, repo) } catch (_: Exception) { 0 },
                doneToday = pr[0] >= pr[1]
            )
        }
        return PageSnapshot(q, effToday, year, month, streak, credits, todayRecs, monthMap, scheduled, members)
    }

    /** 主线程：把快照画到界面上 */
    private fun applySnapshot(snap: PageSnapshot, resetSelection: Boolean) {
        renderMonthData(snap)
        renderToday(snap)
        // 随心记按钮样式要在 renderToday（会 setTint 主题色）之后覆盖
        if (cfg.journalMode && !cfg.moodMode) {
            btnCheckin.background?.setTint(0xFFFFFFFF.toInt())
            btnCheckin.setTextColor(0xFFC97B4A.toInt())
            btnCheckin.elevation = 6f * resources.displayMetrics.density
            btnCheckin.text = "记录"
        }
        // 翻月时保留当前选中日期；整页刷新时回到归属日并同步记录栏
        if (resetSelection) {
            calendar.selectedDate = snap.effToday
            showDayDetail(snap.effToday)
        }
    }


    private fun shiftMonth(delta: Int) {
        if (calendar.weekMode) {
            // v1.4.0：周视图箭头切周（锚点±7天），并同步月份到锚点所在月
            val c = Calendar.getInstance(); c.time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(calendar.weekAnchor)
            c.add(Calendar.DAY_OF_MONTH, delta * 7)
            val newAnchor = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(c.time)
            calendar.weekAnchor = newAnchor
            showYear = c.get(Calendar.YEAR); showMonth = c.get(Calendar.MONTH)
            requestSnapshot(resetSelection = false)
        } else {
            val c = Calendar.getInstance(); c.clear(); c.set(showYear, showMonth, 1)
            c.add(Calendar.MONTH, delta)
            showYear = c.get(Calendar.YEAR); showMonth = c.get(Calendar.MONTH)
            // v1.4.0：月视图切月，锚点跟随到目标月（保留原日号，超出则钳到月末），收起后显示该月对应周
            val ac = Calendar.getInstance()
            ac.time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(calendar.weekAnchor)
            val oldDay = ac.get(Calendar.DAY_OF_MONTH)
            ac.set(Calendar.YEAR, showYear); ac.set(Calendar.MONTH, showMonth)
            val maxDay = ac.getActualMaximum(Calendar.DAY_OF_MONTH)
            ac.set(Calendar.DAY_OF_MONTH, oldDay.coerceAtMost(maxDay))
            calendar.weekAnchor = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(ac.time)
            requestSnapshot(resetSelection = false)
        }
    }

    /** v1.4.0：动态设置日历高度（ScrollView里布局写死300dp需代码覆盖） */
    private fun updateCalendarHeight() {
        val d = resources.displayMetrics.density
        val h = when {
            calendar.weekMode -> (34 + 44)  // 周视图78dp
            calendar.compactMonth -> 220     // 日记模式月视图220dp
            else -> 300                     // 普通项月视图300dp
        }
        calendar.layoutParams = calendar.layoutParams.apply { height = (h * d).toInt() }
    }

    /** 主线程：月历标题 / 图例 / 圆圈状态（状态数据来自后台快照） */
    private fun renderMonthData(snap: PageSnapshot) {
        // v1.4.0：周视图标题显示本周日期范围（10月19日 – 25日）
        renderMonthTitle()
        val groupMode = cfg.groupMode
        // v1.3.15：打卡组用小日历（紧凑高度），普通打卡保持大日历
        val lp = calendar.layoutParams
        lp.height = ((if (groupMode) 188 else 300) * resources.displayMetrics.density).toInt()
        calendar.layoutParams = lp
        // v1.3.15：组日历不显示 ⚡/×n 圆外小标记
        calendar.setData(snap.year, snap.month, snap.monthMap, CheckinEngine.isNegative(cfg), ::onCalendarDate, showBadges = !groupMode)
        // 图例（v1.1.7：○今日未打卡第一位、彩色圆小号色点、两行间距加宽；v1.3.0：随心记/心情日记按实际改图例）
        val legendTv = root.findViewById<TextView>(R.id.tv_legend)
        if (groupMode) { legendTv.visibility = View.GONE; return }   // v1.3.15：打卡组日历不需要图注
        // v1.3.33：随心记日历下方图例整行隐藏（有没有记录看日历圆圈即可，去掉"今日没记录/已记录"行）
        if (cfg.journalMode) { legendTv.visibility = View.GONE; return }
        // v1.3.21：N天打卡（习惯打卡）只保留核心状态，去掉不适用的补签/部分完成/无需打卡/自动
        if (cfg.ndaysMode) {
            val s2 = SpannableStringBuilder()
            // v1.3.26：去掉第一行第一个的○今日
            val d1 = s2.length; s2.append("●").append("已打卡").append("  ")
            s2.setSpan(ForegroundColorSpan(0xFF2FBF71.toInt()), d1, d1 + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val d2 = s2.length; s2.append("●").append("缺卡")
            s2.setSpan(ForegroundColorSpan(0xFFEF5350.toInt()), d2, d2 + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            legendTv.text = s2
            return
        }
        legendTv.visibility = View.VISIBLE
        val sb = SpannableStringBuilder()
        fun dot(color: Int, label: String) {
            val s = sb.length
            sb.append("●").append(label).append("  ")
            sb.setSpan(ForegroundColorSpan(color), s, s + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // v1.3.26：去掉第一行第一个的○今日
        when {
            cfg.moodMode -> {
                // 心情日记：○今日 + 5 档心情色点
                dot(MonthCalendarView.MOOD_COLORS[1], "😄开心"); dot(MonthCalendarView.MOOD_COLORS[2], "🙂不错")
                dot(MonthCalendarView.MOOD_COLORS[3], "😐一般"); dot(MonthCalendarView.MOOD_COLORS[4], "😟低落")
                dot(MonthCalendarView.MOOD_COLORS[5], "😖很差")
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
                CheckinEngine.isNegative(cfg) -> "⏳今天尚未打卡  ✅已打卡  ↩补签  ⚡自动  ❌破戒/缺卡"
                else -> "⏳今天尚未打卡  ✅已打卡  ↩补签  ⚡自动  ❌缺卡"
            }
        )
        legendTv.text = sb
    }

    /** 主线程：今日状态卡 / 连续天数 / 打卡按钮文案（数据来自后台快照） */
    private fun renderToday(snap: PageSnapshot) {
        val it = snap.item
        // v1.4.2：时间分割下「今天」取归属日（凌晨 0–2 点打卡归前一天），状态卡与记录都以归属日为准
        val today = snap.effToday
        val naturalToday = DateUtils.today()
        val todayRecs = snap.todayRecs
        val paused = it.isActive == 0
        // v1.3.15：打卡组——隐藏日期备注栏，显示组子项区（小日历 + 子项列表，无 box）
        val isGroup = cfg.groupMode && !paused
        // v1.4.2 优化：备注栏按「是否打卡组」隐藏（暂停的打卡组此前会残留一个空备注卡）
        root.findViewById<View>(R.id.day_detail_bar).visibility = if (cfg.groupMode) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.status_bar).visibility = if (isGroup) View.GONE else View.VISIBLE
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
        val isAuto = Method.AUTO.key in cfg.methods
        val statusView = root.findViewById<TextView>(R.id.tv_today_status)
        val streakView = root.findViewById<TextView>(R.id.tv_streak)
        // v1.2.0：随心记显示"记录天数"（有记录+1、断签不归零）；v1.3.0：心情日记同；普通模式保持连续天数
        // v1.3.33：随心记/心情日记是最简单的日记本，不显示任何天数统计（隐藏统计行）
        val days = snap.streak   // 组子项标题与状态卡共用（后台快照算好）
        if (journal || mood) {
            streakView.visibility = View.GONE
        } else {
            streakView.visibility = View.VISIBLE
            val credits = snap.credits
            val sb = StringBuilder("🔥 连续 $days 天")
            if (credits > 0) sb.append("    🛡️×$credits")
            if (cfg.dailyLimit > 1 && !neg) sb.append("    目标：每日${cfg.dailyLimit}次")
            streakView.text = sb.toString()
            // v1.3.0：🔥连续天数文字深灰（红=缺卡语义，用于成就违和）；🔥 emoji 自带橙红不动
            streakView.setTextColor(0xFF4A4A4A.toInt())
        }

        // v1.3.26：打卡按钮=打卡项主题色（与日历选中描边一致）
        btnCheckin.background?.setTint(ThemeManager.of(it.theme).primary)
        btnCheckin.isEnabled = true
        when {
            // v1.4.2：暂停项一律不可打卡，且排在所有类型判断之前（此前习惯打卡会漏判）
            paused -> {
                statusView.text = "状态：已暂停（恢复后才能打卡）"
                btnCheckin.text = "已暂停"; btnCheckin.isEnabled = false; grayBtn()
            }
            // v1.3.13 打卡组：展示子项进度（点击子项进入打卡页），完成全部子项后自动记组成功
            cfg.groupMode -> {
                // v1.4.2 优化：子项进度 / 连续天数都在后台快照里算好，这里只负责画
                val members = snap.groupMembers
                val scheduled = snap.groupScheduled
                val doneCount = if (scheduled) members.count { it.doneToday } else members.size
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
                        val allDone = doneCount == members.size
                        statusView.text = if (allDone) "状态：打卡组今日已完成 ✓" else "状态：子项 ${doneCount}/${members.size} 已完成"
                        btnCheckin.text = if (allDone) "今日已完成 ✓" else "完成全部子项后自动成功"
                        btnCheckin.isEnabled = false; grayBtn()
                    }
                }
                // v1.3.17：组模式隐藏顶部状态卡（信息由子项卡片+底部按钮表达），连续天数并入子项标题行
                root.findViewById<View>(R.id.status_bar).visibility = View.GONE
                groupTitle.text = "打卡组子项 · 🔥 连续 $days 天"
                groupBox.removeAllViews()
                // v1.3.17：子项三档排序——部分完成(进行中)最上、未打卡居中、已完成沉底；同档保持原序（新一天全部未打卡即恢复默认）
                val sorted = members.sortedWith(compareByDescending<GroupMemberSnap> { m ->
                    val t = if (m.total <= 0) 1 else m.total
                    when { m.done > 0 && m.done < t -> 2; m.done <= 0 -> 1; else -> 0 }
                })
                sorted.forEachIndexed { idx, m ->
                    val ok = if (!scheduled) true else m.doneToday
                    groupBox.addView(groupSubCard(m, ok, idx == 0))
                }
            }
            // v1.3.13 N天打卡：严格连续，达成目标后可继续超额
            cfg.ndaysMode -> {
                // v1.3.30：习惯打卡支持固定时间段——窗口外禁用并提示（对齐普通打卡）
                if (cfg.timeWindowEnabled && !CheckinEngine.inTimeWindow(cfg)) {
                    val a = DateUtils.parseHHmm(cfg.twStart); val b = DateUtils.parseHHmm(cfg.twEnd)
                    val nowMin = DateUtils.nowMinutes()
                    val passed = nowMin >= b
                    statusView.text = if (passed) "状态：今日未打卡（已过打卡时间）"
                        else "状态：未到打卡时间（${cfg.twStart}–${cfg.twEnd}）"
                    btnCheckin.text = if (passed) "已过打卡时间" else "未到打卡时间"
                    btnCheckin.isEnabled = false; grayBtn()
                } else {
                    val streak = snap.streak
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
            }
            // v1.2.0 随心记 / v1.3.0 心情日记：记录 / 继续记录（一天可多次，只记成功）
            journal || mood -> {
                // v1.3.33：去掉记录次数（焦虑感），按钮恒为"记录"
                statusView.text = if (cnt == 0)
                    (if (mood) "状态：心情日记 · 今天还没记录" else "状态：随心记 · 今天还没记录")
                    else "状态：今日已记录 ✓"
                btnCheckin.text = "记录"
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
        // v1.4.2：时间分割下明确提示归属日（如凌晨 1 点打卡显示「归属 10月25日」而不是记到今天）
        if (!paused && today != naturalToday) {
            statusView.text = statusView.text.toString() + "（归属 ${monthDay(today)}）"
        }
    }

    /** v1.4.2：yyyy-MM-dd → M月d日 */
    private fun monthDay(date: String): String = try {
        "${date.substring(5, 7).toInt()}月${date.substring(8, 10).toInt()}日"
    } catch (_: Exception) { date }

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
    private fun groupSubCard(snap: GroupMemberSnap, ok: Boolean, first: Boolean): View {
        fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
        val m = snap.item
        val subTheme = ThemeManager.of(m.theme)
        val subCfg = ItemConfig.parse(m.configJson)
        val streak = snap.streak
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
        iconWrap.addView(TextView(requireContext()).apply { text = com.zerolab.checkin.theme.IconManager.emojiFor(m.icon, m.theme); textSize = 22f })
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
        val done = snap.done
        val total = if (snap.total <= 0) 1 else snap.total
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
        // v1.4.2：停用期间的日期不显示缺卡，直接提示已停用
        if (recs.isEmpty() && CheckinEngine.isPausedOn(it, date)) {
            title.text = "⏸ $date   ·   已停用"
            body.text = "该日打卡项处于停用状态，不记缺卡；重新启用后从启用日开始记录"
            if (AdminMode.isOn) {
                val citem = it
                title.setOnLongClickListener { adminDayMenu(citem, date); true }
                body.setOnLongClickListener { adminDayMenu(citem, date); true }
                recordsBox.addView(adminAddButton(it, date))
            }
            return
        }
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
            recs.isEmpty() -> "○"
            recs.any { it.status == "OFFSET" } -> "↩"
            recs.any { it.isAuto == 1 } -> "⚡"
            recs.any { it.status == "FAIL" } -> "❌"
            recs.any { it.status == "PAUSED" } -> "⏸"
            else -> "✅"
        }
        // v1.3.33：随心记/心情日记标题不显示记录条数（焦虑感），负打卡/普通模式保留次数
        title.text = when {
            neg -> "$statePrefix $date  破戒 $shownCount 次"
            cfg.moodMode || cfg.journalMode -> "$statePrefix $date"
            else -> "$statePrefix $date  共 $shownCount 条记录"
        }
        // v1.4.2：管理员模式下，长按日期标题或备注即可补录（已有记录的日期也能继续补多条）
        if (AdminMode.isOn) {
            val citem = it
            title.text = title.text.toString() + "  🛠"
            title.setOnLongClickListener { adminDayMenu(citem, date); true }
            body.setOnLongClickListener { adminDayMenu(citem, date); true }
        }
        if (recs.isEmpty()) {
            body.text = when {
                // v1.2.0：随心记 / v1.3.0：心情日记无记录日不显示缺卡文案（不染色）
                cfg.moodMode -> "😊 该日无记录（心情日记不记缺卡）"
                cfg.journalMode -> "📔 该日无记录（随心记不记缺卡）"
                date == CheckinEngine.effectiveToday(it) -> if (neg) "⏳ 今天暂无操作（坚持中）" else "⏳ 今天尚未打卡"
                neg -> "✅ 已打卡（当天无操作）"
                else -> "❌ 未打卡（缺卡）"
            }
            // v1.3.7：负打卡无操作日=成功日，不提供补签；正常模式缺卡日提供手动补签
            if (!neg) offerManualBackfill(it, date, neg)
            if (AdminMode.isOn) recordsBox.addView(adminAddButton(it, date))
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
            // v1.4.0：随心记块流记录——按 blocks[] 数组渲染，不再走老的单字段拼接
            val hasBlocks = try {
                org.json.JSONObject(r.extraJson ?: "{}").has("blocks")
            } catch (_: Exception) { false }
            if (hasBlocks) {
                val ts = TextView(requireContext()).apply {
                    text = "✅ ${fmt.format(java.util.Date(r.checkinTime))}"
                    textSize = 12f
                    setTextColor(0xFF8A7B6B.toInt())
                    setPadding(0, 0, 0, 6)
                }
                row.addView(ts)
                renderJournalBlocks(r)?.let { row.addView(it) }
                // v1.4.2：管理员长按块流记录 → 记录级菜单（可编辑日记内容）
                if (AdminMode.isOn) row.setOnLongClickListener { showRecordAdminMenu(r, date); true }
                recordsBox.addView(row)
                return@forEach
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
            // 媒体内联：v1.4.0 多图三列宫格（全宽）+ 语音按钮（位于本条文字下方）
            val photos = recordPhotoList(r)
            if (photos.isNotEmpty()) row.addView(photoGridView(photos))
            val mediaRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 0)
            }
            if (r.voicePath?.isNotBlank() == true && File(r.voicePath).exists()) mediaRow.addView(voicePlayButton(r.voicePath!!, 0))
            if (mediaRow.childCount > 0) row.addView(mediaRow)
            recordsBox.addView(row)
        }
        // v1.4.2：管理员补录入口固定显示在记录区底部（多次打卡项可逐条补到满足次数）
        if (AdminMode.isOn) recordsBox.addView(adminAddButton(it, date))
        // v1.3.7：负打卡破戒日（过去 + 有 FAIL 且未补签）提供手动补签（蓝色圆圈 ↩）
        if (neg && date < CheckinEngine.effectiveToday(it) && recs.any { it.status == "FAIL" } && recs.none { it.status == "OFFSET" }) {
            offerManualBackfill(it, date, neg)
        }
    }

    /** v1.4.0：读取一条记录的全部图片（extraJson.photoPaths，回退 photoPath），只返回文件存在的 */
    private fun recordPhotoList(r: com.zerolab.checkin.data.entity.CheckinRecord): List<String> {
        val list = mutableListOf<String>()
        try {
            val o = org.json.JSONObject(r.extraJson ?: "{}")
            val arr = o.optJSONArray("photoPaths")
            if (arr != null) for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
            }
        } catch (_: Exception) {}
        if (list.isEmpty() && !r.photoPath.isNullOrBlank()) list.add(r.photoPath)
        return list.filter { File(it).exists() }
    }

    /** v1.4.0：朋友圈式三列宫格（全宽，按容器宽度自适应；点任意一张全屏查看）。onLongClick 可选（管理模式长按删除）。 */
    private fun photoGridView(paths: List<String>, onLongClick: ((String) -> Unit)? = null): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val gap = dp(6)
        // 详情页容器两侧留白合计约 68dp（外层16+卡片14+wrap4）
        val cellW = (resources.displayMetrics.widthPixels - dp(68) - gap * 2) / 3
        var row: LinearLayout? = null
        paths.forEachIndexed { j, path ->
            if (j % 3 == 0) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, gap)
                }
                grid.addView(row)
            }
            val bmp = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(cellW, cellW).apply {
                    marginEnd = if (j % 3 < 2) gap else 0
                }
                setOnClickListener {
                    if (onLongClick != null) {
                        // v1.4.0：管理删除模式下点图片即弹出删除确认（与"点图片删除"提示一致）
                        onLongClick.invoke(path)
                    } else {
                        val big = ImageView(ctx).apply {
                            setImageBitmap(bmp); adjustViewBounds = true
                            maxHeight = dp(600); maxWidth = dp(320)
                        }
                        AlertDialog.Builder(ctx).setView(big).setPositiveButton("关闭", null).show()
                    }
                }
                if (onLongClick != null) setOnLongClickListener { onLongClick.invoke(path); true }
            }
            row?.addView(iv)
        }
        return grid
    }

    /** 语音播放UI holder：同时记住按钮和进度条，切换时旧UI能复位 */
    private data class VoicePlayUi(val btn: TextView, val seekBar: android.widget.SeekBar)

    /** 语音播放/停止按钮（v1.4.0：可拖动 SeekBar，和编辑页一致） */
    private fun voicePlayButton(path: String, seconds: Int): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFFF0E8DC.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }
        val playBtn = TextView(ctx).apply {
            text = "▶"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xFFC97B4A.toInt())
            gravity = Gravity.CENTER
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) }
        }
        val dur = TextView(ctx).apply {
            text = if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else "语音"
            setTextColor(0xFF8A7B6B.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        }
        // v1.4.0：可拖动 SeekBar
        val seekBar = android.widget.SeekBar(ctx).apply {
            max = seconds.coerceAtLeast(1) * 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFC97B4A.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8D5C0.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFC97B4A.toInt())
            splitTrack = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        row.addView(playBtn); row.addView(dur); row.addView(seekBar)

        fun stopProgress() {
            progressRunnable?.let { progressHandler?.removeCallbacks(it) }
            progressRunnable = null
            progressHandler = null
        }
        fun startProgress(mp: MediaPlayer, sb: android.widget.SeekBar) {
            stopProgress()
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            progressHandler = h
            val r = object : Runnable {
                override fun run() {
                    try {
                        if (mp.isPlaying) {
                            sb.progress = mp.currentPosition
                            h.postDelayed(this, 200)
                        }
                    } catch (_: Exception) {}
                }
            }
            progressRunnable = r
            h.postDelayed(r, 200)
        }
        // 复位旧播放UI
        fun resetOld() {
            playingVoice?.let {
                it.btn.text = "▶"
                it.seekBar.progress = 0
            }
            playingVoice = null
        }

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                try { mediaPlayer?.seekTo(seekBar.progress) } catch (_: Exception) {}
            }
        })

        playBtn.setOnClickListener {
            val ui = VoicePlayUi(playBtn, seekBar)
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying && playingVoice?.btn === playBtn) {
                try { mp.stop(); mp.release() } catch (_: Exception) {}
                mediaPlayer = null; playBtn.text = "▶"
                seekBar.progress = 0
                resetOld()
                stopProgress()
            } else {
                // 先停止前一个播放并复位旧UI
                try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
                mediaPlayer = null
                resetOld()
                stopProgress()
                try {
                    val np = MediaPlayer()
                    np.setDataSource(path)
                    np.prepare()
                    seekBar.max = np.duration.coerceAtLeast(1)
                    seekBar.progress = 0
                    np.setOnCompletionListener {
                        try { it.release() } catch (_: Exception) {}
                        if (mediaPlayer === it) mediaPlayer = null
                        playBtn.text = "▶"
                        seekBar.progress = 0
                        playingVoice = null
                        stopProgress()
                    }
                    np.start()
                    mediaPlayer = np
                    playBtn.text = "⏸"
                    playingVoice = ui
                    startProgress(np, seekBar)
                } catch (e: Exception) { Toast.makeText(ctx, "语音播放失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
        return row
    }

    /** v1.4.0：随心记块流渲染——按 extraJson.blocks[] 顺序渲染所有块 */
    private fun renderJournalBlocks(r: CheckinRecord): View? {
        val blocks = try {
            val o = org.json.JSONObject(r.extraJson ?: "{}")
            o.optJSONArray("blocks") ?: return null
        } catch (_: Exception) { return null }
        if (blocks.length() == 0) return null

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }

        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> {
                    val t = b.optString("text")
                    if (t.isNotBlank()) {
                        val tv = android.widget.TextView(ctx).apply {
                            text = t; textSize = 14f
                            setTextColor(0xFF3D342B.toInt())
                            setLineSpacing(2f * density, 1f)
                            setPadding(0, dp(4), 0, dp(4))
                        }
                        wrap.addView(tv)
                    }
                }
                "photos", "photo" -> {
                    // 兼容旧格式：paths[] 数组（新）或直接 path（旧单图）
                    val pathsArr = b.optJSONArray("paths")
                    val raw: List<String> = when {
                        pathsArr != null -> (0 until pathsArr.length()).map { pathsArr.optString(it) }
                        !b.isNull("path") -> listOf(b.optString("path"))
                        else -> emptyList()
                    }
                    if (raw.isEmpty()) continue
                    // 三列宫格 v1.4.0：按详情页实际容器宽度（外层16+卡片14+wrap4=两侧68dp）计算
                    val grid = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
                    val gap = dp(6)
                    val cellW = (resources.displayMetrics.widthPixels - dp(68) - gap * 2) / 3
                    var row: android.widget.LinearLayout? = null
                    for (j in raw.indices) {
                        if (j % 3 == 0) {
                            row = android.widget.LinearLayout(ctx).apply {
                                orientation = android.widget.LinearLayout.HORIZONTAL
                                setPadding(0, 0, 0, gap)
                            }
                            grid.addView(row)
                        }
                        val path = raw[j]
                        if (!java.io.File(path).exists()) continue
                        val iv = android.widget.ImageView(ctx).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(cellW, cellW).apply {
                                marginEnd = if (j % 3 < 2) gap else 0
                            }
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
                            setOnClickListener {
                                val big = android.widget.ImageView(ctx).apply {
                                    setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
                                    adjustViewBounds = true
                                    maxHeight = dp(600); maxWidth = dp(320)
                                }
                                android.app.AlertDialog.Builder(ctx).setView(big)
                                    .setPositiveButton("关闭", null).show()
                            }
                        }
                        row?.addView(iv)
                    }
                    wrap.addView(grid)
                }
                "voice" -> {
                    // 兼容旧格式：voices[] 数组（新）或直接 path+seconds（旧单条）
                    val arr = b.optJSONArray("voices")
                    if (arr != null && arr.length() > 0) {
                        for (j in 0 until arr.length()) {
                            val v = arr.optJSONObject(j) ?: continue
                            val path = v.optString("path")
                            if (!java.io.File(path).exists()) continue
                            wrap.addView(voicePlayButton(path, v.optInt("seconds", 0)))
                        }
                    } else if (!b.isNull("path")) {
                        val path = b.optString("path")
                        if (java.io.File(path).exists()) {
                            wrap.addView(voicePlayButton(path, b.optInt("seconds", 0)))
                        }
                    }
                }
                "location" -> {
                    val name = b.optString("name")
                    val lat = b.optDouble("lat", 0.0)
                    val lng = b.optDouble("lng", 0.0)
                    val tv = android.widget.TextView(ctx).apply {
                        text = "📍 ${if (name.isNotBlank()) name else "%.5f, %.5f".format(lat, lng)}"
                        textSize = 12f
                        setTextColor(0xFF8A7B6B.toInt())
                        setPadding(0, dp(6), 0, dp(2))
                    }
                    wrap.addView(tv)
                }
            }
        }
        return wrap
    }

    /** 过去缺卡/破戒日且有抵消机会时，提供手动补签（v1.3.7：负打卡破戒日也可补签） */
    private fun offerManualBackfill(it: CheckinItem, date: String, neg: Boolean) {
        if (date >= CheckinEngine.effectiveToday(it)) return
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

    /** 自动消耗模式：把创建日至昨天的缺卡按可用机会依次补签（v1.4.2：由后台快照线程调用，不再占用主线程） */
    private fun autoBackfillMissing(q: CheckinItem, c: ItemConfig) {
        if (!c.offset.enabled || !c.offset.autoConsume) return
        // v1.3.8：负打卡不自动补历史破戒日——破戒当天由盾牌直接抵消（蓝卡），历史破戒只支持手动补签
        if (CheckinEngine.isNegative(c)) return
        val created = DateUtils.dateOf(q.createdAt)
        val today = CheckinEngine.effectiveToday(c)
        var d = DateUtils.addDays(today, -1)
        // 性能：一次取出记录后逐日判断；机会数也在内存里递减，避免每天查库
        val byDay = repo.allRecords(q.id).groupBy { r -> r.checkinDate }
        var credits = repo.availableCredits(q.id)
        var guard = 0
        while (d >= created && guard++ < 400 && credits > 0) {
            val recs = byDay[d] ?: emptyList()
            val st = CheckinEngine.dayInfo(q, d, recs, today, c).state
            if (st == DayState.FAIL) {
                if (CheckinEngine.offsetBackfill(q, repo, d)) credits-- else break
            }
            d = DateUtils.addDays(d, -1)
        }
    }
    /** v1.3.17：超级管理员——记录操作菜单 */
    /** v1.3.18：补充「修改打卡时间」「更换照片」，覆盖不同打卡方式的记录内容 */
    /** v1.4.2：补齐记录级全字段（归属日期 / 心情 / 自动标记 / 日记块流 / 抵消机会 / 本日补录） */
    private fun showRecordAdminMenu(r: CheckinRecord, date: String) {
        val citem = item ?: return
        val opts = mutableListOf<String>()
        opts += "补录一条同日记录"
        opts += "修改状态"
        opts += "修改打卡时间"
        opts += "修改归属日期"
        if (!r.textContent.isNullOrBlank()) opts += "修改文字"
        if (hasJournalBlocks(r)) opts += "编辑日记内容"
        if (cfg.moodMode || moodOf(r) != null) opts += "修改心情"
        opts += if (r.isAuto == 1) "取消自动标记" else "标记为自动打卡"
        if (recordPhotoList(r).isNotEmpty()) { opts += "添加图片"; opts += "删除图片" }
        if (r.voicePath?.isNotBlank() == true) opts += "删除语音"
        if (r.latitude != null && r.longitude != null) opts += "删除位置"
        opts += "增减 🛡️ 抵消机会"
        opts += "删除整条记录"
        AlertDialog.Builder(requireContext())
            .setTitle("管理员 · 记录操作（$date）")
            .setItems(opts.toTypedArray()) { _, w ->
                when (opts[w]) {
                    "补录一条同日记录" -> adminDayMenu(citem, date)
                    "修改状态" -> changeRecordStatus(r)
                    "修改打卡时间" -> editRecordTime(r, date)
                    "修改归属日期" -> editRecordDate(r)
                    "修改文字" -> editRecordText(r)
                    "编辑日记内容" -> openJournalEditor(r)
                    "修改心情" -> editRecordMood(r)
                    "标记为自动打卡" -> { repo.updateRecord(r.copy(isAuto = 1)); toast("已标记为自动"); refresh() }
                    "取消自动标记" -> { repo.updateRecord(r.copy(isAuto = 0)); toast("已取消自动标记"); refresh() }
                    "添加图片" -> addRecordPhotos(r)
                    "删除位置" -> {
                        repo.updateRecord(r.copy(latitude = null, longitude = null, extraJson = clearLocName(r.extraJson)))
                        toast("已删除位置信息")
                        refresh()
                    }
                    "删除图片" -> showDeletePhotoPicker(r)
                    "删除语音" -> {
                        try { File(r.voicePath!!).delete() } catch (_: Exception) {}
                        repo.updateRecord(r.copy(voicePath = null))
                        toast("已删除该条记录的语音")
                        refresh()
                    }
                    "增减 🛡️ 抵消机会" -> adminCreditsMenu(citem)
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

    /** v1.3.18：管理员——修改记录打卡时间（输入 HH:mm 或 HH:mm:ss，归属日期不变） */
    private fun editRecordTime(r: CheckinRecord, date: String) {
        val et = android.widget.EditText(requireContext()).apply {
            setText(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(r.checkinTime)))
            hint = "HH:mm（如 08:30）"
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
        }
        AlertDialog.Builder(requireContext())
            .setTitle("修改打卡时间（$date）")
            .setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val s = et.text.toString().trim()
                val parts = s.split(":").mapNotNull { it.toIntOrNull() }
                if (parts.size < 2 || parts[0] !in 0..23 || parts[1] !in 0..59) {
                    toast("时间格式不正确（需 HH:mm）"); return@setPositiveButton
                }
                try {
                    val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    val t = f.parse("$date ${"%02d".format(parts[0])}:${"%02d".format(parts[1])}")?.time ?: run { toast("时间解析失败"); return@setPositiveButton }
                    repo.updateRecord(r.copy(checkinTime = t))
                    toast("打卡时间已修改")
                    refresh()
                } catch (_: Exception) { toast("时间解析失败") }
            }.show()
    }

    /** v1.4.0：把记录的图片列表写回 extraJson.photoPaths + photoPath（第一张，兼容旧版本）；空列表清空全部图片 */
    private fun applyPhotoList(r: CheckinRecord, paths: List<String>) {
        val jo = try { org.json.JSONObject(r.extraJson ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
        if (paths.isEmpty()) {
            jo.remove("photoPaths")
            repo.updateRecord(r.copy(photoPath = null, extraJson = if (jo.length() > 0) jo.toString() else null))
        } else {
            val arr = org.json.JSONArray(); paths.forEach { arr.put(it) }
            jo.put("photoPaths", arr)
            repo.updateRecord(r.copy(photoPath = paths.first(), extraJson = jo.toString()))
        }
    }

    /** v1.4.0：管理员——相册多选追加图片（最多 9 张），压缩后写回 */
    private fun addRecordPhotos(r: CheckinRecord) {
        val existing = recordPhotoList(r)
        if (existing.size >= 9) { toast("最多 9 张"); return }
        addPhotoTarget = r
        addPhotosLauncher.launch("image/*")
    }

    /** v1.4.0：管理员——宫格点选删除某张图片 */
    private fun showDeletePhotoPicker(r: CheckinRecord) {
        var photos = recordPhotoList(r)
        if (photos.isEmpty()) { toast("该记录没有图片"); return }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun rebuild() {
            container.removeAllViews()
            if (photos.isEmpty()) { container.addView(TextView(requireContext()).apply {
                text = "（图片已全部删除）"; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40)
            }); return }
            container.addView(photoGridView(photos) { path ->
                AlertDialog.Builder(requireContext())
                    .setTitle("删除该张图片？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        try { File(path).delete() } catch (_: Exception) {}
                        photos = photos.filter { it != path }
                        applyPhotoList(r, photos)
                        if (photos.isEmpty()) refresh()
                        rebuild()
                    }.show()
            })
        }
        rebuild()
        AlertDialog.Builder(requireContext())
            .setTitle("删除图片（点图片删除）")
            .setView(container)
            .setPositiveButton("关闭") { _, _ -> refresh() }
            .show()
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

    /** v1.4.2：管理员补录入口（记录区底部，点一下就能继续补录） */
    private fun adminAddButton(it: CheckinItem, date: String): View {
        val d = resources.displayMetrics.density
        val citem = it
        return TextView(requireContext()).apply {
            text = "🛠 管理员：补录 / 修改 $date"
            textSize = 12f
            setTextColor(0xFF9AA1B2.toInt())
            setPadding(0, (8 * d).toInt(), 0, 0)
            setOnClickListener { adminDayMenu(citem, date) }
        }
    }

    /** v1.4.2：管理员——按日期补录/清理。已有多条记录的日期也能继续补，解决「多次打卡打不满」 */
    private fun adminDayMenu(it: CheckinItem, date: String) {
        val existing = repo.recordsOfDay(it.id, date)
        val opts = mutableListOf<String>()
        opts += "补录成功记录"
        opts += "补录补签记录"
        opts += "补录破戒记录"
        opts += "补录并标记为「自动」"
        opts += "增减 🛡️ 抵消机会"
        if (existing.isNotEmpty()) opts += "清空该日全部记录"
        AlertDialog.Builder(requireContext())
            .setTitle("管理员 · $date（现有 ${existing.size} 条）")
            .setItems(opts.toTypedArray()) { _, w ->
                when (opts[w]) {
                    "补录成功记录" -> adminInsertRecord(it, date, "SUCCESS", false)
                    "补录补签记录" -> adminInsertRecord(it, date, "OFFSET", false)
                    "补录破戒记录" -> adminInsertRecord(it, date, "FAIL", false)
                    "补录并标记为「自动」" -> adminInsertRecord(it, date, "SUCCESS", true)
                    "增减 🛡️ 抵消机会" -> adminCreditsMenu(it)
                    "清空该日全部记录" -> confirmClearDay(it, date, existing)
                }
            }.show()
    }

    /** v1.4.2：管理员补录一条记录（直接入库，不受每日次数限制） */
    private fun adminInsertRecord(it: CheckinItem, date: String, status: String, auto: Boolean) {
        val t = if (date == DateUtils.today()) System.currentTimeMillis() else try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .parse("$date 12:00:00")?.time ?: System.currentTimeMillis()
        } catch (_: Exception) { System.currentTimeMillis() }
        repo.insertRecord(com.zerolab.checkin.data.entity.CheckinRecord(
            itemId = it.id, checkinDate = date, checkinTime = t,
            status = status, isAuto = if (auto) 1 else 0))
        // 打卡组子项补录后，尝试让组当日自动完成（幂等）
        try { CheckinEngine.maybeAutoGroup(it, repo, date) } catch (_: Exception) {}
        val label = when (status) { "OFFSET" -> "补签"; "FAIL" -> "破戒"; else -> "成功" }
        toast("已补录一条${label}记录（${date}）")
        refresh()
    }

    /** v1.4.2：管理员——清空某日全部记录（不动物料文件） */
    private fun confirmClearDay(item: CheckinItem, date: String, recs: List<CheckinRecord>) {
        AlertDialog.Builder(requireContext())
            .setTitle("清空 $date 的记录")
            .setMessage("将删除该日 ${recs.size} 条记录（不影响其他日期），确定？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                recs.forEach { r -> repo.deleteRecord(r.id) }
                toast("已清空该日记录")
                refresh()
            }.show()
    }

    /** v1.4.2：管理员——手动增减 🛡️ 抵消机会 */
    private fun adminCreditsMenu(it: CheckinItem) {
        val avail = repo.availableCredits(it.id)
        AlertDialog.Builder(requireContext())
            .setTitle("🛡️ 抵消机会（当前可用 $avail）")
            .setItems(arrayOf("+1 个", "+3 个", "−1 个", "−3 个")) { _, w ->
                when (w) {
                    0 -> repo.grantCredits(it.id, 1, DateUtils.today())
                    1 -> repo.grantCredits(it.id, 3, DateUtils.today())
                    2 -> repo.revokeCredits(it.id, 1)
                    else -> repo.revokeCredits(it.id, 3)
                }
                toast("已更新：可用 ${repo.availableCredits(it.id)} 个")
                refresh()
            }.show()
    }

    /** v1.4.2：管理员——修改记录归属日期（保留原时刻） */
    private fun editRecordDate(r: CheckinRecord) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = r.checkinTime
        android.app.DatePickerDialog(requireContext(), { _, y, m0, d ->
            val date = "%04d-%02d-%02d".format(y, m0 + 1, d)
            val hh = cal.get(Calendar.HOUR_OF_DAY); val mm = cal.get(Calendar.MINUTE)
            val t = try {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .parse("$date %02d:%02d".format(hh, mm))?.time
            } catch (_: Exception) { null }
            repo.updateRecord(r.copy(checkinDate = date, checkinTime = t ?: r.checkinTime))
            toast("归属日期已改为 $date")
            refresh()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    /** v1.4.2：管理员——修改记录心情档位 */
    private fun editRecordMood(r: CheckinRecord) {
        val labels = arrayOf("😄 开心", "🙂 不错", "😐 一般", "😟 低落", "😖 很差", "清除心情")
        AlertDialog.Builder(requireContext())
            .setTitle("修改心情")
            .setItems(labels) { _, w ->
                val jo = try { org.json.JSONObject(r.extraJson ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
                if (w == 5) jo.remove("mood") else jo.put("mood", w + 1)
                repo.updateRecord(r.copy(extraJson = if (jo.length() == 0) null else jo.toString()))
                toast("心情已更新")
                refresh()
            }.show()
    }

    /** v1.4.2：管理员——编辑随心记块流内容（复用日记编辑页，覆盖原记录） */
    private fun openJournalEditor(r: CheckinRecord) {
        val it = item ?: return
        startActivity(android.content.Intent(requireContext(), com.zerolab.checkin.ui.journal.JournalEditActivity::class.java)
            .putExtra(com.zerolab.checkin.ui.journal.JournalEditActivity.EXTRA_ITEM_ID, it.id)
            .putExtra(com.zerolab.checkin.ui.journal.JournalEditActivity.EXTRA_RECORD_ID, r.id))
    }

    /** v1.4.2：该记录是否为块流日记（有 blocks 数组） */
    private fun hasJournalBlocks(r: CheckinRecord): Boolean = try {
        org.json.JSONObject(r.extraJson ?: "{}").has("blocks")
    } catch (_: Exception) { false }

    /** 修改记录文字内容 */
    private fun editRecordText(r: CheckinRecord) {        val et = android.widget.EditText(requireContext()).apply {
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