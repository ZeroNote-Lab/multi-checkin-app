package com.zerolab.checkin.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.zerolab.checkin.R

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try {
            val tvVer = view.findViewById<TextView>(R.id.tv_version)
            tvVer.text = "打卡 APP v" + requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            // v1.3.6：隐藏迁移工具入口——版本号连点 5 次触发（一次性解锁旧版 LOCKED 项，用完即退役）
            tvVer.setOnClickListener { onVersionTapped() }
        } catch (_: Exception) {}
        view.findViewById<View>(R.id.item_export).setOnClickListener {
            startActivity(Intent(requireContext(), ExportActivity::class.java))
        }
        // v1.3.0 联网增强三级开关（联网总开关 → 定位增强 → API Key）
        bindNetGeo(view)
        val soon = View.OnClickListener { v ->
            val name = when (v.id) { R.id.item_import -> "数据导入"; R.id.item_theme -> "主题管理"; else -> "关于" }
            if (v.id == R.id.item_about) {
                val ver = try {
                    requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }
                AlertDialog.Builder(requireContext()).setTitle("关于")
                    .setMessage("打卡 APP v$ver\n纯本地运行，数据仅保存在本机；联网增强默认关闭，打开后仅用于位置地名翻译。")
                    .setPositiveButton("知道了", null).show()
            } else Toast.makeText(requireContext(), "$name 即将推出", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.item_import).setOnClickListener(soon)
        view.findViewById<View>(R.id.item_theme).setOnClickListener(soon)
        view.findViewById<View>(R.id.item_about).setOnClickListener(soon)
    }

    /** v1.3.0：联网增强 → 定位增强 → API Key 三级联动（NetGeo prefs） */
    private fun bindNetGeo(view: View) {
        val prefs = requireContext().getSharedPreferences(com.zerolab.checkin.util.NetGeo.PREFS, android.content.Context.MODE_PRIVATE)
        val swNet = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_net)
        val swGeo = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_geo)
        val panelGeo = view.findViewById<View>(R.id.panel_geo)
        val panelKey = view.findViewById<View>(R.id.panel_key)
        val etKey = view.findViewById<android.widget.EditText>(R.id.et_api_key)

        fun refresh() {
            val net = prefs.getBoolean(com.zerolab.checkin.util.NetGeo.KEY_NET, false)
            val geo = prefs.getBoolean(com.zerolab.checkin.util.NetGeo.KEY_GEO, false)
            swNet.isChecked = net
            swGeo.isChecked = geo
            panelGeo.visibility = if (net) View.VISIBLE else View.GONE
            panelKey.visibility = if (net && geo) View.VISIBLE else View.GONE
            etKey.setText(prefs.getString(com.zerolab.checkin.util.NetGeo.KEY_API_KEY, "") ?: "")
        }
        refresh()
        swNet.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(com.zerolab.checkin.util.NetGeo.KEY_NET, on).apply()
            if (!on) prefs.edit().putBoolean(com.zerolab.checkin.util.NetGeo.KEY_GEO, false).apply()
            refresh()
        }
        swGeo.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(com.zerolab.checkin.util.NetGeo.KEY_GEO, on).apply()
            refresh()
        }
        // 输入后立即保存（失去焦点 + 文本变化双保险）
        etKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(com.zerolab.checkin.util.NetGeo.KEY_API_KEY, s?.toString()?.trim() ?: "").apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etKey.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refresh() }
    }

    // ---------- v1.3.6 一次性迁移工具（隐藏入口：版本号连点 5 次） ----------
    private var versionTapCount = 0
    private var lastVersionTap = 0L

    private fun onVersionTapped() {
        val now = System.currentTimeMillis()
        if (now - lastVersionTap > 800) versionTapCount = 0
        lastVersionTap = now
        versionTapCount++
        if (versionTapCount >= 5) {
            versionTapCount = 0
            offerLockMigration()
        }
    }

    /** 把所有 LOCKED 旧打卡项一次性迁移为可修改（FLEX）；只执行一次，之后入口提示已迁移 */
    private fun offerLockMigration() {
        val prefs = requireContext().getSharedPreferences("checkin_migration", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("lock_migrated", false)) {
            Toast.makeText(requireContext(), "LOCKED 迁移已完成，无需重复执行", Toast.LENGTH_SHORT).show()
            return
        }
        val repo = (requireActivity().application as com.zerolab.checkin.CheckinApp).repository
        val lockedCount = repo.getItems().count { it.editPolicy == "LOCKED" }
        if (lockedCount == 0) {
            prefs.edit().putBoolean("lock_migrated", true).apply()
            Toast.makeText(requireContext(), "没有需要迁移的 LOCKED 打卡项", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("数据迁移（开发者工具）")
            .setMessage("检测到 $lockedCount 个「不可修改」打卡项（旧版本创建）。\n\n将一次性改为「可修改」，之后可正常编辑规则；本次迁移后新建的打卡项仍默认锁定。\n\n确定迁移？")
            .setNegativeButton("取消", null)
            .setPositiveButton("迁移") { _, _ ->
                val n = repo.migrateLockedToFlex()
                prefs.edit().putBoolean("lock_migrated", true).apply()
                Toast.makeText(requireContext(), "已迁移 $n 个打卡项为可修改 ✓", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
