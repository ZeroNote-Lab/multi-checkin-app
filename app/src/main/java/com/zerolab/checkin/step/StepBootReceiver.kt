package com.zerolab.checkin.step

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zerolab.checkin.util.StepStore

/**
 * 手机重启 / 应用更新后，如果用户开启过「后台计步」，尝试恢复前台服务。
 * Android 12+ 对后台启动前台服务有限制，失败时等用户下次打开 App 再启动。
 */
class StepBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!StepStore.isEnabled(context)) return
        try {
            StepCounterService.start(context)
        } catch (_: Exception) {
            // 系统限制启动，忽略；下次打开 App 会重试
        }
    }
}
