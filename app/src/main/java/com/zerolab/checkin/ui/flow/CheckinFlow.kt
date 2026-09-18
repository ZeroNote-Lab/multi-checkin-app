package com.zerolab.checkin.ui.flow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.media.MediaPlayer
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.engine.CheckinResult
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.ui.scan.ScanActivity
import com.zerolab.checkin.util.AudioRecorder
import com.zerolab.checkin.util.DateUtils
import com.zerolab.checkin.util.formatLatLng
import com.zerolab.checkin.util.ImageUtil
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 统一打卡交互。
 * 单方式：收集该方式材料后写一条记录；
 * 组合（多开关）：点打卡按钮弹出「打卡方式卡片」，逐个点击方式完成，各自记一条成功记录，
 * 全部完成后当天变绿。
 * 任何一步失败都只提示、不崩溃。
 */
class CheckinFlow(private val fragment: Fragment, private val onDone: () -> Unit) {

    private val ctx get() = fragment.requireContext()
    private val repo get() = (fragment.requireActivity().application as CheckinApp).repository
    private val main = Handler(Looper.getMainLooper())

    private var item: CheckinItem? = null
    private var cfg: ItemConfig? = null
    private val queue = ArrayDeque<String>()

    // 组合打卡状态
    private var comboMethods: List<String>? = null
    private val doneMethods = mutableSetOf<String>()
    private var comboCurrent: String? = null
    private var comboDialog: AlertDialog? = null

    // 收集到的材料
    private var photoPath: String? = null
    private var textContent: String? = null
    private var voicePath: String? = null
    private var lat: Double? = null
    private var lng: Double? = null
    // v1.2.0：时间打卡本次会话的实际计时信息（写记录时附加到 extraJson）
    private var timerExtra: String? = null

    private var pendingPhotoUri: android.net.Uri? = null
    private var audioRecorder = AudioRecorder()

    // ---- ActivityResult / 权限注册（须在 Fragment 未 STARTED 时创建）----
    private val permCamera = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doPhoto() else toast("需要相机权限才能拍照打卡")
    }
    private val permLocation = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doLocation() else toast("需要定位权限才能位置打卡")
    }
    private val permAudio = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doVoice() else toast("需要录音权限才能语音打卡")
    }
    private val permSensor = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doSteps() else toast("需要身体传感器权限才能步数打卡")
    }
    private val takePhoto = fragment.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            val uri = pendingPhotoUri
            thread {
                val src = File(ctx.cacheDir, "photo_tmp.jpg")
                val outDir = File(ctx.filesDir, "checkin_photos/${item?.id ?: 0}")
                var compressed: File? = null
                var err: String? = null
                try {
                    if (uri != null) {
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null && bytes.isNotEmpty()) {
                            FileOutputStream(src).use { it.write(bytes) }
                            compressed = ImageUtil.compressWebp(src, outDir)
                            if (compressed == null) err = "压缩失败或图片为空"
                        } else err = "照片数据为空"
                    } else err = "URI 丢失"
                } catch (e: Exception) {
                    err = e.message
                }
                if (err != null) android.util.Log.e("CheckinFlow", "photo save failed: $err") else android.util.Log.i("CheckinFlow", "photo compress ok: ${compressed?.absolutePath}")
                main.post {
                    if (compressed != null) {
                        photoPath = compressed.absolutePath
                        toast("照片已保存")
                    } else {
                        // PRD：打卡状态不依赖文件，图片失败也完成打卡
                        toast("照片保存失败，已按打卡成功处理")
                    }
                    stepSuccess()
                }
            }
        } else {
            // v1.1.8：相机取消直接返回（不降级相册，保证拍照实时性）
            toast("拍照未完成")
        }
    }
    private val pickImage = fragment.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) thread {
            val src = File(ctx.cacheDir, "album_tmp.jpg")
            try { ctx.contentResolver.openInputStream(uri).use { it?.copyTo(FileOutputStreamSafe(src)) } } catch (_: Exception) {}
            val outDir = File(ctx.filesDir, "checkin_photos/${item?.id ?: 0}")
            val c = ImageUtil.compressWebp(src, outDir)
            main.post { if (c != null) { photoPath = c.absolutePath; stepSuccess() } else toast("图片读取失败") }
        } else toast("已取消选择图片")
    }

    private fun FileOutputStreamSafe(f: File) = java.io.FileOutputStream(f)

    fun start(item: CheckinItem, cfg: ItemConfig) {
        this.item = item; this.cfg = cfg
        photoPath = null; textContent = null; voicePath = null; lat = null; lng = null
        timerExtra = null
        doneMethods.clear(); queue.clear()
        val interactive = cfg.methods.filter { it != Method.AUTO.key }
        // 组合（多方式）：弹出方式卡片，逐个完成
        if (interactive.size > 1) {
            comboMethods = interactive
            // v1.1.4：从数据库读取今日已完成方式，退出重进也能显示 ✅ 并禁止重复打卡
            val recs = repo.recordsOfDay(item.id, DateUtils.today())
            interactive.forEach { m ->
                if (recs.any { r -> r.status == "SUCCESS" && r.extraJson?.contains(m) == true }) doneMethods.add(m)
            }
            // v1.2.0：完成判定按 comboRequired（0=全部）；随心记日记式不做全完成判定、可继续记录
            val req = if (cfg.comboRequired in 1..interactive.size) cfg.comboRequired else interactive.size
            if (!cfg.journalMode && doneMethods.size >= req) { toast("今日已完成打卡目标"); onDone(); return }
            showComboCard()
            return
        }
        comboMethods = null
        interactive.forEach { queue.addLast(it) }
        if (queue.isEmpty()) { finalize(); return }
        next()
    }

    /** 单方式模式：按队列依次收集；成功后取下一项 */
    private fun next() {
        if (queue.isEmpty()) { finalize(); return }
        runMethod(queue.removeFirst())
    }

    /** 每个方式的成功回调：组合模式立即按方式写记录；单方式模式继续队列 */
    private fun stepSuccess() {
        val methods = comboMethods
        if (methods == null) { next(); return }
        val m = comboCurrent ?: return
        val it = item ?: return
        thread {
            // v1.2.0：组合方式记录合并时间打卡实际计时信息
            val jo = org.json.JSONObject().put("method", m)
            timerExtra?.let { te -> try {
                val o = org.json.JSONObject(te)
                val keys = o.keys()
                while (keys.hasNext()) { val k = keys.next(); jo.put(k, o.get(k)) }
            } catch (_: Exception) {} }
            val r = CheckinEngine.perform(it, repo, photoPath, textContent, voicePath, lat, lng,
                extra = jo.toString())
            main.post {
                when (r) {
                    is CheckinResult.Blocked -> toast(r.reason)
                    is CheckinResult.Ok -> {
                        doneMethods.add(m)
                        toast("「${Method.of(m)?.label ?: m}」完成 ✓")
                        updateComboCard()
                    }
                }
            }
        }
    }

    // ---------- 组合打卡方式卡片 ----------
    private fun showComboCard() {
        val methods = comboMethods ?: return
        comboDialog?.dismiss()
        val c = cfg ?: return
        val journal = c.journalMode
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 16, 36, 4) }
        methods.forEach { m ->
            // v1.2.0：随心记日记式——所有方式均可点，不锁定已完成项（可继续记录）
            val done = !journal && m in doneMethods
            val row = TextView(ctx).apply {
                text = "${if (done) "✅" else "○"}  ${Method.of(m)?.label ?: m}"
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 22, 0, 22)
                setTextColor(if (done) 0xFF2FBF71.toInt() else 0xFF1F2430.toInt())
                setOnClickListener { runMethod(m) }
            }
            box.addView(row)
        }
        comboDialog = AlertDialog.Builder(ctx)
            .setTitle(if (journal) "📔 随心记 · 选择记录方式（今日已记 ${doneMethods.size} 次）"
                else "打卡方式（${doneMethods.size}/${methods.size}）")
            .setView(box)
            .setNegativeButton("关闭", null)
            .create()
        comboDialog!!.show()
    }

    private fun updateComboCard() {
        val methods = comboMethods ?: return
        val c = cfg ?: return
        val req = if (c.comboRequired in 1..methods.size) c.comboRequired else methods.size
        // v1.2.0：随心记日记式——完成即记录，不判全完成、保持弹窗可继续
        if (!c.journalMode && doneMethods.size >= req) {
            comboDialog?.dismiss(); comboDialog = null
            toast(if (methods.size > 1) "已完成目标打卡项，打卡成功 ✓" else "打卡成功 ✓")
            onDone()
        } else {
            showComboCard()
            // v1.1.6：每完成一个子项立即刷新备注区/日历（此前仅全部完成才刷新，部分完成时页面无反应）
            onDone()
        }
    }

    private fun runMethod(m: String) {
        // v1.1.4：组合模式下已完成的打卡项不可重复执行（以数据库记录为准）；v1.2.0 随心记除外
        if (comboMethods != null && !(cfg?.journalMode == true)) {
            val it = item ?: return
            val recs = repo.recordsOfDay(it.id, DateUtils.today())
            if (recs.any { r -> r.status == "SUCCESS" && r.extraJson?.contains(m) == true }) {
                toast("今日「${Method.of(m)?.label ?: m}」已完成")
                return
            }
        }
        comboCurrent = m
        photoPath = null; textContent = null; voicePath = null; lat = null; lng = null
        timerExtra = null
        when (m) {
            Method.TEXT.key -> doText()
            Method.PHOTO.key -> ensure(Manifest.permission.CAMERA, permCamera) { doPhoto() }
            Method.LOCATION.key -> ensure(Manifest.permission.ACCESS_FINE_LOCATION, permLocation) { doLocation() }
            Method.VOICE.key -> ensure(Manifest.permission.RECORD_AUDIO, permAudio) { doVoice() }
            Method.STEPS.key -> ensure(Manifest.permission.BODY_SENSORS, permSensor) { doSteps() }
            Method.TIMER.key -> doTimer()
            Method.QRCODE.key -> doQr()
            Method.NFC.key -> doNfc()
            else -> stepSuccess()
        }
    }

    private fun ensure(perm: String, launcher: androidx.activity.result.ActivityResultLauncher<String>, ok: () -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) ok()
        else launcher.launch(perm)
    }

    // ---------- 文字 ----------
    private fun doText() {
        val c = cfg ?: return
        val et = EditText(ctx).apply { hint = "输入打卡内容（至少 ${c.textMinWords} 字）"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; setMinLines(2) }
        val container = LinearLayout(ctx).apply { setPadding(48, 24, 48, 0); addView(et) }
        AlertDialog.Builder(ctx).setTitle("文字打卡").setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                val s = et.text.toString().trim()
                if (s.length < c.textMinWords) { toast("字数不足"); return@setPositiveButton }
                if (c.textNoRepeat) {
                    val last = repo.allRecords(item!!.id).firstOrNull { !it.textContent.isNullOrBlank() }?.textContent
                    if (last == s) { toast("内容不可与上次重复"); return@setPositiveButton }
                }
                textContent = s; stepSuccess()
            }.show()
    }

    // ---------- 拍照 ----------
    private fun doPhoto() {
        val c = cfg ?: return
        val opts = mutableListOf<String>()
        val hasCamera = try { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) } catch (_: Exception) { false }
        if (c.photoFromCamera && hasCamera) opts.add("拍照")
        if (c.photoFromAlbum) opts.add("从相册选择")
        if (opts.isEmpty()) { toast("该打卡项未配置图片来源，请在编辑页设置"); return }
        if (opts.size == 1) { if (opts[0] == "拍照") openCamera() else pickImage.launch("image/*"); return }
        AlertDialog.Builder(ctx).setTitle("选择图片来源").setItems(opts.toTypedArray()) { _, i ->
            if (opts[i] == "拍照") openCamera() else pickImage.launch("image/*")
        }.setNegativeButton("取消", null).show()
    }

    private fun openCamera() {
        try {
            val tmp = File(ctx.cacheDir, "photo_tmp.jpg")
            val authority = ctx.packageName + ".fileprovider"
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, authority, tmp)
            pendingPhotoUri = uri
            takePhoto.launch(uri)
        } catch (e: Exception) {
            // v1.1.8：相机启动失败直接返回（不降级相册）
            toast("无法启动相机")
        }
    }

    // ---------- 位置 ----------
    @SuppressLint("MissingPermission")
    private fun doLocation() {
        val c = cfg ?: return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loading = AlertDialog.Builder(ctx).setMessage("正在获取当前位置…").setCancelable(false).show()
        thread {
            val loc = java.util.concurrent.atomic.AtomicReference<Location?>(null)
            val providers = try {
                lm.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
                    .ifEmpty { lm.getProviders(true) }
            } catch (_: Exception) { emptyList() }
            for (p in providers) { try { val l = lm.getLastKnownLocation(p) ?: continue; if (loc.get() == null || l.accuracy < (loc.get()?.accuracy ?: 9999f)) loc.set(l) } catch (_: Exception) {} }
            val latch = java.util.concurrent.CountDownLatch(1)
            // 始终等待一次新的定位回调（优先实时位置，避免间隔短时误用缓存旧点）
            val fresh = java.util.concurrent.atomic.AtomicReference<Location?>(null)
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) { fresh.set(l); latch.countDown() }
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
                @Deprecated("deprecated") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
            }
            for (p in providers) { try { lm.requestLocationUpdates(p, 0L, 0f, listener, main.looper) } catch (_: Exception) {} }
            latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            main.post {
                loading.dismiss()
                val l = fresh.get() ?: loc.get()
                if (l == null) { toast("无法获取定位，请到空旷处重试"); return@post }
                lat = l.latitude; lng = l.longitude
                judgeLocation(c, l.latitude, l.longitude)
            }
        }
    }
    private fun judgeLocation(c: ItemConfig, la: Double, ln: Double) {
        if (c.locPoints.isEmpty()) { stepSuccess(); return }
        // 找到最近的点与距离
        var nearestName = c.locPoints[0].name; var nearestDist = Double.MAX_VALUE; var within = false
        c.locPoints.forEach { p ->
            val d = distanceMeters(la, ln, p.lat, p.lng)
            if (d < nearestDist) { nearestDist = d; nearestName = p.name }
            if (d <= p.radius) within = true
        }
        val msg = "当前位置：${formatLatLng(la, ln)}\n最近地点：$nearestName（%.0f 米）".format(nearestDist)
        val pass = if (c.locNegative) !within else within
        if (!pass) {
            AlertDialog.Builder(ctx).setTitle("位置不符合要求").setMessage(
                msg + if (c.locNegative) "\n模式：离开设定范围才有效（当前${if (within) "在范围内" else "已离开"}）"
                else "\n需要在设定范围内（当前${if (within) "在范围内" else "不在范围"}）"
            ).setPositiveButton("知道了", null).show()
            return
        }
        AlertDialog.Builder(ctx).setTitle("位置符合要求").setMessage(
            msg + if (c.locNegative) "\n模式：离开设定范围才有效（当前${if (within) "在范围内" else "已离开"}）"
            else "\n需要在设定范围内（当前${if (within) "在范围内" else "不在范围"}）"
        ).setNegativeButton("取消", null)
            .setPositiveButton("使用该位置") { _, _ -> stepSuccess() }
            .show()

    }

    private fun distanceMeters(la1: Double, ln1: Double, la2: Double, ln2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(la2 - la1); val dLn = Math.toRadians(ln2 - ln1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLn / 2) * sin(dLn / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    // ---------- 步数（读取真实计步传感器，显示今日步数，达标后才可确认） ----------
    private fun doSteps() {
        val c = cfg ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val stepCounter = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
        val stepDetector = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR)
        if (stepCounter == null && stepDetector == null) {
            AlertDialog.Builder(ctx).setTitle("步数打卡")
                .setMessage("目标步数：${c.stepTarget} 步。\n此设备不支持计步传感器，无法完成步数打卡。")
                .setPositiveButton("知道了", null).show()
            return
        }
        // 今日步数基准：TYPE_STEP_COUNTER 返回开机累计值，用「当天首次打开」的值作为基准，
        // 跨天自动重置；若设备 STEP_COUNTER 不上报事件，则回退 STEP_DETECTOR 逐步入账（从本次打开起计）。
        val prefs = ctx.getSharedPreferences("steps_today", Context.MODE_PRIVATE)
        val today = DateUtils.today()
        var base = prefs.getLong("base", -1L)
        val baseDate = prefs.getString("baseDate", "")
        if (baseDate != today) base = -1L

        var listener: android.hardware.SensorEventListener? = null
        val sensorNames = listOfNotNull(stepCounter?.let { "计步器" }, stepDetector?.let { "步数检测器" }).joinToString(" + ")
        val tv = TextView(ctx).apply {
            textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 36, 0, 36)
            text = "已连接：$sensorNames\n正在等待计步数据…\n（静止时无数据，请走动几步）"
        }
        val dlg = AlertDialog.Builder(ctx).setTitle("步数打卡（目标 ${c.stepTarget} 步）").setView(tv)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> listener?.let { sm.unregisterListener(it) } }
            .setPositiveButton("确认打卡", null)
            .create()
        dlg.show()
        val posBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        posBtn.isEnabled = false
        posBtn.setOnClickListener {
            listener?.let { sm.unregisterListener(it) }; dlg.dismiss(); stepSuccess()
        }
        var baseRef = base
        var counterWalked: Long? = null
        var detCount = 0
        var useDetector = false
        var gotEvent = false
        val updateUi = {
            val walked = counterWalked ?: (if (useDetector) detCount.toLong() else null)
            if (walked == null) {
                tv.text = "正在读取计步器…"
            } else if (walked >= c.stepTarget) {
                tv.text = if (useDetector) "本次打开起已走 $walked 步，达标 ✓\n点击「确认打卡」完成"
                else "今日 $walked 步，达标 ✓\n点击「确认打卡」完成"
                posBtn.isEnabled = true
            } else {
                tv.text = if (useDetector) "本次打开起已走 $walked / ${c.stepTarget} 步"
                else "今日 $walked / ${c.stepTarget} 步"
            }
        }
        listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                gotEvent = true
                when (e.sensor.type) {
                    android.hardware.Sensor.TYPE_STEP_COUNTER -> {
                        val v = e.values.firstOrNull()?.toLong() ?: return
                        if (baseRef < 0) {
                            baseRef = v
                            prefs.edit().putLong("base", v).putString("baseDate", today).apply()
                        }
                        counterWalked = (v - baseRef).coerceAtLeast(0L)
                    }
                    android.hardware.Sensor.TYPE_STEP_DETECTOR -> {
                        detCount++
                        if (counterWalked == null) useDetector = true
                    }
                }
                updateUi()
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        try {
            if (stepCounter != null) sm.registerListener(listener!!, stepCounter, android.hardware.SensorManager.SENSOR_DELAY_FASTEST, main)
            if (stepDetector != null) sm.registerListener(listener!!, stepDetector, android.hardware.SensorManager.SENSOR_DELAY_FASTEST, main)
        } catch (e: SecurityException) {
            tv.text = "未授予身体传感器权限，无法读取步数"
        } catch (_: Exception) {
            tv.text = "计步传感器启动失败，请重试"
        }
        // 5 秒无任何传感器事件：提示用户走动（部分设备需实际走步后才上报首个事件）
        android.os.Handler(ctx.mainLooper).postDelayed({
            if (!gotEvent) tv.text = "尚无计步数据，请走动几步后再看\n（此设备静止时不统计步数，走动即开始计数）"
        }, 5000)
    }
    // ---------- 时间打卡（v1.2.0：倒计时 / 正计时 / 暂停保存续时） ----------
    private fun doTimer() {
        val c = cfg ?: return
        val it = item ?: return
        val countUp = c.timerMode == "COUNTUP"
        val pausable = c.timerPausable
        val totalSec = (c.timerMinutes * 60).coerceAtLeast(60)
        val today = DateUtils.today()
        // v1.2.0：读取当天最新 PAUSED 进度续时（倒计时续剩余 / 正计时续已走）
        val lastPaused = repo.recordsOfDay(it.id, today).lastOrNull { r -> r.status == "PAUSED" }
        var remainSec = totalSec
        var elapsedSec = 0
        var startBase = System.currentTimeMillis()
        lastPaused?.extraJson?.let { js ->
            try {
                val o = org.json.JSONObject(js)
                if (countUp) {
                    elapsedSec = o.optInt("elapsedSec", 0)
                    startBase = System.currentTimeMillis() - elapsedSec * 1000L
                } else {
                    remainSec = o.optInt("remainSec", totalSec)
                    startBase = System.currentTimeMillis() - (totalSec - remainSec) * 1000L
                }
            } catch (_: Exception) {}
        }
        val endAt = System.currentTimeMillis() + remainSec * 1000L
        val tv = TextView(ctx).apply { textSize = 30f; gravity = android.view.Gravity.CENTER; setPadding(0, 40, 0, 40) }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (countUp) "正计时 · 目标 ${c.timerMinutes} 分钟" else "倒计时 ${c.timerMinutes} 分钟")
            .setView(tv)
            .setCancelable(false)
            .setNegativeButton("放弃", null)
        if (pausable) {
            builder.setNeutralButton("暂停保存") { _, _ ->
                saveTimerPause(it.id, today, countUp, elapsedSec, remainSec, totalSec)
                dlgSafeDismiss()
                onDone()
            }
        }
        builder.setPositiveButton("完成打卡") { _, _ ->
            // v1.2.0 正计时：未达目标点击 → 提示失败、计时继续（弹窗不关闭）
            if (countUp && elapsedSec < totalSec) {
                val need = totalSec - elapsedSec
                toast("未到目标时长（还需 %02d:%02d），计时继续 ~".format(need / 60, need % 60))
            } else {
                finishTimer(countUp, totalSec, elapsedSec)
            }
        }
        val dlg = builder.create()
        // v1.2.0 fix：在 show() 之前注册 onShow，复写 Positive 按钮点击，
        // 避免 AlertDialog 默认点击即关闭——正计时未达目标时保持弹窗、计时继续；达标才完成并关闭
        dlg.setOnShowListener {
            val pb = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            pb.isEnabled = countUp
            pb.setOnClickListener {
                if (countUp && elapsedSec < totalSec) {
                    val need = totalSec - elapsedSec
                    toast("未到目标时长（还需 %02d:%02d），计时继续 ~".format(need / 60, need % 60))
                } else {
                    finishTimer(countUp, totalSec, elapsedSec)
                    dlg.dismiss()
                }
            }
        }
        dlg.show()
        timerDialogRef = dlg
        val posBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        // 倒计时：结束才可完成；正计时：始终可点，未达目标点击提示失败并继续
        posBtn.isEnabled = countUp
        val runnable = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                val now = System.currentTimeMillis()
                if (countUp) {
                    elapsedSec = ((now - startBase) / 1000L).toInt()
                    val fmt = "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60)
                    tv.text = if (elapsedSec >= totalSec)
                        "$fmt 已达标 ✓\n点击「完成打卡」记录用时" else
                        "$fmt / %02d:%02d".format(totalSec / 60, totalSec % 60)
                } else {
                    val remain = ((endAt - now) / 1000L).toInt()
                    if (remain <= 0) {
                        tv.text = "时间到 ✓ 点击「完成打卡」"
                        posBtn.isEnabled = true
                        main.postDelayed(this, 500)
                        return
                    }
                    tv.text = "%02d:%02d".format(remain / 60, remain % 60)
                }
                main.postDelayed(this, 500)
            }
        }
        main.post(runnable)
    }

    /** 暂停保存：写一条 PAUSED 记录（复用 checkin_record，不占打卡次数） */
    private fun saveTimerPause(itemId: Long, today: String, countUp: Boolean, elapsedSec: Int, remainSec: Int, totalSec: Int) {
        val jo = org.json.JSONObject()
        jo.put("timerMode", if (countUp) "COUNTUP" else "COUNTDOWN")
        if (countUp) { jo.put("elapsedSec", elapsedSec); jo.put("targetSec", totalSec) }
        else { jo.put("remainSec", remainSec); jo.put("totalSec", totalSec) }
        repo.insertRecord(com.zerolab.checkin.data.entity.CheckinRecord(
            itemId = itemId, checkinDate = today, checkinTime = System.currentTimeMillis(),
            status = "PAUSED", extraJson = jo.toString()))
        toast("已暂停保存，下次打卡继续计时 ⏸")
    }

    /** 完成时间打卡：记录实际计时信息后走 stepSuccess */
    private fun finishTimer(countUp: Boolean, totalSec: Int, elapsedSec: Int) {
        val jo = org.json.JSONObject()
        jo.put("timerMode", if (countUp) "COUNTUP" else "COUNTDOWN")
        if (countUp) { jo.put("elapsedSec", elapsedSec); jo.put("targetSec", totalSec) }
        else { jo.put("totalSec", totalSec) }
        timerExtra = jo.toString()
        stepSuccess()
    }

    private var timerDialogRef: AlertDialog? = null
    private fun dlgSafeDismiss() { try { timerDialogRef?.dismiss() } catch (_: Exception) {} }

    // ---------- 扫码（真实相机扫码，匹配才打卡） ----------
    private val scanLauncher =
        fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                toast("扫码匹配成功")
                stepSuccess()
            } else {
                toast("已取消扫码，未打卡")
            }
        }

    private fun doQr() {
        val c = cfg ?: return
        if (c.qrContent.isBlank()) { toast("该打卡项未生成专属二维码"); return }
        val intent = Intent(ctx, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_EXPECT, c.qrContent)
        scanLauncher.launch(intent)
    }

    // ---------- NFC（真实读取标签，匹配才打卡） ----------
    /** 是否正在等待 NFC 标签贴合（由 MainActivity 前台分发回调） */
    var awaitingNfc = false

    /** MainActivity.onNewIntent 读到标签后回调 */
    fun nfcDetected(tagId: String) {
        if (!awaitingNfc) return
        awaitingNfc = false
        nfcDialog?.dismiss(); nfcDialog = null
        val c = cfg ?: return
        if (c.nfcTagId.isBlank()) { toast("该打卡项未绑定 NFC 标签"); return }
        if (tagId.equals(c.nfcTagId, ignoreCase = true)) {
            toast("NFC 匹配成功")
            stepSuccess()
        } else {
            toast("NFC 标签不匹配（读到 ${tagId.take(12)}…）")
        }
    }

    private var nfcDialog: AlertDialog? = null

    private fun doNfc() {
        val c = cfg ?: return
        if (c.nfcTagId.isBlank()) { toast("该打卡项未绑定 NFC 标签"); return }
        val nfc = NfcAdapter.getDefaultAdapter(ctx)
        if (nfc == null) {
            AlertDialog.Builder(ctx).setTitle("NFC 打卡")
                .setMessage("此设备不支持 NFC，无法完成 NFC 打卡。\n\n绑定标签：${c.nfcTagId.take(16)}…")
                .setPositiveButton("知道了", null).show()
            return
        }
        if (!nfc.isEnabled) {
            AlertDialog.Builder(ctx).setTitle("NFC 打卡")
                .setMessage("系统 NFC 已关闭，请先在系统设置中开启 NFC 后再打卡。")
                .setPositiveButton("知道了", null).show()
            return
        }
        awaitingNfc = true
        val dlg = AlertDialog.Builder(ctx).setTitle("NFC 打卡")
            .setMessage("请将手机贴近已绑定的 NFC 标签。\n\n绑定标签：${c.nfcTagId.take(16)}…\n\n读取成功后会自动完成打卡")
            .setNegativeButton("取消") { _, _ -> awaitingNfc = false; nfcDialog = null }
            .setPositiveButton("已完成", null)
            .create()
        nfcDialog = dlg
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (awaitingNfc) toast("尚未读取到标签，请保持贴近") else dlg.dismiss()
        }
    }

    // ---------- 语音 ----------
    private fun doVoice() {
        val c = cfg ?: return
        val btn = Button(ctx).apply {
            text = "开始录音（最长 ${c.voiceMaxSeconds} 秒）"; setPadding(40, 60, 40, 60)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF39C5BB.toInt())
            setTextColor(android.graphics.Color.WHITE)
        }
        val dlg = AlertDialog.Builder(ctx).setTitle("语音打卡").setView(btn).setNegativeButton("取消", null).create()
        var recording = false
        btn.setOnClickListener {
            if (!recording) {
                try {
                    audioRecorder.start(File(ctx.filesDir, "checkin_voices/${item?.id ?: 0}"), c.voiceMaxSeconds)
                    recording = true; btn.text = "停止并使用"
                    main.postDelayed({ if (recording) btn.performClick() }, c.voiceMaxSeconds * 1000L)
                } catch (e: Exception) { toast("录音启动失败：${e.message}") }
            } else {
                recording = false
                val f = audioRecorder.stop()
                dlg.dismiss()
                if (f != null && f.exists()) { voicePath = f.absolutePath; stepSuccess() } else toast("录音太短或失败")
            }
        }
        dlg.show()
    }

    // ---------- 写入 ----------
    private fun finalize() {
        val it = item ?: return
        thread {
            val r = CheckinEngine.perform(
                it, repo,
                photoPath = photoPath, text = textContent, voicePath = voicePath,
                lat = lat, lng = lng, extra = timerExtra // v1.2.0：时间打卡附加实际计时信息
            )
            main.post {
                when (r) {
                    is CheckinResult.Ok -> {
                        val c2 = cfg
                        val msg = when {
                            c2?.customNeg == true -> if (r.status == "SUCCESS") "时段内打卡成功 ✓"
                                else if (r.date == DateUtils.today()) "已记录：今天未打卡" else "已记录：昨天未打卡"
                            r.status == "FAIL" -> "已记录"
                            else -> "打卡成功 ✓"
                        }
                        toast(msg)
                        onDone()
                    }
                    is CheckinResult.Blocked -> toast(r.reason)
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
}
