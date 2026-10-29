package com.zerolab.checkin.step

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerolab.checkin.R
import com.zerolab.checkin.ui.main.MainActivity
import com.zerolab.checkin.util.StepStore

/**
 * 可选「后台计步」前台服务（设置 → 增强服务里开关）。
 *
 * 目的：TYPE_STEP_COUNTER 只有开机累计值，要做到「今日 0 点起」必须在后台持续记录、
 * 跨天时把基准切到新一天。服务用低优先级静默通知，耗电主要来自计步传感器（硬件低功耗）。
 *
 * 用户关闭开关 / 系统杀掉进程后，下次打开 App 或手机重启会尝试恢复。
 */
class StepCounterService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "step_bg"
        const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, StepCounterService::class.java))
            } catch (_: Exception) {
                // Android 12+ 后台启动限制 / 权限缺失：交给下次前台打开时重试
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, StepCounterService::class.java)) } catch (_: Exception) {}
        }
    }

    private lateinit var sm: SensorManager
    private var counter: Sensor? = null
    private var detector: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastNotifStep = -1L
    private var lastNotifAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                StepStore.rollDayIfNeeded(this@StepCounterService)
                StepStore.heartbeat(this@StepCounterService)
                if (!registered) registerSensors()
                if (registered) updateNotification()
                else updateNotification(force = true, error = sensorError())
            } catch (_: Exception) {}
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        createChannel()
        startForegroundCompat(0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val ok = registerSensors()
            StepStore.heartbeat(this)
            updateNotification(force = true, error = if (ok) null else sensorError())
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 60_000L)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { if (registered) sm.unregisterListener(this) } catch (_: Exception) {}
        registered = false
        super.onDestroy()
    }

    private fun registerSensors(): Boolean {
        if (registered) return true
        var ok = false
        try {
            if (counter != null) {
                ok = sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, handler) || ok
            }
            if (detector != null) {
                ok = sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_NORMAL, handler) || ok
            }
        } catch (_: Exception) {
            ok = false
        }
        registered = ok
        return ok
    }

    private fun sensorError(): String =
        if (counter == null && detector == null) "设备没有计步传感器，无法后台计步"
        else "未获得「身体活动」权限，无法后台计步"

    override fun onSensorChanged(event: SensorEvent) {
        val total = try {
            when (event.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val v = event.values.firstOrNull()?.toLong() ?: return
                    StepStore.onCounterEvent(this, v)
                }
                Sensor.TYPE_STEP_DETECTOR -> {
                    // 有计步器时以计步器为准，检测器只做兜底
                    if (counter == null) StepStore.onDetectorStep(this) else StepStore.todayStepsOrNull(this) ?: 0L
                }
                else -> return
            }
        } catch (_: Exception) { return }
        updateNotification(total = total)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateNotification(total: Long = StepStore.todayStepsOrNull(this) ?: 0L, force: Boolean = false, error: String? = null) {
        val now = System.currentTimeMillis()
        // 常驻通知尽量安静：至少间隔 5 分钟或累计变化 100 步才刷新，避免频繁打扰
        if (!force && lastNotifStep > 0 && now - lastNotifAt < 5 * 60_000L &&
            kotlin.math.abs(total - lastNotifStep) < 100
        ) return
        lastNotifStep = total
        lastNotifAt = now
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIF_ID, buildNotification(total, error))
        } catch (_: Exception) {}
    }

    private fun buildNotification(total: Long, error: String? = null): Notification {
        val content = when {
            error != null -> error
            total > 0 -> "今日 $total 步"
            else -> "正在等待计步数据…"
        }
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, intent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_steps)
            .setContentTitle("打卡 · 后台计步")
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL_ID, "后台计步", NotificationManager.IMPORTANCE_LOW).apply {
            description = "可选：持续记录今日步数（低耗电，静默通知）"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(ch)
    }

    private fun startForegroundCompat(total: Long) {
        val notification = buildNotification(total)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                ServiceCompat.startForeground(this, NOTIF_ID, notification, 0)
            }
        } catch (_: Exception) {
            // 权限（FOREGROUND_SERVICE_HEALTH / ACTIVITY_RECOGNITION）不足时无法前台运行
            stopSelf()
        }
    }
}
