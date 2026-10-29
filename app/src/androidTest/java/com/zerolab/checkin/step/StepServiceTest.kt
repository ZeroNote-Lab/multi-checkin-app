package com.zerolab.checkin.step

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerolab.checkin.util.StepStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 后台计步服务：权限就绪时能启动前台服务并写入心跳（模拟器没有计步传感器也会正常挂着提示） */
@RunWith(AndroidJUnit4::class)
class StepServiceTest {

    @Test
    fun foregroundServiceStartsAndHeartbeats() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val pkg = ctx.packageName
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            ui.grantRuntimePermission(pkg, Manifest.permission.ACTIVITY_RECOGNITION)
        } catch (_: Exception) {}
        try {
            ui.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {}
        StepStore.setEnabled(ctx, true)
        StepCounterService.start(ctx)
        var running = false
        for (i in 0 until 50) {
            if (StepStore.isRunning(ctx)) { running = true; break }
            Thread.sleep(100)
        }
        assertTrue("后台计步服务应启动并上报心跳", running)
        StepCounterService.stop(ctx)
        StepStore.setEnabled(ctx, false)
        StepStore.clearToday(ctx)
    }
}
