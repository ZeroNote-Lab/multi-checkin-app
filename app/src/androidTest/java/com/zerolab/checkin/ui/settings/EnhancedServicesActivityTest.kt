package com.zerolab.checkin.ui.settings

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 增强服务页回归：包含联网增强、后台计步开关、健康数据入口，且 Health Connect 不可用时不能崩 */
@RunWith(AndroidJUnit4::class)
class EnhancedServicesActivityTest {

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString()?.contains(text) == true) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    @Test
    fun pageShowsAllSections() {
        ActivityScenario.launch(NetGeoActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val root = act.window.decorView
                assertNotNull("标题应为增强服务", findText(root, "增强服务"))
                assertNotNull("应有后台计步开关", findText(root, "后台计步"))
                assertNotNull("应有健康数据入口", findText(root, "健康数据"))
                assertNotNull("应保留联网增强", findText(root, "联网增强"))
            }
        }
    }
}
