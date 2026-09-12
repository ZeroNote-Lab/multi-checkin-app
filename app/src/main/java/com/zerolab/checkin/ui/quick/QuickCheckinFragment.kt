package com.zerolab.checkin.ui.quick

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AlertDialog
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.engine.DayInfo
import com.zerolab.checkin.engine.DayState
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.theme.ThemeManager
import com.zerolab.checkin.ui.create.CreateItemActivity
import com.zerolab.checkin.ui.detail.ItemDetailActivity
import com.zerolab.checkin.ui.flow.CheckinFlow
import com.zerolab.checkin.util.DateUtils
import com.zerolab.checkin.util.formatLatLng
import java.io.File
import java.util.Calendar

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
    }

    /** MainActivity 读到 NFC 标签后转发给打卡流程 */
    fun notifyNfc(tagId: String) {
        if (::flow.isInitialized) flow.nfcDetected(tagId)
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
                val i = Intent(requireContext(), ItemDetailActivity::class.java)
                i.putExtra(ItemDetailActivity.EXTRA_ID, it.id); startActivity(i)
            }
        }
        btnCheckin.setOnClickListener { flow.start(item!!, cfg) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        autoBackfillMissing()
        val theme = ThemeManager.of(q.theme)
        calendar.themeColor = theme.primary
        root.findViewById<TextView>(R.id.tv_emoji).text = theme.emoji
        root.findViewById<TextView>(R.id.tv_name).text = q.name
        btnCheckin.background?.setTint(theme.primary)
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
        val map = HashMap<String, DayInfo>()
        records.groupBy { r -> r.checkinDate }.forEach { (d, list) ->
            map[d] = CheckinEngine.dayInfo(it, d, list.sortedBy { x -> x.checkinTime })
        }
        calendar.setData(showYear, showMonth, map, CheckinEngine.isNegative(cfg)) { date -> showDayDetail(date) }
        // 图例随模式变化
        val legend = if (CheckinEngine.isNegative(cfg))
            "✅坚持成功 ❌破戒记录 ○今天 ·未到 ↩补签 ⚡自动"
        else "✅成功 ○今日未打卡 ·未到 ↩补签 ⚡自动"
        root.findViewById<TextView>(R.id.tv_legend).text = legend
    }

    private fun renderToday() {
        val it = item ?: return
        val today = DateUtils.today()
        val todayRecs = repo.recordsOfDay(it.id, today)
        val cnt = todayRecs.size
        val neg = CheckinEngine.isNegative(cfg)
        val interactive = cfg.methods.filter { m -> m != Method.AUTO.key }
        val paused = it.isActive == 0
        val isAuto = Method.AUTO.key in cfg.methods
        val statusView = root.findViewById<TextView>(R.id.tv_today_status)
        val streakView = root.findViewById<TextView>(R.id.tv_streak)
        val streak = CheckinEngine.streak(it, repo)
        val credits = repo.availableCredits(it.id)
        val sb = StringBuilder("🔥 连续 $streak 天")
        if (credits > 0) sb.append("    🛡️×$credits")
        if (cfg.dailyLimit > 1 && !neg) sb.append("    目标：每日${cfg.dailyLimit}次")
        streakView.text = sb.toString()

        btnCheckin.background?.setTint(ThemeManager.of(it.theme).primary)
        btnCheckin.isEnabled = true
        when {
            paused -> { statusView.text = "状态：已暂停"; btnCheckin.text = "已暂停"; btnCheckin.isEnabled = false; grayBtn() }
            isAuto -> { statusView.text = "状态：自动打卡（前台自动完成）"; btnCheckin.text = "⚡ 自动打卡，无需操作"; btnCheckin.isEnabled = false; grayBtn() }
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
                statusView.text = if (cnt == 0) "状态：坚持中（无操作=成功）" else "状态：今日已记录 $cnt 次破戒"
                btnCheckin.text = if (cnt == 0) "记录一次（破戒）" else "再记录一次（$cnt）"
            }
            !neg && interactive.size > 1 -> {
                val done = interactive.count { m -> todayRecs.any { r -> r.status == "SUCCESS" && r.extraJson?.contains(m) == true } }
                if (done >= interactive.size) { statusView.text = "状态：今日已完成 ✓"; btnCheckin.text = "今日已完成 ✓"; btnCheckin.isEnabled = false; grayBtn() }
                else { statusView.text = "状态：$done/${interactive.size}"; btnCheckin.text = "继续打卡 ($done/${interactive.size})" }
            }
            cfg.dailyLimit <= 1 -> {
                if (cnt == 0) { statusView.text = "状态：未打卡"; btnCheckin.text = "打卡" } else { statusView.text = "状态：已打卡 ✓"; btnCheckin.text = "已完成 ✓"; btnCheckin.isEnabled = false; grayBtn() }
            }
            else -> {
                if (cnt >= cfg.dailyLimit) { statusView.text = "状态：已完成 $cnt/${cfg.dailyLimit} ✓"; btnCheckin.text = "今日已完成 ✓"; btnCheckin.isEnabled = false; grayBtn() }
                else { statusView.text = "状态：$cnt/${cfg.dailyLimit}"; btnCheckin.text = "继续打卡 ($cnt/${cfg.dailyLimit})" }
            }
        }
    }

    private fun grayBtn() { btnCheckin.background?.setTint(0xFFB6BCC9.toInt()) }

    /** 点击日期：备注栏显示次数/时间/文字/缩略图/语音 */
    private fun showDayDetail(date: String) {
        val it = item ?: return
        val recs = repo.recordsOfDay(it.id, date)
        val title = root.findViewById<TextView>(R.id.tv_detail_title)
        val body = root.findViewById<TextView>(R.id.tv_detail_body)
        title.text = "$date  共 ${recs.size} 条记录"
        // 先清空媒体区，防止空记录时残留上一日期的缩略图/语音按钮（会把打卡按钮挤出屏幕）
        root.findViewById<LinearLayout>(R.id.detail_media_box).removeAllViews()
        if (recs.isEmpty()) {
            val neg = CheckinEngine.isNegative(cfg)
            body.text = when {
                date == DateUtils.today() -> if (neg) "今天暂无操作（坚持中）" else "今天尚未打卡"
                neg -> "已打卡（当天无操作）"
                else -> "未打卡（缺卡）"
            }
            offerManualBackfill(it, date, neg)
            return
        }
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        val lines = recs.map { r ->
            val prefix = when {
                r.status == "OFFSET" -> "↩ 补签"
                r.isAuto == 1 -> "⚡ 自动"
                r.status == "FAIL" -> "❌ 破戒"
                else -> "✅"
            }
            var line = "$prefix ${fmt.format(java.util.Date(r.checkinTime))}"
            if (!r.textContent.isNullOrBlank()) line += "\n   文字：${r.textContent}"
            if (!r.photoPath.isNullOrBlank()) line += "\n   📷 照片：${if (File(r.photoPath).exists()) "已保存（点击缩略图查看大图）" else "文件已丢失"}"
            if (!r.voicePath.isNullOrBlank()) line += "\n   🎤 语音：${if (File(r.voicePath).exists()) "已录制（点击播放）" else "文件已丢失"}"
            if (r.latitude != null && r.longitude != null) line += "\n   📍 位置：${formatLatLng(r.latitude!!, r.longitude!!)}"
            line
        }
        body.text = lines.joinToString("\n")
        // 媒体预览：缩略图 + 语音播放按钮
        val mediaBox = root.findViewById<LinearLayout>(R.id.detail_media_box)
        mediaBox.removeAllViews()
        recs.forEach { r ->
            if (r.photoPath?.isNotBlank() == true && File(r.photoPath).exists()) mediaBox.addView(thumbView(File(r.photoPath)))
            if (r.voicePath?.isNotBlank() == true && File(r.voicePath).exists()) mediaBox.addView(voicePlayButton(r.voicePath!!))
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

    /** 过去缺卡日且有抵消机会时，提供手动补签 */
    private fun offerManualBackfill(it: CheckinItem, date: String, neg: Boolean) {
        if (neg) return
        if (date >= DateUtils.today()) return
        if (!cfg.offset.enabled) return
        val avail = repo.availableCredits(it.id)
        if (avail <= 0) return
        AlertDialog.Builder(requireContext())
            .setTitle("补签 $date")
            .setMessage("该日缺卡。消耗 1 次抵消机会（当前剩余 $avail 次）补签？")
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
}
