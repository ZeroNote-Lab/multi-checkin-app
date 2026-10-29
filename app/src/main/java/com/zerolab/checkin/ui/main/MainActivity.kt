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
import com.zerolab.checkin.ui.settings.AdminMode
import com.zerolab.checkin.ui.settings.SettingsFragment
import com.zerolab.checkin.theme.GlobalThemeManager
import com.zerolab.checkin.theme.ThemeUi
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var nav: BottomNavigationView
    private val frags = HashMap<Int, Fragment>()
    private var currentId = -1

    private var nfcPending: PendingIntent? = null

    private val autoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 冷启动先让打卡页出首帧，再把自动打卡/负打卡结算放到后台，避免与页面快照抢数据库 */
    private val autoTask = Runnable {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        nav = findViewById(R.id.bottom_nav)
        // v1.3.14：全局主题换肤（背景 / 状态栏 / 底部导航选中色）
        ThemeUi.applyWindow(this)
        try { rootFind() } catch (_: Exception) {}
        ThemeUi.tintBottomNav(nav, ThemeUi.current(this))

        if (savedInstanceState != null) {
            // 进程重建：恢复 FragmentManager 里已有的页面
            listOf(R.id.nav_quick, R.id.nav_item_list, R.id.nav_settings).forEach { id ->
                (supportFragmentManager.findFragmentByTag(id.toString()) as? Fragment)?.let { frags[id] = it }
            }
        }
        // v1.4.3：只创建当前 tab 的 Fragment，列表页/设置页按需创建。
        // 冷启动不再同时跑「快捷页快照 + 列表页所有打卡项连续天数 + 设置页」三份数据加载。
        val cur = if (savedInstanceState != null) {
            nav.selectedItemId.let { if (it == 0) R.id.nav_quick else it }
        } else R.id.nav_quick
        val target = ensureFragment(cur)
        val tx = supportFragmentManager.beginTransaction()
        tx.show(target)
        frags.forEach { (id, f) -> if (id != cur) tx.hide(f) }
        tx.commitNowAllowingStateLoss()
        currentId = cur
        if (nav.selectedItemId != cur) nav.menu.findItem(cur).isChecked = true

        nav.setOnItemSelectedListener { item ->
            switchTo(item.itemId); true
        }
    }

    /** 按需创建 tab 页：冷启动只创建快捷页，其它页首次切换过去时再创建 */
    private fun ensureFragment(id: Int): Fragment {
        frags[id]?.let { return it }
        val f: Fragment = when (id) {
            R.id.nav_item_list -> ItemListFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> QuickCheckinFragment()
        }
        frags[id] = f
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, f, id.toString())
            .hide(f)
            .commitNowAllowingStateLoss()
        return f
    }

    private fun switchTo(id: Int) {
        if (id == currentId) return
        val target = ensureFragment(id)
        val tx = supportFragmentManager.beginTransaction()
        tx.show(target)
        frags.forEach { entry -> if (entry.key != id) tx.hide(entry.value) }
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

    private fun rootFind() {
        findViewById<android.view.View>(android.R.id.content)?.setBackgroundColor(ThemeUi.current(this).bg)
    }

    /** v1.3.14：主题切换后（ThemeActivity 返回）重建主界面以全局换肤 */
    private var lastThemeId: String? = null
    override fun onResume() {
        super.onResume()
        val curTheme = GlobalThemeManager.get(this)
        if (lastThemeId != null && lastThemeId != curTheme) {
            lastThemeId = curTheme
            recreate()
            return
        }
        lastThemeId = curTheme
        enableNfcForeground()
        // 前台触发自动打卡（冷启动 / 回前台）：延后 600ms，先让快捷页首帧/缓存快照出来
        autoHandler.removeCallbacks(autoTask)
        autoHandler.postDelayed(autoTask, 600L)
    }

    override fun onPause() {
        super.onPause()
        autoHandler.removeCallbacks(autoTask)
        try { NfcAdapter.getDefaultAdapter(this)?.disableForegroundDispatch(this) } catch (_: Exception) {}
    }

    // v1.3.17：正常退出/划掉任务时关闭超级管理员模式（冷启动本就重置）
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) AdminMode.off()
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
