package com.zerolab.checkin.ui.flow

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationListener
import android.media.MediaPlayer
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
import com.zerolab.checkin.util.AudioRecorder
import com.zerolab.checkin.util.DateUtils
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
            // 相机取消/启动失败：自动降级到相册
            toast("拍照未完成，已切换到相册")
            pickImage.launch("image/*")
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
        doneMethods.clear(); queue.clear()
        val interactive = cfg.methods.filter { it != Method.AUTO.key }
        // 组合（多方式）：弹出方式卡片，逐个完成
        if (interactive.size > 1) {
            comboMethods = interactive
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
            val r = CheckinEngine.perform(it, repo, photoPath, textContent, voicePath, lat, lng,
                extra = "{\"method\":\"$m\"}")
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
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 16, 36, 4) }
        methods.forEach { m ->
            val done = m in doneMethods
            val row = TextView(ctx).apply {
                text = "${if (done) "✅" else "○"}  ${Method.of(m)?.label ?: m}"
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 22, 0, 22)
                setTextColor(if (done) 0xFF2FBF71.toInt() else 0xFF1F2430.toInt())
                if (!done) setOnClickListener { runMethod(m) }
            }
            box.addView(row)
        }
        comboDialog = AlertDialog.Builder(ctx)
            .setTitle("打卡方式（${doneMethods.size}/${methods.size}）")
            .setView(box)
            .setNegativeButton("关闭", null)
            .create()
        comboDialog!!.show()
    }

    private fun updateComboCard() {
        val methods = comboMethods ?: return
        if (methods.all { it in doneMethods }) {
            comboDialog?.dismiss(); comboDialog = null
            toast("全部完成，打卡成功 ✓")
            onDone()
        } else showComboCard()
    }

    private fun runMethod(m: String) {
        comboCurrent = m
        photoPath = null; textContent = null; voicePath = null; lat = null; lng = null
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
        if (opts.isEmpty()) { stepSuccess(); return }
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
            toast("无法启动相机，已切换到相册")
            pickImage.launch("image/*")
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
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) { loc.set(l); latch.countDown() }
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
                @Deprecated("deprecated") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
            }
            for (p in providers) { try { lm.requestLocationUpdates(p, 0L, 0f, listener, main.looper) } catch (_: Exception) {} }
            if (loc.get() == null) latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            main.post {
                loading.dismiss()
                val l = loc.get()
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
        val msg = "当前位置：%.5f, %.5f\n最近地点：$nearestName（%.0f 米）".format(la, ln, nearestDist)
        val pass = if (c.locNegative) !within else within
        val title = if (pass) "位置符合要求" else "位置不符合要求"
        AlertDialog.Builder(ctx).setTitle(title).setMessage(
            msg + if (c.locNegative) "\n模式：离开设定范围才有效（当前${if (within) "在范围内" else "已离开"}）"
            else "\n需要在设定范围内（当前${if (within) "在范围内" else "不在范围"}）"
        ).setNegativeButton("取消", null)
            .setPositiveButton(if (pass) "使用该位置" else "仍然继续") { _, _ -> stepSuccess() }
            .show()
    }

    private fun distanceMeters(la1: Double, ln1: Double, la2: Double, ln2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(la2 - la1); val dLn = Math.toRadians(ln2 - ln1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLn / 2) * sin(dLn / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    // ---------- 步数（读取真实计步传感器，达标后才可确认） ----------
    private fun doSteps() {
        val c = cfg ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            AlertDialog.Builder(ctx).setTitle("步数打卡")
                .setMessage("目标步数：${c.stepTarget} 步。\n此设备不支持计步传感器，无法完成步数打卡。")
                .setPositiveButton("知道了", null).show()
            return
        }
        var listener: android.hardware.SensorEventListener? = null
        val tv = TextView(ctx).apply {
            textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 36, 0, 36)
            text = "当前 0 / ${c.stepTarget} 步"
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
        val startSteps = java.util.concurrent.atomic.AtomicLong(-1L)
        listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val v = e.values.firstOrNull()?.toLong() ?: return
                if (startSteps.get() < 0) startSteps.set(v)
                val walked = (v - startSteps.get()).coerceAtLeast(0L)
                if (walked >= c.stepTarget) {
                    tv.text = "达标 ✓ 当前 $walked 步，点击「确认打卡」完成"
                    posBtn.isEnabled = true
                } else tv.text = "当前 $walked / ${c.stepTarget} 步"
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(listener!!, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    // ---------- 倒计时 ----------
    private fun doTimer() {
        val c = cfg ?: return
        val totalSec = (c.timerMinutes * 60).coerceAtLeast(60)
        val endAt = System.currentTimeMillis() + totalSec * 1000L
        val tv = TextView(ctx).apply { textSize = 30f; gravity = android.view.Gravity.CENTER; setPadding(0, 40, 0, 40) }
        val dlg = AlertDialog.Builder(ctx).setTitle("倒计时 ${c.timerMinutes} 分钟").setView(tv)
            .setCancelable(false)
            .setNegativeButton("放弃", null)
            .setPositiveButton("完成打卡") { _, _ -> stepSuccess() }
            .create()
        dlg.show()
        val posBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        posBtn.isEnabled = false // 倒计时结束才可完成
        val runnable = object : Runnable {
            override fun run() {
                val remain = ((endAt - System.currentTimeMillis()) / 1000L).toInt()
                if (remain <= 0) {
                    tv.text = "时间到 ✓ 点击「完成打卡」"
                    posBtn.isEnabled = true
                    return
                }
                tv.text = "%02d:%02d".format(remain / 60, remain % 60)
                main.postDelayed(this, 500)
            }
        }
        main.post(runnable)
    }

    // ---------- 扫码（稳定模拟匹配） ----------
    private fun doQr() {
        val c = cfg ?: return
        AlertDialog.Builder(ctx).setTitle("扫码打卡")
            .setMessage("请将摄像头对准创建时生成的专属二维码。\n\n预设内容：${c.qrContent.take(24)}…\n（点击下方模拟扫描成功）")
            .setNegativeButton("取消", null)
            .setPositiveButton("模拟扫描") { _, _ -> toast("扫码匹配成功"); stepSuccess() }.show()
    }

    // ---------- NFC（稳定模拟匹配） ----------
    private fun doNfc() {
        val c = cfg ?: return
        AlertDialog.Builder(ctx).setTitle("NFC 打卡")
            .setMessage("请将手机贴近已绑定的 NFC 标签。\n绑定标签：${c.nfcTagId.ifBlank { "（未绑定）" }}\n（点击模拟触碰成功）")
            .setNegativeButton("取消", null)
            .setPositiveButton("模拟触碰") { _, _ ->
                if (c.nfcTagId.isBlank()) toast("该打卡项未绑定 NFC 标签"); else { toast("NFC 匹配成功"); stepSuccess() }
            }.show()
    }

    // ---------- 语音 ----------
    private fun doVoice() {
        val c = cfg ?: return
        val btn = Button(ctx).apply { text = "开始录音（最长 ${c.voiceMaxSeconds} 秒）"; setPadding(40, 60, 40, 60) }
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
                lat = lat, lng = lng
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
