package com.zerolab.checkin.ui.create

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 步数打卡回归：创建页曾经把步数开关禁用（提示「开发中」），现在应可开启并出现目标步数配置。
 * 真机传感器读取依赖「身体活动」权限，无法在模拟器验证，这里只保证入口与配置可用。
 */
@RunWith(AndroidJUnit4::class)
class StepMethodEnabledTest {

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString()?.contains(text) == true) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun findSwitch(root: View): SwitchCompat? {
        if (root is SwitchCompat) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findSwitch(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun stepsCanBeEnabledAndShowsTargetPanel() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), CreateItemActivity::class.java)
            .putExtra(CreateItemActivity.EXTRA_MODE, CreateItemActivity.MODE_NORMAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<CreateItemActivity>(intent).use { scenario ->
            scenario.onActivity { act ->
                val root = act.window.decorView
                val label = findText(root, "步数打卡")
                assertNotNull("应存在步数打卡行", label)
                val row = label!!.parent as ViewGroup
                val sw = findSwitch(row)
                assertNotNull("步数开关应存在", sw)
                assertTrue("步数开关应可用（不再提示开发中）", sw!!.isEnabled)
                sw.performClick()
                assertNotNull("开启后应出现目标步数配置", findText(root, "目标步数"))
            }
        }
    }
}
