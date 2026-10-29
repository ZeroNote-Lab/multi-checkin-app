package com.zerolab.checkin.ui.create

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 随心记创建页：不再展示四个方式勾选，只显示「已内置」提示 */
@RunWith(AndroidJUnit4::class)
class JournalCreateHintTest {

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString()?.contains(text) == true) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    @Test
    fun journalPageShowsBuiltinHintAndHidesMethodSwitches() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), CreateItemActivity::class.java)
            .putExtra(CreateItemActivity.EXTRA_MODE, CreateItemActivity.MODE_JOURNAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<CreateItemActivity>(intent).use { scenario ->
            scenario.onActivity { act ->
                val root = act.window.decorView
                val hint = findText(root, "已内置文字 / 图片 / 语音 / 位置")
                assertNotNull("应显示四种记录块已内置的提示", hint)
                assertTrue("提示应可见", hint!!.isShown)
                for (label in listOf("拍照打卡", "文字打卡", "语音打卡", "位置打卡")) {
                    val tv = findText(root, label)
                    assertTrue("随心记下不应显示「$label」勾选", tv == null || !tv.isShown)
                }
            }
        }
    }
}
