package com.zerolab.checkin.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.zerolab.checkin.R
import com.zerolab.checkin.step.StepCounterService
import com.zerolab.checkin.theme.ThemeUi
import com.zerolab.checkin.util.HealthSteps
import com.zerolab.checkin.util.NetGeo
import com.zerolab.checkin.util.StepStore
import kotlin.concurrent.thread

/**
 * 增强服务页：联网增强（位置地名）+ 后台计步（可选前台服务）+ 健康数据（Health Connect 今日步数）。
 */
class NetGeoActivity : AppCompatActivity() {

    private var pendingBgEnable = false
    private lateinit var swStepBg: SwitchCompat
    private lateinit var tvStepBgStatus: TextView

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 通知权限无论是否允许都继续；再检查「身体活动」权限
        requestActivityPermissionOrEnable()
    }

    private val activityPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingBgEnable) {
            enableBackgroundSteps()
        } else {
            pendingBgEnable = false
            swStepBg.isChecked = false
            StepStore.setEnabled(this, false)
            Toast.makeText(this, "需要「身体活动」权限才能后台计步", Toast.LENGTH_LONG).show()
            refreshStepBgStatus()
        }
    }

    /** Health Connect 授权（系统不支持时为 null） */
    private val healthPerm = HealthSteps.permissionContract()?.let { contract ->
        registerForActivityResult(contract) { refreshHealth() }
    }

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshStepBgStatus()
            statusHandler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_geo)
        // v1.3.14：全局主题换肤（根背景）
        ThemeUi.apply(this, findViewById(android.R.id.content))
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "增强服务"

        val prefs = getSharedPreferences(NetGeo.PREFS, MODE_PRIVATE)
        val swNet = findViewById<SwitchCompat>(R.id.sw_net)
        val swGeo = findViewById<SwitchCompat>(R.id.sw_geo)
        val panelGeo = findViewById<View>(R.id.panel_geo)
        val panelKey = findViewById<View>(R.id.panel_key)
        val etKey = findViewById<EditText>(R.id.et_api_key)

        fun refresh() {
            val net = prefs.getBoolean(NetGeo.KEY_NET, false)
            val geo = prefs.getBoolean(NetGeo.KEY_GEO, false)
            swNet.isChecked = net
            swGeo.isChecked = geo
            panelGeo.visibility = if (net) View.VISIBLE else View.GONE
            panelKey.visibility = if (net && geo) View.VISIBLE else View.GONE
            etKey.setText(prefs.getString(NetGeo.KEY_API_KEY, "") ?: "")
        }
        refresh()

        swNet.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(NetGeo.KEY_NET, on).apply()
            if (!on) prefs.edit().putBoolean(NetGeo.KEY_GEO, false).apply()
            refresh()
        }
        swGeo.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(NetGeo.KEY_GEO, on).apply()
            refresh()
        }
        etKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(NetGeo.KEY_API_KEY, s?.toString()?.trim() ?: "").apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etKey.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refresh() }

        // ---------- 后台计步 ----------
        swStepBg = findViewById(R.id.sw_step_bg)
        tvStepBgStatus = findViewById(R.id.tv_step_bg_status)
        swStepBg.isChecked = StepStore.isEnabled(this)
        swStepBg.setOnCheckedChangeListener { _, on ->
            if (on) requestEnableBackgroundSteps() else disableBackgroundSteps()
        }
        refreshStepBgStatus()

        // ---------- 健康数据 ----------
        findViewById<Button>(R.id.btn_health_auth).setOnClickListener {
            val perm = HealthSteps.readPermission()
            val launcher = healthPerm
            if (perm != null && launcher != null) {
                launcher.launch(setOf(perm))
            } else {
                Toast.makeText(this, "当前系统不支持 Health Connect", Toast.LENGTH_SHORT).show()
            }
        }
        refreshHealth()
    }

    override fun onResume() {
        super.onResume()
        refreshStepBgStatus()
        statusHandler.removeCallbacks(statusTick)
        statusHandler.postDelayed(statusTick, 3000L)
        refreshHealth()
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusTick)
    }

    // ---------- 后台计步 ----------
    private fun requestEnableBackgroundSteps() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestActivityPermissionOrEnable()
    }

    private fun requestActivityPermissionOrEnable() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBgEnable = true
            activityPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
        enableBackgroundSteps()
    }

    private fun enableBackgroundSteps() {
        pendingBgEnable = false
        StepStore.setEnabled(this, true)
        StepStore.clearToday(this)
        StepCounterService.start(this)
        if (!swStepBg.isChecked) swStepBg.isChecked = true
        refreshStepBgStatus()
        Toast.makeText(this, "后台计步已开启（常驻低优先级通知）", Toast.LENGTH_SHORT).show()
    }

    private fun disableBackgroundSteps() {
        pendingBgEnable = false
        StepStore.setEnabled(this, false)
        StepCounterService.stop(this)
        StepStore.clearToday(this)
        refreshStepBgStatus()
    }

    private fun refreshStepBgStatus() {
        if (!::tvStepBgStatus.isInitialized) return
        val enabled = StepStore.isEnabled(this)
        tvStepBgStatus.text = when {
            !enabled -> "已关闭。开启后会在后台记录「0 点起」的今日步数。"
            StepStore.isRunning(this) -> "运行中 · 今日 ${StepStore.todayStepsOrNull(this) ?: 0} 步"
            else -> "已开启，但服务暂时没有运行（可能被系统限制）；打开 App 或允许自启动后会恢复。"
        }
    }

    // ---------- 健康数据 ----------
    private fun refreshHealth() {
        val btn = findViewById<Button>(R.id.btn_health_auth)
        val tv = findViewById<TextView>(R.id.tv_health_status)
        if (!HealthSteps.supportedByOs) {
            tv.text = "当前系统版本不支持 Health Connect（需 Android 8.0 及以上）"
            btn.visibility = View.GONE
            return
        }
        tv.text = "正在检查健康数据…"
        btn.visibility = View.GONE
        thread {
            val available = HealthSteps.isAvailable(this)
            val granted = available && HealthSteps.hasPermission(this)
            val steps = if (granted) HealthSteps.readTodaySteps(this) else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    !available -> {
                        tv.text = "未安装 Health Connect（或系统不支持），无法读取系统步数。可改用后台计步。"
                        btn.visibility = View.GONE
                    }
                    granted -> {
                        tv.text = "已授权" + if (steps != null) " · 今日 $steps 步（0 点起）" else ""
                        btn.text = "重新授权"
                        btn.visibility = View.VISIBLE
                    }
                    else -> {
                        tv.text = "Health Connect 可用，尚未授权读取步数"
                        btn.text = "授权健康数据"
                        btn.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
}
