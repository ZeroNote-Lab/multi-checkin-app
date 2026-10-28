package com.zerolab.checkin.ui.flow

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerolab.checkin.ui.settings.ThemeActivity
import com.zerolab.checkin.util.NetGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 位置选择弹窗的 UI 回归：
 * 之前用 AlertDialog 的 message + items，部分机型上列表不显示，只剩按钮，导致位置打卡无法完成。
 */
@RunWith(AndroidJUnit4::class)
class LocationPickerDialogTest {

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findText(root.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out.add(v.text?.toString() ?: "")
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun nearbyPoiRowIsVisibleAndClickable() {
        var dlg: AlertDialog? = null
        var pickedName: String? = null
        ActivityScenario.launch(ThemeActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val detail = NetGeo.GeoDetail(
                    "广东省广州市海珠区阅江西路",
                    listOf(NetGeo.Poi("广州大剧院", "珠江西路1号", 80, 23.1065, 113.3255))
                )
                dlg = LocationPickerDialog.show(
                    act, "确认打卡位置", "当前位置：广州大剧院附近", detail,
                    23.1047, 113.3245, onRetry = null
                ) { name, _, _ -> pickedName = name }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { _ ->
                val root = dlg!!.window!!.decorView
                val texts = collectTexts(root)
                val current = findText(root, "使用当前定位")
                val poi = findText(root, "广州大剧院")
                assertNotNull("应显示当前定位行，实际：$texts", current)
                assertNotNull("应显示附近地点行，实际：$texts", poi)
                assertTrue("当前定位行应可见", current!!.isShown)
                assertTrue("附近地点行应可见", poi!!.isShown)
                // 点击地点行应回调并关闭弹窗
                (poi.parent as View).performClick()
                assertEquals("广州大剧院", pickedName)
                dlg!!.dismiss()
            }
        }
    }

    @Test
    fun emptyPoiStillShowsUsableCurrentLocationRow() {
        var dlg: AlertDialog? = null
        ActivityScenario.launch(ThemeActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val detail = NetGeo.GeoDetail("", emptyList())
                dlg = LocationPickerDialog.show(
                    act, "确认打卡位置", "无附近地点", detail,
                    23.1047, 113.3245, onRetry = null
                ) { _, _, _ -> }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { _ ->
                val root = dlg!!.window!!.decorView
                val texts = collectTexts(root)
                val current = findText(root, "使用当前定位")
                assertNotNull("应仍显示当前定位行，实际：$texts", current)
                assertTrue("当前定位行应可见", current!!.isShown)
                assertTrue(
                    "应提示可直接使用当前定位，实际：$texts",
                    texts.any { it.contains("未获取到附近地点") }
                )
                dlg!!.dismiss()
            }
        }
    }
}
