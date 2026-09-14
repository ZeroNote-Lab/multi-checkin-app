package com.zerolab.checkin.ui.detail

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.ui.quick.NfcHub
import com.zerolab.checkin.ui.quick.QuickCheckinFragment

/**
 * 打卡操作页：从打卡选择页点击卡片进入。
 * 展示该打卡项的日历与打卡操作（与快捷打卡页同一套界面），但【不会】修改快捷打卡配置。
 * 右上角 ⋮ 进入编辑/详情页。
 */
class ItemCheckinActivity : AppCompatActivity() {

    private var nfcPending: PendingIntent? = null
    private var checkinFragment: QuickCheckinFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_item_checkin)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val repo = (application as CheckinApp).repository
        val item = repo.getItem(id)
        if (item == null) { finish(); return }

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = item.name
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener {
            startActivity(Intent(this, ItemDetailActivity::class.java).putExtra(ItemDetailActivity.EXTRA_ID, id))
        }

        if (savedInstanceState == null) {
            checkinFragment = QuickCheckinFragment.newInstance(id)
            supportFragmentManager.beginTransaction()
                .replace(R.id.frag_container, checkinFragment!!)
                .commit()
        } else {
            // 进程重建：从 FragmentManager 恢复打卡页实例
            checkinFragment = supportFragmentManager.findFragmentById(R.id.frag_container) as? QuickCheckinFragment
        }
    }

    // ---------- NFC 前台分发：列表进入的打卡页也要能读到标签（v6.1.0 修复） ----------
    override fun onResume() {
        super.onResume()
        enableNfcForeground()
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
            // 直连本页打卡 fragment（不依赖注册时序）+ NfcHub 兜底；awaitingNfc 防重复
            checkinFragment?.notifyNfc(id)
            NfcHub.dispatch(id)
        }
    }

    companion object {
        const val EXTRA_ID = "item_id"
    }
}
