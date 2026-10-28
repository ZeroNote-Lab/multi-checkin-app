package com.zerolab.checkin.ui.quick

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** v1.4.2：打卡按钮改为底部悬浮（不随内容滚动，进入页面即可点） */
@RunWith(AndroidJUnit4::class)
class FloatingCheckinButtonTest {

    @Test
    fun checkinButtonFloatsAtBottomAndIsVisible() {
        val app = ApplicationProvider.getApplicationContext<CheckinApp>()
        val repo = app.repository
        val id = repo.insertItem(
            CheckinItem(name = "悬浮按钮测试", type = "NORMAL", configJson = ItemConfig().toJson())
        )
        repo.setQuick(id)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { act ->
                val btn = act.findViewById<View>(R.id.btn_checkin)
                assertNotNull("应存在打卡按钮", btn)
                assertTrue("打卡按钮应可见", btn.isShown)

                val lp = btn.layoutParams
                assertTrue("打卡按钮应直接挂在根 FrameLayout 上（悬浮）", lp is FrameLayout.LayoutParams)
                assertEquals(
                    "打卡按钮应贴底部",
                    Gravity.BOTTOM,
                    (lp as FrameLayout.LayoutParams).gravity and Gravity.BOTTOM
                )

                val loc = IntArray(2)
                btn.getLocationInWindow(loc)
                val screenH = act.window.decorView.height
                assertTrue("按钮应在屏幕下半部分，实际 y=${loc[1]} / $screenH", loc[1] > screenH / 2)
            }
        }
    }
}
