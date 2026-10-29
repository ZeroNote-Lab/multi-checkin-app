package com.zerolab.checkin.ui.create

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.zerolab.checkin.R
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.engine.Method
import com.zerolab.checkin.util.QrCode
import com.zerolab.checkin.util.formatLatLng

/**
 * v1.4.2 优化：打卡方式「参数配置」内联面板（从 CreateItemActivity 抽出，约 190 行）。
 *
 * 设计：只负责「读改写 [ItemConfig] + 生成视图」，不持有页面状态；
 * 需要页面能力的部分（定位取点、NFC 绑定、Toast）通过构造函数注入。
 */
class MethodPanelBuilder(
    private val ctx: Activity,
    private val toast: (String) -> Unit,
    private val fetchLocation: (ItemConfig, TextView, () -> Unit) -> Unit,
    private val bindNfc: (ItemConfig, TextView) -> Unit
) {

    /** 该方式是否有可配置参数（决定是否展开面板 / 显示 ⚙） */
    fun hasConfig(m: Method): Boolean = when (m) {
        Method.PHOTO, Method.TEXT, Method.LOCATION, Method.STEPS, Method.TIMER,
        Method.QRCODE, Method.NFC, Method.VOICE -> true
        else -> false
    }

    /** 构建某方式的参数面板；直接读写 target（作用于主配置 cfg 或组子项 cfgSub） */
    fun build(m: Method, target: ItemConfig): LinearLayout {
        fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
        }
        when (m) {
            Method.PHOTO -> {
                box.addView(CheckBox(ctx).apply {
                    text = "允许拍照"; isChecked = target.photoFromCamera; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> target.photoFromCamera = on }
                })
                box.addView(CheckBox(ctx).apply {
                    text = "允许相册"; isChecked = target.photoFromAlbum; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> target.photoFromAlbum = on }
                })
            }
            Method.TEXT -> {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ctx).apply { text = "最低字数 "; textSize = 14f; setTextColor(0xFF1F2430.toInt()) })
                val et = EditText(ctx).apply {
                    setText("${target.textMinWords}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    textSize = 14f; background = ctx.getDrawable(R.drawable.bg_input)
                    layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                et.setOnFocusChangeListener(null)
                // v1.3.30：TextWatcher 实时写入（原失焦写入会丢值）
                et.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        s?.toString()?.toIntOrNull()?.let { target.textMinWords = it.coerceIn(1, 99) }
                    }
                })
                row.addView(et)
                row.addView(TextView(ctx).apply { text = " 字"; textSize = 14f; setTextColor(0xFF6E7F78.toInt()) })
                box.addView(row)
                box.addView(CheckBox(ctx).apply {
                    text = "不可与上次内容重复"; isChecked = target.textNoRepeat; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> target.textNoRepeat = on }
                })
            }
            Method.LOCATION -> {
                box.addView(CheckBox(ctx).apply {
                    text = "负打卡：离开标准点即破戒"; isChecked = target.locNegative; setTextColor(0xFF1F2430.toInt())
                    setOnCheckedChangeListener { _, on -> target.locNegative = on }
                })
                val tv = TextView(ctx).apply {
                    textSize = 12f; setTextColor(0xFF6E7F78.toInt()); setPadding(0, 4, 0, 0)
                }
                fun refreshLoc() {
                    tv.text = if (target.locPoints.isEmpty()) "尚未设定标准位置"
                    else "已设定 ${target.locPoints.size} 个标准点：\n" +
                        target.locPoints.joinToString("\n") { p -> "· ${p.name} ${formatLatLng(p.lat, p.lng)} 半径${p.radius}m" }
                }
                refreshLoc()
                box.addView(tv)
                box.addView(Button(ctx).apply {
                    text = "选择标准位置（可校准偏移）"; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                    setOnClickListener { fetchLocation(target, tv) { refreshLoc() } }
                })
            }
            Method.STEPS -> {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ctx).apply { text = "目标步数 "; textSize = 14f; setTextColor(0xFF1F2430.toInt()) })
                val et = EditText(ctx).apply {
                    setText("${target.stepTarget}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    textSize = 14f; background = ctx.getDrawable(R.drawable.bg_input)
                    layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                et.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        s?.toString()?.toIntOrNull()?.let { target.stepTarget = it.coerceIn(100, 200000) }
                    }
                })
                row.addView(et)
                row.addView(TextView(ctx).apply { text = " 步"; textSize = 14f; setTextColor(0xFF6E7F78.toInt()) })
                box.addView(row)
                box.addView(TextView(ctx).apply {
                    text = "读取系统计步传感器；今日步数以当天首次打开 App 时的读数为基准。Android 10+ 需要「身体活动」权限。"
                    textSize = 11f; setTextColor(0xFF9AA1B2.toInt()); setPadding(0, dp(6), 0, 0)
                })
            }
            Method.TIMER -> {
                box.addView(TextView(ctx).apply { text = "计时方式"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()); setPadding(0, 8, 0, 4) })
                val rbCount = RadioButton(ctx).apply {
                    // v1.3.30：必须显式 id，否则 RadioGroup 互斥失效（NO_ID 时 check(-1) 短路，两个可同选）
                    id = View.generateViewId()
                    text = "倒计时"; isChecked = target.timerMode != "COUNTUP"; setTextColor(0xFF1F2430.toInt())
                }
                val rbUp = RadioButton(ctx).apply {
                    id = View.generateViewId()
                    text = "正计时"; isChecked = target.timerMode == "COUNTUP"; setTextColor(0xFF1F2430.toInt())
                }
                val rg = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
                rg.addView(rbCount); rg.addView(rbUp)
                box.addView(rg)
                rbCount.setOnCheckedChangeListener { _, on -> if (on) target.timerMode = "COUNTDOWN" }
                rbUp.setOnCheckedChangeListener { _, on -> if (on) target.timerMode = "COUNTUP" }
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ctx).apply { text = "时长 "; textSize = 14f; setTextColor(0xFF1F2430.toInt()) })
                val et = EditText(ctx).apply {
                    setText("${target.timerMinutes}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    textSize = 14f; background = ctx.getDrawable(R.drawable.bg_input)
                    layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                et.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        s?.toString()?.toIntOrNull()?.let { target.timerMinutes = it.coerceAtLeast(1) }
                    }
                })
                row.addView(et)
                row.addView(TextView(ctx).apply { text = " 分钟"; textSize = 14f; setTextColor(0xFF6E7F78.toInt()) })
                box.addView(row)
                val cbPause = CheckBox(ctx).apply {
                    text = "可暂停保存（仅正计时）"; isChecked = target.timerPausable; setTextColor(0xFF1F2430.toInt())
                }
                val cbForce = CheckBox(ctx).apply {
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
                val iv = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL }
                }
                fun renderQr() {
                    iv.setImageBitmap(QrCode.bitmap(target.qrContent.ifBlank { "uuid:" + java.util.UUID.randomUUID() }))
                }
                renderQr()
                box.addView(iv, 0)
                box.addView(TextView(ctx).apply {
                    text = "长按二维码保存到相册，可打印张贴"; textSize = 12f; setTextColor(0xFF6E7F78.toInt()); gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 6, 0, 0)
                })
                iv.setOnLongClickListener {
                    val content = target.qrContent.ifBlank { "uuid:" + java.util.UUID.randomUUID() }
                    if (target.qrContent.isBlank()) { target.qrContent = content }
                    QrCode.bitmap(content)?.let { QrCode.saveToGallery(ctx, it, content) }
                    true
                }
                box.addView(Button(ctx).apply {
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
                val tv = TextView(ctx).apply {
                    textSize = 13f; setTextColor(0xFF1F2430.toInt()); setPadding(0, 4, 0, 0)
                    text = if (target.nfcTagId.isBlank()) "尚未绑定 NFC 标签" else "已绑定标签：${target.nfcTagId}"
                }
                box.addView(tv)
                box.addView(Button(ctx).apply {
                    text = if (target.nfcTagId.isBlank()) "绑定 NFC 标签" else "重新绑定 NFC 标签"; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                    setOnClickListener { bindNfc(target, tv) }
                })
            }
            Method.VOICE -> {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ctx).apply { text = "最长 "; textSize = 14f; setTextColor(0xFF1F2430.toInt()) })
                val et = EditText(ctx).apply {
                    setText("${target.voiceMaxSeconds}"); inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    textSize = 14f; background = ctx.getDrawable(R.drawable.bg_input)
                    layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                et.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        s?.toString()?.toIntOrNull()?.let { target.voiceMaxSeconds = it.coerceAtLeast(1) }
                    }
                })
                row.addView(et)
                row.addView(TextView(ctx).apply { text = " 秒"; textSize = 14f; setTextColor(0xFF6E7F78.toInt()) })
                box.addView(row)
            }
            else -> box.addView(TextView(ctx).apply { text = "点击打卡即完成，无需额外设置。"; textSize = 13f; setTextColor(0xFF6E7F78.toInt()) })
        }
        // v1.3.24：内联面板无保存按钮——配置即写；完整性校验由 save()/子项确定时兜底
        return box
    }
}
