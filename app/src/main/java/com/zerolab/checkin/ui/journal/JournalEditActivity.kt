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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.util.AudioRecorder
import com.zerolab.checkin.util.ChinaCoord
import com.zerolab.checkin.util.DateUtils
import com.zerolab.checkin.util.ImageUtil
import com.zerolab.checkin.util.NetGeo
import com.zerolab.checkin.util.formatLatLng
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
    // v1.4.2：管理员编辑已存在的随心记记录（而不是新增一条）
    private var recordId: Long = -1L
    private var editingRecord: CheckinRecord? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private var audioRecorder = AudioRecorder()
    private var recordingBlock: Block.VoiceBlock? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var playingBtn: TextView? = null
    private var playingSeekBar: android.widget.SeekBar? = null    // v1.4.0：可拖动进度条
    private var progressHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    private var pendingPhotoTarget: Block.PhotoBlock? = null  // v1.4.0：点某块+号后追加到该块

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        /** v1.4.2：非空时编辑该条记录（管理员入口） */
        const val EXTRA_RECORD_ID = "extra_record_id"
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
        recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        container = findViewById(R.id.block_container)

        // v1.4.2：管理员编辑已有记录——载入原块流内容，保存时覆盖该条记录
        if (recordId > 0) {
            editingRecord = (application as CheckinApp).repository
                .allRecords(itemId).firstOrNull { it.id == recordId }
            editingRecord?.let { r ->
                findViewById<TextView>(R.id.tv_journal_title)?.text = "编辑记录"
                loadBlocksFrom(r)
            }
        }

        findViewById<TextView>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_save).setOnClickListener { save() }
        findViewById<TextView>(R.id.btn_add_photo).setOnClickListener { showPhotoPicker() }
        findViewById<TextView>(R.id.btn_add_voice).setOnClickListener { ensure(Manifest.permission.RECORD_AUDIO, permAudio) { startRecording() } }
        findViewById<TextView>(R.id.btn_add_location).setOnClickListener { showLocationOptions() }

        // 初始：编辑模式用原内容，新建模式给一个空白文字块
        if (blocks.isEmpty()) blocks.add(Block.TextBlock())
        renderAll()
    }

    /** v1.4.2：把记录里的 blocks[] 还原成编辑块（兼容旧单字段记录） */
    private fun loadBlocksFrom(r: CheckinRecord) {
        blocks.clear()
        val arr = try { JSONObject(r.extraJson ?: "{}").optJSONArray("blocks") } catch (_: Exception) { null }
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                when (b.optString("type")) {
                    "text" -> b.optString("text").takeIf { it.isNotBlank() }?.let { blocks.add(Block.TextBlock(it)) }
                    "photos", "photo" -> {
                        val paths = mutableListOf<String>()
                        b.optJSONArray("paths")?.let { pa ->
                            for (j in 0 until pa.length()) pa.optString(j).takeIf { it.isNotBlank() }?.let { paths.add(it) }
                        }
                        if (paths.isEmpty()) b.optString("path").takeIf { it.isNotBlank() }?.let { paths.add(it) }
                        if (paths.isNotEmpty()) blocks.add(Block.PhotoBlock(paths))
                    }
                    "voice" -> {
                        val voices = mutableListOf<VoiceItem>()
                        b.optJSONArray("voices")?.let { va ->
                            for (j in 0 until va.length()) {
                                val v = va.optJSONObject(j) ?: continue
                                val p = v.optString("path")
                                if (p.isNotBlank()) voices.add(VoiceItem(p, v.optInt("seconds", 0)))
                            }
                        }
                        if (voices.isEmpty()) b.optString("path").takeIf { it.isNotBlank() }?.let {
                            voices.add(VoiceItem(it, b.optInt("seconds", 0)))
                        }
                        if (voices.isNotEmpty()) blocks.add(Block.VoiceBlock(voices))
                    }
                    "location" -> {
                        val name = b.optString("name")
                        blocks.add(Block.LocationBlock(name, b.optDouble("lat", 0.0), b.optDouble("lng", 0.0)))
                    }
                }
            }
        }
        // 旧数据（无 blocks）兜底：用顶层字段还原
        if (blocks.isEmpty()) {
            r.textContent?.takeIf { it.isNotBlank() }?.let { blocks.add(Block.TextBlock(it)) }
            r.photoPath?.takeIf { it.isNotBlank() }?.let { blocks.add(Block.PhotoBlock(mutableListOf(it))) }
            r.voicePath?.takeIf { it.isNotBlank() }?.let { blocks.add(Block.VoiceBlock(mutableListOf(VoiceItem(it, 0)))) }
        }
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
            text = "点击开始录音"
            textSize = 14f
            setTextColor(0xFF3D342B.toInt())
            setPadding(40, 30, 40, 10)
        }
        AlertDialog.Builder(this).setTitle("录音").setView(msgView).setCancelable(false)
            .setNegativeButton("取消") { _, _ -> recordingBlock = null }
            .setPositiveButton("开始") { _, _ ->
                try {
                    audioRecorder.start(File(filesDir, "checkin_voices/$itemId"), 0)
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
                            // v1.4.0：用 MediaMetadataRetriever 读 AAC 真实时长（毫秒→秒），废弃文件大小估算
                            val seconds = try {
                                val mmr = android.media.MediaMetadataRetriever()
                                mmr.setDataSource(f.absolutePath)
                                val durMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                mmr.release()
                                (durMs / 1000).toInt().coerceAtLeast(1)
                            } catch (_: Exception) { 1 }
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
                        handler.postDelayed(this, 1000)
                    }
                }
                handler.postDelayed(tick, 1000)
            }.show()
    }

    // ---- 定位（v1.4.2：实时定位，不再读历史缓存） ----
    /** 定位请求序号：取消后旧结果丢弃，避免界面卡在「获取中」 */
    private var locationSeq = 0

    /** v1.4.3：位置候选（历史位置 / 搜索结果），坐标为 WGS-84 */
    private data class PlaceChoice(val name: String, val detail: String, val lat: Double, val lng: Double)

    /** 位置入口：实时定位 / 复用历史位置 / 搜索地点（补记之前去过的地方） */
    private fun showLocationOptions() {
        val canSearch = NetGeo.enabled(this)
        val options = arrayOf(
            "使用当前位置（实时定位）",
            "从历史位置选择",
            if (canSearch) "搜索地点（补记之前去过的地方）" else "搜索地点（需先开启联网增强）"
        )
        AlertDialog.Builder(this)
            .setTitle("添加位置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> ensure(Manifest.permission.ACCESS_FINE_LOCATION, permLocation) { doLocate() }
                    1 -> loadHistoryPlaces()
                    else -> if (canSearch) showPlaceSearch()
                    else toast("请先在「增强服务 → 联网增强」开启并填写高德 Key")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 收集当前随心记保存过的位置，按最近使用去重 */
    private fun loadHistoryPlaces() {
        val loading = AlertDialog.Builder(this)
            .setMessage("正在读取历史位置…")
            .setCancelable(true)
            .create()
        loading.show()
        thread {
            val places = LinkedHashMap<String, PlaceChoice>()
            try {
                val repo = (application as CheckinApp).repository
                val journalItems = repo.getItems().filter {
                    try { ItemConfig.parse(it.configJson).journalMode } catch (_: Exception) { false }
                }
                val records = if (journalItems.isEmpty()) repo.allRecords(itemId)
                else journalItems.flatMap { repo.allRecords(it.id) }
                records.sortedByDescending { it.checkinTime }.forEach { r ->
                    val o = try { JSONObject(r.extraJson ?: "{}") } catch (_: Exception) { JSONObject() }
                    val blocks = o.optJSONArray("blocks")
                    var found = false
                    if (blocks != null) {
                        for (i in 0 until blocks.length()) {
                            val b = blocks.optJSONObject(i) ?: continue
                            if (b.optString("type") != "location") continue
                            val name = b.optString("name").ifBlank { "位置" }
                            val lat = b.optDouble("lat", 0.0)
                            val lng = b.optDouble("lng", 0.0)
                            if (lat == 0.0 && lng == 0.0) continue
                            found = true
                            val key = "%.5f|%.5f".format(lat, lng)
                            places.putIfAbsent(key, PlaceChoice(name, formatLatLng(lat, lng), lat, lng))
                        }
                    }
                    // 兼容只有经纬度、没有块流的老记录
                    if (!found && r.latitude != null && r.longitude != null) {
                        val lat = r.latitude!!; val lng = r.longitude!!
                        val key = "%.5f|%.5f".format(lat, lng)
                        places.putIfAbsent(key, PlaceChoice("历史位置", formatLatLng(lat, lng), lat, lng))
                    }
                }
            } catch (_: Exception) {}
            main.post {
                try { loading.dismiss() } catch (_: Exception) {}
                val list = places.values.take(40)
                if (list.isEmpty()) toast("还没有保存过位置，可先用实时定位或搜索地点")
                else showPlaceChoices("历史位置", list)
            }
        }
    }

    /** 搜索地点：高德 place/text，返回带完整名称/地址的 POI，避免只输入 xx 显示不对 */
    private fun showPlaceSearch() {
        val input = EditText(this).apply {
            hint = "输入地点名称，例如：西湖、XX 商场"
            setSingleLine()
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("搜索地点")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("搜索", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val kw = input.text.toString().trim()
            if (kw.isBlank()) { toast("请输入地点名称"); return@setOnClickListener }
            dlg.dismiss()
            val loading = AlertDialog.Builder(this)
                .setMessage("正在搜索「$kw」…")
                .setCancelable(true)
                .create()
            loading.show()
            thread {
                val pois = try { NetGeo.searchPlaces(this, kw) } catch (_: Exception) { emptyList() }
                main.post {
                    try { loading.dismiss() } catch (_: Exception) {}
                    if (pois.isEmpty()) {
                        toast("没有搜到地点，请检查联网增强 / Key / 网络")
                        return@post
                    }
                    val list = pois.map {
                        val w = ChinaCoord.gcj02ToWgs84(it.lat, it.lng)
                        val sub = listOfNotNull(
                            it.address.takeIf { a -> a.isNotBlank() },
                            if (it.distanceMeters > 0) "距离 ${it.distanceMeters} 米" else null
                        ).joinToString(" · ")
                        PlaceChoice(it.name, sub.ifBlank { "搜索结果" }, w[0], w[1])
                    }
                    showPlaceChoices("搜索结果：$kw", list)
                }
            }
        }
    }

    /** 历史/搜索结果列表，选中后替换位置块 */
    private fun showPlaceChoices(title: String, places: List<PlaceChoice>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        var dlgRef: AlertDialog? = null
        places.forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = true
                setBackgroundColor(0x00000000)
            }
            row.addView(TextView(this).apply {
                text = p.name
                textSize = 15f
                setTextColor(textMain)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = p.detail
                textSize = 12f
                setTextColor(textSub)
                setPadding(0, dp(3), 0, 0)
            })
            row.setOnClickListener {
                dlgRef?.dismiss()
                replaceLocationBlock(p.name, p.lat, p.lng)
            }
            box.addView(row)
        }
        val scroll = ScrollView(this).apply {
            addView(box)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(dp(420), dp(64) * places.size.coerceAtLeast(1))
            )
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("取消", null)
            .create()
        dlgRef = dlg
        dlg.show()
    }

    private fun doLocate() {
        val seq = ++locationSeq
        val loading = AlertDialog.Builder(this)
            .setMessage("正在获取实时定位…\n不使用历史缓存，最多等待 20 秒；可点「取消」返回")
            .setCancelable(true)
            .setNegativeButton("取消") { d, _ ->
                locationSeq++
                try { d.dismiss() } catch (_: Exception) {}
            }
            .create()
        loading.setOnCancelListener { locationSeq++ }
        loading.show()
        thread {
            val fix = try { com.zerolab.checkin.util.FreshLocation.request(this, 20_000L) } catch (_: Exception) { null }
            main.post {
                if (seq != locationSeq) {
                    try { loading.dismiss() } catch (_: Exception) {}
                    return@post
                }
                try { loading.dismiss() } catch (_: Exception) {}
                if (fix == null) {
                    AlertDialog.Builder(this)
                        .setTitle("无法获取实时定位")
                        .setMessage("可以改用历史位置，或在联网增强开启后搜索之前去过的地方。")
                        .setPositiveButton("历史位置") { _, _ -> loadHistoryPlaces() }
                        .setNeutralButton("搜索地点") { _, _ ->
                            if (NetGeo.enabled(this)) showPlaceSearch()
                            else toast("请先在「增强服务 → 联网增强」开启并填写高德 Key")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@post
                }
                // 解析地名 + 周边地点（定位有偏移时可手动改选）
                val locSeq = locationSeq
                val locLoading = AlertDialog.Builder(this)
                    .setMessage("正在获取位置名称…\n可点「取消」返回")
                    .setCancelable(true)
                    .setNegativeButton("取消") { d, _ ->
                        locationSeq++
                        try { d.dismiss() } catch (_: Exception) {}
                    }
                    .create()
                locLoading.setOnCancelListener { locationSeq++ }
                locLoading.show()
                thread {
                    val detail = try {
                        if (NetGeo.enabled(this)) NetGeo.regeoDetail(this, fix.lat, fix.lng) else null
                    } catch (_: Exception) { null }
                    main.post {
                        if (locSeq != locationSeq) {
                            try { locLoading.dismiss() } catch (_: Exception) {}
                            return@post
                        }
                        try { locLoading.dismiss() } catch (_: Exception) {}
                        if (detail == null) {
                            replaceLocationBlock("当前位置", fix.lat, fix.lng)
                            toast("未获取到位置名称，已记录当前坐标")
                        } else {
                            val info = "当前地址：${detail.address.ifBlank { "未知" }}\n如定位有偏移，可选择附近地点："
                            com.zerolab.checkin.ui.flow.LocationPickerDialog.show(
                                this, "选择位置", info, detail, fix.lat, fix.lng, onRetry = null
                            ) { name, wLat, wLng ->
                                replaceLocationBlock(name ?: "当前位置", wLat, wLng)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 文字块自动接力：非文字块后自动跟一个空白文字块 ----
    private fun appendAutoTextBlock() {
        blocks.add(Block.TextBlock())
    }

    /** v1.4.2：位置块唯一，重选时覆盖旧块 */
    private fun replaceLocationBlock(name: String, la: Double, ln: Double) {
        blocks.removeAll { it is Block.LocationBlock }
        blocks.add(Block.LocationBlock(name, la, ln))
        renderAll()
        toast("已添加位置")
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

    /** v1.4.3：全屏黑底预览，图片按屏幕缩放，不再出现只有下/左/右的白边 */
    private fun showImagePreview(path: String) {
        val bmp = try { android.graphics.BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
        if (bmp == null) { toast("图片加载失败"); return }
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { }
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0x66000000)
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(24); marginEnd = dp(16)
            }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(iv)
        root.addView(close)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        iv.setOnClickListener { dialog.dismiss() }
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
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
            // 进度条：v1.4.0 改为可拖动 SeekBar
            val seekBar = android.widget.SeekBar(this).apply {
                max = v.seconds.coerceAtLeast(1) * 1000
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(voiceTrack)
                thumbTintList = android.content.res.ColorStateList.valueOf(accent)
                splitTrack = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            }
            play.setOnClickListener { togglePlay(v.path, play, seekBar) }
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
            row.addView(play); row.addView(dur); row.addView(seekBar); row.addView(del)
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val tv = TextView(this).apply {
            val coords = formatLatLng(b.lat, b.lng)
            val label = if (b.name.isBlank() || b.name == "当前位置") "当前位置 $coords" else b.name
            text = "📍 $label"
            setTextColor(textSub)
            setTextSize(12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(bgLight, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // v1.4.2 修复：位置块补删除入口（之前只能退出重进）
        val del = TextView(this).apply {
            text = "✕"
            setTextColor(textSub)
            setTextSize(14f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(2), dp(6))
            setOnClickListener {
                blocks.remove(b)
                renderAll()
            }
        }
        row.addView(tv)
        row.addView(del)
        return row
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
    private fun startProgress(mp: android.media.MediaPlayer, sb: android.widget.SeekBar) {
        stopProgress()
        val h = Handler(Looper.getMainLooper())
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

    private fun togglePlay(path: String, btn: TextView, sb: android.widget.SeekBar) {
        // 拖动监听：松手即 seekTo
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                try { mediaPlayer?.seekTo(sb.progress) } catch (_: Exception) {}
            }
        })
        // 正在播放这条：停止
        if (mediaPlayer != null && playingBtn === btn && mediaPlayer!!.isPlaying) {
            try { mediaPlayer?.stop() } catch (_: Exception) {}
            mediaPlayer?.release(); mediaPlayer = null
            btn.text = "▶"; playingBtn = null
            sb.progress = 0
            stopProgress()
            return
        }
        // 切到新的：先停旧的并复位旧UI
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
        playingBtn?.let { it.text = "▶" }
        playingSeekBar?.progress = 0
        stopProgress()
        try {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(path); mp.prepare()
            sb.max = mp.duration.coerceAtLeast(1)
            sb.progress = 0
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
                mediaPlayer = null; btn.text = "▶"; playingBtn = null
                sb.progress = 0
                stopProgress()
            }
            mp.start()
            mediaPlayer = mp; playingBtn = btn; btn.text = "⏸"
            playingSeekBar = sb
            startProgress(mp, sb)
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

        // v1.4.2：管理员编辑已有记录——直接覆盖该条记录的块流内容，不新增记录
        val editing = editingRecord
        if (editing != null) {
            val repo = (application as CheckinApp).repository
            thread {
                val base = try { JSONObject(editing.extraJson ?: "{}") } catch (_: Exception) { JSONObject() }
                base.put("method", "JOURNAL_BLOCKS")
                base.put("blocks", arr)
                if (locName != null) base.put("locName", locName!!) else base.remove("locName")
                repo.updateRecord(editing.copy(
                    photoPath = firstPhoto,
                    textContent = firstText,
                    voicePath = firstVoice,
                    latitude = locLat,
                    longitude = locLng,
                    extraJson = base.toString()
                ))
                main.post { toast("已保存修改"); finish() }
            }
            return
        }

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
