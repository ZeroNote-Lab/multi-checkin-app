package com.zerolab.checkin.ui.create

import android.Manifest
import android.app.TimePickerDialog
import android.content.ContentValues
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

    private data class MethodRow(val switch: SwitchCompat, val panel: LinearLayout)
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

    // 真实 NFC 标签读取（enableReaderMode）
    private var nfcAdapter: NfcAdapter? = null
    private var nfcReader: NfcAdapter.ReaderCallback? = null

    private var dailyLimit = 1

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
        buildMethodRows()
        bindRuleControls()
        bindOffset()
        bindPolicy()

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
            bg.setColor(if (selected) t.primary else 0xFFEEF1F6.toInt())
            bg.setStroke(if (selected) 0 else 2, 0xFFD6DBE6.toInt())
            tv.background = bg
            tv.setTextColor(if (selected) ThemeManager.onColor(t.primary) else 0xFF4A5160.toInt())
            tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ---------- 方式开关行 ----------
    private fun buildMethodRows() {
        val container = findViewById<LinearLayout>(R.id.method_container)
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
                panel.visibility = if (on) View.VISIBLE else View.GONE
                if (on) cfg.methods.add(m.key) else cfg.methods.remove(m.key)
                refreshConflicts()
            }
            container.addView(card)
            rows[m.key] = MethodRow(sw, panel)
        }
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 16, 0, 6)
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
                cbPhotoCamera = CheckBox(this).apply { text = "允许拍照"; isChecked = true; setTextColor(0xFF1F2430.toInt()) }
                cbPhotoAlbum = CheckBox(this).apply { text = "允许从相册选择"; isChecked = true; setTextColor(0xFF1F2430.toInt()) }
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
                panel.addView(addBtn)
                tvLocPoints = TextView(this).apply { textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(0, 10, 0, 0) }
                panel.addView(tvLocPoints)
            }
            Method.STEPS -> {
                panel.addView(sectionLabel("每日目标步数"))
                etSteps = input("5000", true); panel.addView(etSteps)
            }
            Method.TIMER -> {
                panel.addView(sectionLabel("倒计时时长（分钟）"))
                etTimer = input("25", true); panel.addView(etTimer)
            }
            Method.QRCODE -> {
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
                panel.addView(btn)
                tvNfc = sectionLabel("尚未绑定标签"); panel.addView(tvNfc)
            }
            Method.VOICE -> {
                panel.addView(sectionLabel("最大录制时长（秒）"))
                etVoice = input("10", true); panel.addView(etVoice)
            }
            Method.AUTO -> panel.addView(sectionLabel("App 回到前台/冷启动时自动完成，无需手动操作。"))
        }
    }

    /** 互斥置灰 */
    private fun refreshConflicts() {
        val blocked = Method.conflictsWith(cfg.methods)
        rows.forEach { (key, row) ->
            val isBlocked = key in blocked
            row.switch.isEnabled = !isBlocked
            row.switch.alpha = if (isBlocked) 0.4f else 1f
            if (isBlocked && row.switch.isChecked) {
                row.switch.isChecked = false // 双保险
            }
        }
    }

    // ---------- 频率与规则 ----------
    private fun bindRuleControls() {
        val tvLimit = findViewById<TextView>(R.id.tv_limit)
        fun renderLimit() { tvLimit.text = if (dailyLimit < 0) "不限" else dailyLimit.toString() }
        findViewById<Button>(R.id.btn_limit_minus).setOnClickListener {
            dailyLimit = when { dailyLimit == -1 -> 1; dailyLimit <= 1 -> -1; else -> dailyLimit - 1 }; renderLimit()
        }
        findViewById<Button>(R.id.btn_limit_plus).setOnClickListener {
            dailyLimit = if (dailyLimit == -1) 1 else dailyLimit + 1; renderLimit()
        }
        val cbNeg = findViewById<CheckBox>(R.id.cb_negative)
        val cbCustom = findViewById<CheckBox>(R.id.cb_custom_neg)
        cbNeg.setOnCheckedChangeListener { _, on -> if (on) cbCustom.isChecked = false }
        cbCustom.setOnCheckedChangeListener { _, on ->
            if (on) cbNeg.isChecked = false
            findViewById<View>(R.id.custom_neg_panel).visibility = if (on) View.VISIBLE else View.GONE
            updateCustomHint()
        }
        findViewById<Button>(R.id.btn_t1).setOnClickListener { pickTime(findViewById(R.id.btn_t1), true) }
        findViewById<Button>(R.id.btn_t2).setOnClickListener { pickTime(findViewById(R.id.btn_t2), false) }
    }

    private fun pickTime(btn: Button, isT1: Boolean) {
        val cur = btn.text.toString().split(":")
        val h = cur[0].toInt(); val mi = cur[1].toInt()
        TimePickerDialog(this, { _, hour, minute ->
            btn.text = "%02d:%02d".format(hour, minute)
            updateCustomHint()
        }, h, mi, true).show()
    }

    private fun updateCustomHint() {
        val t1 = findViewById<Button>(R.id.btn_t1).text.toString()
        val t2 = findViewById<Button>(R.id.btn_t2).text.toString()
        findViewById<TextView>(R.id.tv_custom_hint).text =
            "00:00–$t1 操作 → 记「昨天未打卡」；$t1–$t2 操作 → 记「今天打卡成功」；$t2–24:00 操作 → 记「今天未打卡」；无操作默认成功。"
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
        val latch = java.util.concurrent.CountDownLatch(1)
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { best.set(l); latch.countDown() }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("deprecated") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
        }
        for (p in providers) { try { lm.requestLocationUpdates(p, 0L, 0f, listener, mainLooper) } catch (_: Exception) {} }
        thread {
            if (best.get() == null) latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
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
                android.app.AlertDialog.Builder(this)
                    .setTitle("确认作为标准位置？")
                    .setMessage("位置：${formatLatLng(l.latitude, l.longitude)}\n允许半径（米，50-5000，默认200）")
                    .setView(etR)
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
        cfg.dailyLimit = loaded.dailyLimit; cfg.negative = loaded.negative; cfg.customNeg = loaded.customNeg
        cfg.t1 = loaded.t1; cfg.t2 = loaded.t2; cfg.offsetBackfill = loaded.offsetBackfill
        cfg.photoFromCamera = loaded.photoFromCamera; cfg.photoFromAlbum = loaded.photoFromAlbum
        cfg.textMinWords = loaded.textMinWords; cfg.textNoRepeat = loaded.textNoRepeat
        cfg.locNegative = loaded.locNegative; cfg.locPoints.clear(); cfg.locPoints.addAll(loaded.locPoints)
        cfg.stepTarget = loaded.stepTarget; cfg.timerMinutes = loaded.timerMinutes
        cfg.qrContent = loaded.qrContent.ifBlank { "uuid:" + java.util.UUID.randomUUID() }
        cfg.nfcTagId = loaded.nfcTagId; cfg.voiceMaxSeconds = loaded.voiceMaxSeconds
        cfg.offset = loaded.offset

        findViewById<EditText>(R.id.et_name).setText(it.name)
        selectedTheme = it.theme
        dailyLimit = cfg.dailyLimit
        findViewById<TextView>(R.id.tv_limit).text = if (dailyLimit < 0) "不限" else dailyLimit.toString()
        findViewById<CheckBox>(R.id.cb_negative).isChecked = cfg.negative
        findViewById<CheckBox>(R.id.cb_custom_neg).isChecked = cfg.customNeg
        findViewById<View>(R.id.custom_neg_panel).visibility = if (cfg.customNeg) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btn_t1).text = cfg.t1
        findViewById<Button>(R.id.btn_t2).text = cfg.t2
        updateCustomHint()
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

        // 不可修改策略：规则整体锁定（bug8：之前 LOCKED 仍可改，现在强制生效）
        if (it.editPolicy == "LOCKED") {
            lockRules("规则已锁定（创建后不可修改），仅可修改名称/主题")
        }
        // INTERVAL_N 锁定期：仅名称/主题可改，其余规则禁用
        if (it.editPolicy == "INTERVAL_N" && it.lastEditDate != null &&
            DateUtils.daysSince(it.lastEditDate) < (it.editInterval ?: 0)) {
            lockRules("距上次修改不足 ${it.editInterval} 天，规则暂不可改")
        }
        // 自动方式核心规则锁定
        if (Method.AUTO.key in cfg.methods) lockRules("自动打卡类型创建后核心规则不可改")
        renderThemeChips()
    }

    private var locked = false
    private fun lockRules(msg: String) {
        locked = true
        toast(msg)
        rows.values.forEach { it.switch.isEnabled = false }
        findViewById<CheckBox>(R.id.cb_negative).isEnabled = false
        findViewById<CheckBox>(R.id.cb_custom_neg).isEnabled = false
        findViewById<CheckBox>(R.id.cb_offset).isEnabled = false
        findViewById<Button>(R.id.btn_limit_minus).isEnabled = false
        findViewById<Button>(R.id.btn_limit_plus).isEnabled = false
        findViewById<Button>(R.id.btn_t1).isEnabled = false
        findViewById<Button>(R.id.btn_t2).isEnabled = false
        findViewById<RadioButton>(R.id.rb_mode_a).isEnabled = false
        findViewById<RadioButton>(R.id.rb_mode_b).isEnabled = false
        findViewById<RadioButton>(R.id.rb_mode_c).isEnabled = false
        findViewById<EditText>(R.id.et_offset_n).isEnabled = false
        findViewById<EditText>(R.id.et_offset_k).isEnabled = false
        findViewById<CheckBox>(R.id.cb_offset_auto).isEnabled = false
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
        cfg.negative = findViewById<CheckBox>(R.id.cb_negative).isChecked
        cfg.customNeg = findViewById<CheckBox>(R.id.cb_custom_neg).isChecked
        cfg.t1 = findViewById<Button>(R.id.btn_t1).text.toString()
        cfg.t2 = findViewById<Button>(R.id.btn_t2).text.toString()
        cbPhotoCamera?.let { cfg.photoFromCamera = it.isChecked }
        cbPhotoAlbum?.let { cfg.photoFromAlbum = it.isChecked }
        etTextMin?.let { cfg.textMinWords = it.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 1 }
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
        if (cfg.methods.isEmpty()) { toast("请至少打开一种打卡方式"); return }
        if (!Method.isValid(cfg.methods)) { toast("所选方式存在互斥冲突"); return }
        if (Method.LOCATION.key in cfg.methods && cfg.locPoints.isEmpty()) { toast("位置打卡需先获取一个标准位置"); return }
        if (Method.PHOTO.key in cfg.methods && !cfg.photoFromCamera && !cfg.photoFromAlbum) { toast("拍照打卡需至少勾选一种图片来源"); return }
        collectConfigFromUi()
        if (cfg.customNeg) {
            val a = DateUtils.parseHHmm(cfg.t1); val b = DateUtils.parseHHmm(cfg.t2)
            if (a < 0 || b < 0 || a >= b) { toast("时间点1需早于时间点2（如 05:00 / 13:00）"); return }
        }

        val policyInterval = findViewById<RadioButton>(R.id.rb_interval).isChecked
        val intervalN = findViewById<EditText>(R.id.et_interval).text.toString().toIntOrNull()
        val now = System.currentTimeMillis()
        thread {
            if (editing != null) {
                val old = editing!!
                val updated = old.copy(
                    name = name, theme = selectedTheme,
                    type = ItemConfig.mainType(cfg.methods), configJson = cfg.toJson(),
                    editPolicy = if (policyInterval) "INTERVAL_N" else "LOCKED",
                    editInterval = if (policyInterval) (intervalN ?: 7) else null,
                    lastEditDate = if (!locked || old.lastEditDate == null) DateUtils.today() else old.lastEditDate,
                    updatedAt = now
                )
                repo.updateItem(updated)
            } else {
                val item = CheckinItem(
                    name = name, type = ItemConfig.mainType(cfg.methods), configJson = cfg.toJson(),
                    icon = "default", theme = selectedTheme, sortOrder = repo.itemCount(),
                    editPolicy = if (policyInterval) "INTERVAL_N" else "LOCKED",
                    editInterval = if (policyInterval) (intervalN ?: 7) else null,
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
