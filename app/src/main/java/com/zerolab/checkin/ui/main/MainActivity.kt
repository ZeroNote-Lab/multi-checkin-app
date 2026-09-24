package com.zerolab.checkin.ui.main

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.engine.CheckinEngine
import com.zerolab.checkin.ui.list.ItemListFragment
import com.zerolab.checkin.ui.quick.NfcHub
import com.zerolab.checkin.ui.quick.QuickCheckinFragment
import com.zerolab.checkin.ui.settings.SettingsFragment
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var nav: BottomNavigationView
    private val frags = HashMap<Int, Fragment>()
    private var currentId = -1

    private var nfcPending: PendingIntent? = null

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
        } else {
            // 进程重建：从 FragmentManager 恢复已存在的实例，避免 frags 为空导致刷新失效（状态栏残留旧数据）
            frags[R.id.nav_quick] = supportFragmentManager.findFragmentByTag(R.id.nav_quick.toString()) ?: QuickCheckinFragment()
            frags[R.id.nav_item_list] = supportFragmentManager.findFragmentByTag(R.id.nav_item_list.toString()) ?: ItemListFragment()
            frags[R.id.nav_settings] = supportFragmentManager.findFragmentByTag(R.id.nav_settings.toString()) ?: SettingsFragment()
            // hide/show 状态不会随重建恢复，重新隐藏非当前页
            val cur = nav.selectedItemId.let { if (it != 0) it else R.id.nav_quick }
            val tx = supportFragmentManager.beginTransaction()
            frags.forEach { (id, f) -> if (id == cur) tx.show(f) else tx.hide(f) }
            tx.commitNowAllowingStateLoss()
            currentId = cur
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

    /** 列表页/其它页面改动快捷项后，主动通知快捷页刷新（防竞态残留） */
    fun refreshQuick() {
        (frags[R.id.nav_quick] as? QuickCheckinFragment)?.refresh()
    }

    fun gotoListTab() {
        switchTo(R.id.nav_item_list)
    }

    // ---------- NFC 前台分发（打卡时读取标签） ----------
    override fun onResume() {
        super.onResume()
        enableNfcForeground()
        // 前台触发自动打卡（冷启动 / 回前台）
        thread {
            val repo = (application as CheckinApp).repository
            val done = CheckinEngine.tryAutoAll(repo)
            // v1.3.7：负打卡机会结算（幂等补发破戒机会；负打卡无成功打卡动作，发放只能在页面结算）
            try { CheckinEngine.settleNegativeOffsets(repo) } catch (_: Exception) {}
            if (done.isNotEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "⚡ ${done.joinToString("、")} 已自动完成", Toast.LENGTH_SHORT).show()
                    // 通知当前快捷页刷新
                    (frags[R.id.nav_quick] as? QuickCheckinFragment)?.refresh()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try { NfcAdapter.getDefaultAdapter(this)?.disableForegroundDispatch(this) } catch (_: Exception) {}
    }

    private fun enableNfcForeground() {
        try {
            val nfc = NfcAdapter.getDefaultAdapter(this) ?: return
            if (!nfc.isEnabled) return
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            nfcPending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
            nfc.enableForegroundDispatch(this, nfcPending!!, null, null)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tag = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            val id = tag.id.joinToString("") { "%02X".format(it) }
            // 直连快捷页（恢复 v6.1.0 之前的可用行为，不依赖注册时序）+ NfcHub 兜底（覆盖列表进入的打卡页）
            // awaitingNfc 机制保证只由「正在等待标签」的页面响应，双路径不会重复打卡
            (frags[R.id.nav_quick] as? QuickCheckinFragment)?.notifyNfc(id)
            NfcHub.dispatch(id)
        }
    }
}
