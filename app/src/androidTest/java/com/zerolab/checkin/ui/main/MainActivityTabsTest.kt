package com.zerolab.checkin.ui.main

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.zerolab.checkin.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 冷启动只创建快捷页；切到列表/设置时按需创建，不应崩溃 */
@RunWith(AndroidJUnit4::class)
class MainActivityTabsTest {

    @Test
    fun lazyTabsAreCreatedOnDemand() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { act ->
                val fm = act.supportFragmentManager
                val nav = act.findViewById<BottomNavigationView>(R.id.bottom_nav)
                // 初始只创建快捷页
                assertNotNull(fm.findFragmentByTag(R.id.nav_quick.toString()))
                // 切到列表页再切到设置页，按需创建
                nav.selectedItemId = R.id.nav_item_list
                act.supportFragmentManager.executePendingTransactions()
                assertNotNull(fm.findFragmentByTag(R.id.nav_item_list.toString()))
                nav.selectedItemId = R.id.nav_settings
                act.supportFragmentManager.executePendingTransactions()
                assertNotNull(fm.findFragmentByTag(R.id.nav_settings.toString()))
                nav.selectedItemId = R.id.nav_quick
                act.supportFragmentManager.executePendingTransactions()
                assertNotNull(fm.findFragmentByTag(R.id.nav_quick.toString()))
            }
        }
    }
}
