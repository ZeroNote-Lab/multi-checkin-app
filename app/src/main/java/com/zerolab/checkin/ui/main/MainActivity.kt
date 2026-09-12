package com.zerolab.checkin.ui.main

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.ui.list.ItemListFragment
import com.zerolab.checkin.ui.quick.QuickCheckinFragment
import com.zerolab.checkin.ui.settings.SettingsFragment
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var nav: BottomNavigationView
    private val frags = HashMap<Int, Fragment>()
    private var currentId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nav = findViewById(R.id.bottom_nav)

        if (savedInstanceState == null) {
            frags[R.id.nav_quick] = QuickCheckinFragment()
            frags[R.id.nav_item_list] = ItemListFragment()
            frags[R.id.nav_settings] = SettingsFragment()
            val tx = supportFragmentManager.beginTransaction()
            frags.forEach { (id, f) -> tx.add(R.id.fragment_container, f, id.toString()).hide(f) }
            tx.commitNow()
            switchTo(R.id.nav_quick)
        }

        nav.setOnItemSelectedListener { item ->
            switchTo(item.itemId); true
        }
    }

    private fun switchTo(id: Int) {
        if (id == currentId) return
        val tx = supportFragmentManager.beginTransaction()
        frags.forEach { entry -> if (entry.key == id) tx.show(entry.value) else tx.hide(entry.value) }
        tx.commitNowAllowingStateLoss()
        currentId = id
        if (nav.selectedItemId != id) nav.menu.findItem(id).isChecked = true
        // show/hide 不触发 onResume，显式刷新目标页
        when (id) {
            R.id.nav_quick -> (frags[id] as? QuickCheckinFragment)?.refresh()
            R.id.nav_item_list -> (frags[id] as? ItemListFragment)?.reload()
        }
    }

    fun gotoListTab() {
        switchTo(R.id.nav_item_list)
    }

    /** 管理页点卡片：设为当前快捷项并直达打卡页 */
    fun openCheckin(itemId: Long) {
        (application as CheckinApp).repository.setQuick(itemId)
        switchTo(R.id.nav_quick)
    }

    override fun onResume() {
        super.onResume()
        // 前台触发自动打卡（冷启动 / 回前台）
        thread {
            val repo = (application as CheckinApp).repository
            val done = CheckinEngine.tryAutoAll(repo)
            if (done.isNotEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "⚡ ${done.joinToString("、")} 已自动完成", Toast.LENGTH_SHORT).show()
                    // 通知当前快捷页刷新
                    (frags[R.id.nav_quick] as? QuickCheckinFragment)?.refresh()
                }
            }
        }
    }
}
