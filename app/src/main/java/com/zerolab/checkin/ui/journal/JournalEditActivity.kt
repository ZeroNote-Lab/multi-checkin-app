package com.zerolab.checkin.ui.journal

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.util.AudioRecorder
import com.zerolab.checkin.util.DateUtils
import com.zerolab.checkin.util.ImageUtil
import com.zerolab.checkin.util.NetGeo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * v1.4.0 随心记块流编辑页。
 * 积木式：文字/图片宫格/语音列表/位置块随机穿插；
 * 底部悬浮三按钮即点即插；插入新块总是追加到末尾。
 */
class JournalEditActivity : AppCompatActivity() {

    // ---- 色号（独立于 App 主题）----
    private val bgPaper = 0xFFFAF7F2.toInt()
    private val cardWhite = 0xFFFFFFFF.toInt()
    private val textMain = 0xFF3D342B.toInt()
    private val textSub = 0xFF8A7B6B.toInt()
    private val accent = 0xFFC97B4A.toInt()
    private val bgLight = 0xFFF0E8DC.toInt()
    private val divider = 0xFFE0D5C5.toInt()
    private val voiceTrack = 0xFFE8D5C0.toInt()

    // ---- 数据模型 ----
    sealed class Block {
        data class TextBlock(var text: String = "") : Block()
        data class PhotoBlock(val paths: MutableList<String> = mutableListOf()) : Block()
        data class VoiceBlock(val voices: MutableList<VoiceItem> = mutableListOf()) : Block()
        data class LocationBlock(var name: String = "", var lat: Double = 0.0, var lng: Double = 0.0) : Block()
    }
    data class VoiceItem(val path: String, val seconds: Int)

    private val blocks = mutableListOf<Block>()
    private var itemId: Long = -1L
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private var audioRecorder = AudioRecorder()
    private var recordingBlock: Block.VoiceBlock? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var playingBtn: TextView? = null
    private var playingTrack: android.widget.FrameLayout? = null   // v1.4.0：进度条轨道
    private var playingProgress: View? = null                   // v1.4.0：进度条填充
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    private var pendingPhotoTarget: Block.PhotoBlock? = null  // v1.4.0：点某块+号后追加到该块

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
    }

    // ---- 图片多选（系统相册）----
    private val pickPhotos = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val item = itemId
        thread {
            val outDir = File(filesDir, "checkin_photos/$item")
            val saved = mutableListOf<String>()
            for (uri in uris) {
                try {
                    val tmp = File(cacheDir, "photo_tmp_${UUID.randomUUID()}.jpg")
                    contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
                    val c = ImageUtil.compressWebp(tmp, outDir)
                    if (c != null) saved.add(c.absolutePath)
                } catch (_: Exception) {}
            }
            main.post {
                if (saved.isEmpty()) { toast("图片读取失败"); return@post }
                // v1.4.0：有目标块就追加，没目标才新建
                val target = pendingPhotoTarget
                if (target != null) {
                    target.paths.addAll(saved)
                    pendingPhotoTarget = null
                } else {
                    val photoBlock = Block.PhotoBlock(saved)
                    blocks.add(photoBlock)
                    appendAutoTextBlock()
                }
                renderAll()
                toast("已添加 ${saved.size} 张图片")
            }
        }
    }

    // ---- 相机拍照 ----
    private val takePhotoUri = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        if (!ok) return@registerForActivityResult
        val pending = pendingCameraUri ?: return@registerForActivityResult
        thread {
            val outDir = File(filesDir, "checkin_photos/$itemId")
            val c = try {
                ImageUtil.compressWebp(File(cacheDir, "photo_tmp_cam.jpg"), outDir)
            } catch (_: Exception) { null }
            main.post {
                if (c != null) {
                    blocks.add(Block.PhotoBlock(mutableListOf(c.absolutePath)))
                    appendAutoTextBlock()
                    renderAll()
                } else toast("拍照保存失败")
            }
        }
    }
    private var pendingCameraUri: Uri? = null

    // ---- 权限 ----
    private val permCamera = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else toast("需要相机权限")
    }
    private val permAudio = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("需要录音权限")
    }
    private val permLocation = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doLocate() else toast("需要定位权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgPaper
        setContentView(R.layout.activity_journal_edit)
        itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        container = findViewById(R.id.block_container)

        findViewById<TextView>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_save).setOnClickListener { save() }
        findViewById<TextView>(R.id.btn_add_photo).setOnClickListener { showPhotoPicker() }
        findViewById<TextView>(R.id.btn_add_voice).setOnClickListener { ensure(Manifest.permission.RECORD_AUDIO, permAudio) { startRecording() } }
        findViewById<TextView>(R.id.btn_add_location).setOnClickListener { ensure(Manifest.permission.ACCESS_FINE_LOCATION, permLocation) { doLocate() } }

        // 初始：一个空白文字块
        blocks.add(Block.TextBlock())
        renderAll()
    }

    private fun ensure(perm: String, launcher: androidx.activity.result.ActivityResultLauncher<String>, ok: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) ok()
        else launcher.launch(perm)
    }

    // ---- 图片来源选择 ----
    private fun showPhotoPicker() {
        val opts = arrayOf("拍照", "从相册选择")
        AlertDialog.Builder(this).setTitle("添加图片").setItems(opts) { _, i ->
            when (i) {
                0 -> ensure(Manifest.permission.CAMERA, permCamera) { openCamera() }
                1 -> pickPhotos.launch("image/*")
            }
        }.show()
    }

    private fun openCamera() {
        try {
            val tmp = File(cacheDir, "photo_tmp_cam.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", tmp)
            pendingCameraUri = uri
            takePhotoUri.launch(uri)
        } catch (_: Exception) { toast("无法启动相机") }
    }

    // ---- 语音录制 ----
    private fun startRecording() {
        // 追加到最后一个 VoiceBlock；没有就新建一个
        val lastVoice = blocks.lastOrNull { it is Block.VoiceBlock } as? Block.VoiceBlock
        val target = if (lastVoice != null && !lastVoice.voices.isEmpty()) lastVoice else {
            val vb = Block.VoiceBlock()
            blocks.add(vb)
            appendAutoTextBlock()
            vb
        }
        recordingBlock = target
        // v1.4.0：先弹开始确认，点开始才真正录
        val msgView = TextView(this).apply {
            text = "点击开始录音，最长60秒"
            textSize = 14f
            setTextColor(0xFF3D342B.toInt())
            setPadding(40, 30, 40, 10)
        }
        AlertDialog.Builder(this).setTitle("录音").setView(msgView).setCancelable(false)
            .setNegativeButton("取消") { _, _ -> recordingBlock = null }
            .setPositiveButton("开始") { _, _ ->
                try {
                    audioRecorder.start(File(filesDir, "checkin_voices/$itemId"), 60)
                } catch (e: Exception) { toast("录音启动失败: ${e.message}"); recordingBlock = null; return@setPositiveButton }
                // 录音中弹窗：显示已录秒数
                val recordMsg = TextView(this).apply {
                    text = "正在录音…\n已录 0 秒"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(40, 40, 40, 20)
                }
                val dlg = AlertDialog.Builder(this).setTitle("录音中").setView(recordMsg).setCancelable(false)
                    .setNegativeButton("取消") { _, _ ->
                        audioRecorder.cancel(); recordingBlock = null
                    }
                    .setPositiveButton("完成") { _, _ ->
                        val f = audioRecorder.stop()
                        recordingBlock = null
                        if (f != null && f.exists()) {
                            val seconds = ((f.length() / 16000.0) * 2.0).toInt().coerceAtLeast(1)
                            target.voices.add(VoiceItem(f.absolutePath, seconds))
                            renderAll()
                        } else toast("录音太短")
                    }.show()
                // 计时更新
                val handler = Handler(Looper.getMainLooper())
                var sec = 0
                val tick = object : Runnable {
                    override fun run() {
                        sec++
                        recordMsg.text = "正在录音…\n已录 $sec 秒"
                        if (sec < 60) handler.postDelayed(this, 1000)
                    }
                }
                handler.postDelayed(tick, 1000)
            }.show()
    }

    // ---- 定位 ----
    private fun doLocate() {
        val loading = AlertDialog.Builder(this).setMessage("正在获取位置…").setCancelable(false).show()
        thread {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            var best: android.location.Location? = null
            try {
                for (p in lm.getProviders(true)) {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || l.accuracy < best!!.accuracy) best = l
                }
            } catch (_: SecurityException) {}
            val l = best
            main.post {
                loading.dismiss()
                if (l == null) { toast("无法获取定位"); return@post }
                // 解析地名
                val locLoading = AlertDialog.Builder(this).setMessage("正在解析位置名称…").setCancelable(false).show()
                thread {
                    val name = try { if (NetGeo.enabled(this)) NetGeo.regeo(this, l.latitude, l.longitude) else null } catch (_: Exception) { null }
                    main.post {
                        locLoading.dismiss()
                        // 移除旧 LocationBlock（点第二次覆盖）
                        blocks.removeAll { it is Block.LocationBlock }
                        blocks.add(Block.LocationBlock(name ?: "当前位置", l.latitude, l.longitude))
                        renderAll()
                        toast("已添加位置")
                    }
                }
            }
        }
    }

    // ---- 文字块自动接力：非文字块后自动跟一个空白文字块 ----
    private fun appendAutoTextBlock() {
        blocks.add(Block.TextBlock())
    }

    // ---- 渲染所有 blocks ----
    private fun renderAll() {
        container.removeAllViews()
        blocks.forEachIndexed { idx, block ->
            val v = renderBlock(block, idx)
            container.addView(v)
        }
    }

    private fun renderBlock(block: Block, idx: Int): View {
        return when (block) {
            is Block.TextBlock -> renderTextBlock(block)
            is Block.PhotoBlock -> renderPhotoBlock(block, idx)
            is Block.VoiceBlock -> renderVoiceBlock(block, idx)
            is Block.LocationBlock -> renderLocationBlock(block)
        }
    }

    private fun renderTextBlock(b: Block.TextBlock): View {
        val et = EditText(this).apply {
            setText(b.text)
            setTextColor(textMain)
            setTextSize(16f)
            setPadding(8, 12, 8, 12)
            setBackgroundColor(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "写点什么…"
            setHintTextColor(textSub)
            setLineSpacing(6f, 1.4f)
            background = null
            // 文字变化同步到数据模型
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { b.text = s?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        return et
    }

    private fun renderPhotoBlock(b: Block.PhotoBlock, idx: Int): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        // 3列宫格：用 LinearLayout 包裹，每行3个
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val screenW = resources.displayMetrics.widthPixels - dp(32)
        val gap = dp(6)
        val cellW = (screenW - gap * 2) / 3  // v1.4.0：修复第三张截断
        var row: LinearLayout? = null
        b.paths.forEachIndexed { i, path ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(6))
                }
                grid.addView(row)
            }
            // 图片+删除按钮容器
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(cellW, cellW).apply {
                    marginEnd = if (i % 3 < 2) gap else 0
                }
            }
            val iv = android.widget.ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
                setOnClickListener { showImagePreview(path) }
            }
            cell.addView(iv)
            // 删除按钮（右上角×）
            val del = TextView(this).apply {
                text = "✕"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(0x80000000.toInt())
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END)
                setOnClickListener {
                    b.paths.removeAt(i)
                    if (b.paths.isEmpty()) blocks.remove(b)
                    renderAll()
                }
            }
            cell.addView(del)
            row?.addView(cell)
        }
        // 末尾虚线+号
        val addCell = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(cellW, cellW).apply {
                marginEnd = if (b.paths.size % 3 < 2) gap else 0
            }
            text = "+"
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(textSub)
            background = rounded(divider, dp(8))
            setOnClickListener { pendingPhotoTarget = b; pickPhotos.launch("image/*") }
        }
        if (row == null || (b.paths.size % 3 == 0 && b.paths.isNotEmpty())) {
            row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
            grid.addView(row)
        }
        row?.addView(addCell)
        wrap.addView(grid)
        return wrap
    }

    private fun showImagePreview(path: String) {
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        AlertDialog.Builder(this).setView(iv).setPositiveButton("关闭", null).show()
    }

    private fun renderVoiceBlock(b: Block.VoiceBlock, idx: Int): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        b.voices.forEach { v ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 10, 12, 10)
                setBackgroundColor(cardWhite)
                background = roundedShadow(bgLight, dp(12))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            }
            // 播放键
            val play = TextView(this).apply {
                text = "▶"
                setTextColor(Color.WHITE)
                setBackgroundColor(accent)
                gravity = Gravity.CENTER
                setTextSize(11f)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) }
            }
            // 时长
            val dur = TextView(this).apply {
                text = "%d:%02d".format(v.seconds / 60, v.seconds % 60)
                setTextColor(textSub)
                setTextSize(12f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            }
            // 进度条：轨道 + 填充
            val barTrack = android.widget.FrameLayout(this).apply {
                setBackgroundColor(voiceTrack)
                layoutParams = LinearLayout.LayoutParams(0, dp(3), 1f).apply { marginEnd = dp(8) }
            }
            val barProgress = View(this).apply {
                setBackgroundColor(accent)
                layoutParams = android.widget.FrameLayout.LayoutParams(0, dp(3))
            }
            barTrack.addView(barProgress)
            play.setOnClickListener { togglePlay(v.path, play, barTrack, barProgress) }
            // 删除
            val del = TextView(this).apply {
                text = "✕"
                setTextColor(textSub)
                setTextSize(14f)
                setOnClickListener {
                    b.voices.remove(v)
                    if (b.voices.isEmpty()) blocks.remove(b)
                    renderAll()
                }
            }
            row.addView(play); row.addView(dur); row.addView(barTrack); row.addView(del)
            wrap.addView(row)
        }
        // 语音块内部末尾+号：追加一条语音
        val addRow = TextView(this).apply {
            text = "+ 追加语音"
            setTextColor(accent)
            setTextSize(12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { ensure(Manifest.permission.RECORD_AUDIO, permAudio) { startRecording() } }
        }
        wrap.addView(addRow)
        return wrap
    }

    private fun renderLocationBlock(b: Block.LocationBlock): View {
        val tv = TextView(this).apply {
            text = "📍 ${b.name}"
            setTextColor(textSub)
            setTextSize(12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(bgLight, dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        return tv
    }

    // ---- 工具 ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    }
    private fun roundedShadow(color: Int, radius: Int): GradientDrawable = rounded(color, radius)

    private fun thread(f: () -> Unit) = Thread(f).start()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun stopProgress() {
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        progressRunnable = null
        progressHandler = null
    }
    private fun startProgress(mp: android.media.MediaPlayer, track: android.widget.FrameLayout, prog: View) {
        stopProgress()
        val h = Handler(Looper.getMainLooper())
        progressHandler = h
        val total = mp.duration.coerceAtLeast(1)
        val r = object : Runnable {
            override fun run() {
                try {
                    if (mp.isPlaying) {
                        val w = (track.width * mp.currentPosition / total).toInt()
                        prog.layoutParams = android.widget.FrameLayout.LayoutParams(w, dp(3))
                        h.postDelayed(this, 200)
                    }
                } catch (_: Exception) {}
            }
        }
        progressRunnable = r
        h.postDelayed(r, 200)
    }

    private fun togglePlay(path: String, btn: TextView, track: android.widget.FrameLayout, prog: View) {
        // 正在播放这条：停止
        if (mediaPlayer != null && playingBtn === btn && mediaPlayer!!.isPlaying) {
            try { mediaPlayer?.stop() } catch (_: Exception) {}
            mediaPlayer?.release(); mediaPlayer = null
            btn.text = "▶"; playingBtn = null
            prog.layoutParams = android.widget.FrameLayout.LayoutParams(0, dp(3))
            stopProgress()
            return
        }
        // 切到新的：先停旧的并复位旧UI
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        playingBtn?.let { it.text = "▶" }
        playingProgress?.layoutParams = android.widget.FrameLayout.LayoutParams(0, dp(3))
        stopProgress()
        try {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(path); mp.prepare()
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
                mediaPlayer = null; btn.text = "▶"; playingBtn = null
                prog.layoutParams = android.widget.FrameLayout.LayoutParams(0, dp(3))
                stopProgress()
            }
            mp.start()
            mediaPlayer = mp; playingBtn = btn; btn.text = "⏸"
            playingTrack = track; playingProgress = prog
            startProgress(mp, track, prog)
        } catch (e: Exception) { toast("播放失败: ${e.message}") }
    }

    override fun onDestroy() {
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---- 保存 ----
    private fun save() {
        val item = (application as CheckinApp).repository.getItem(itemId)
        if (item == null) { toast("打卡项不存在"); finish(); return }

        // 过滤掉空白文字块
        val validBlocks = blocks.filter {
            when (it) {
                is Block.TextBlock -> it.text.isNotBlank()
                is Block.PhotoBlock -> it.paths.isNotEmpty()
                is Block.VoiceBlock -> it.voices.isNotEmpty()
                is Block.LocationBlock -> true
            }
        }
        if (validBlocks.isEmpty()) { toast("写点什么再保存吧"); return }

        // 序列化 blocks 为 JSON
        val arr = JSONArray()
        var firstPhoto: String? = null
        var firstText: String? = null
        var firstVoice: String? = null
        var locLat: Double? = null; var locLng: Double? = null; var locName: String? = null
        validBlocks.forEach { b ->
            when (b) {
                is Block.TextBlock -> {
                    arr.put(JSONObject().put("type", "text").put("text", b.text))
                    if (firstText == null) firstText = b.text
                }
                is Block.PhotoBlock -> {
                    val pathsArr = JSONArray()
                    b.paths.forEach { pathsArr.put(it) }
                    arr.put(JSONObject().put("type", "photos").put("paths", pathsArr))
                    if (firstPhoto == null && b.paths.isNotEmpty()) firstPhoto = b.paths[0]
                }
                is Block.VoiceBlock -> {
                    val voicesArr = JSONArray()
                    b.voices.forEach { voicesArr.put(JSONObject().put("path", it.path).put("seconds", it.seconds)) }
                    arr.put(JSONObject().put("type", "voice").put("voices", voicesArr))
                    if (firstVoice == null && b.voices.isNotEmpty()) firstVoice = b.voices[0].path
                }
                is Block.LocationBlock -> {
                    arr.put(JSONObject().put("type", "location").put("name", b.name).put("lat", b.lat).put("lng", b.lng))
                    locLat = b.lat; locLng = b.lng; locName = b.name
                }
            }
        }
        val jo = JSONObject()
        jo.put("method", "JOURNAL_BLOCKS")
        jo.put("blocks", arr)
        locName?.let { jo.put("locName", it) }

        thread {
            val r = CheckinEngine.perform(
                item, (application as CheckinApp).repository,
                photoPath = firstPhoto,
                text = firstText,
                voicePath = firstVoice,
                lat = locLat, lng = locLng,
                extra = jo.toString()
            )
            main.post {
                when (r) {
                    is com.zerolab.checkin.engine.CheckinResult.Ok -> { toast("已记录 ✓"); finish() }
                    is com.zerolab.checkin.engine.CheckinResult.Blocked -> toast(r.reason)
                }
            }
        }
    }
}
