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
import com.zerolab.checkin.theme.ThemeUi

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, b: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // v1.3.14：全局主题换肤（根背景）
        ThemeUi.apply(requireActivity(), view)
        try {
            val tvVer = view.findViewById<TextView>(R.id.tv_version)
            updateVerText(tvVer)
            // v1.3.17：版本号连点 5 次 = 超级管理员模式开关（退出 App 自动关闭）
            tvVer.setOnClickListener { onVersionTapped() }
        } catch (_: Exception) {}
        view.findViewById<View>(R.id.item_export).setOnClickListener {
            startActivity(Intent(requireContext(), ExportActivity::class.java))
        }
        view.findViewById<View>(R.id.item_import).setOnClickListener {
            startActivity(Intent(requireContext(), ImportActivity::class.java))
        }
        // v1.3.11：联网增强改为独立入口页，不再在设置页内展开
        view.findViewById<View>(R.id.item_net).setOnClickListener {
            startActivity(Intent(requireContext(), NetGeoActivity::class.java))
        }
        // v1.3.14：外观主题入口——6 套莫兰迪全局主题选择
        view.findViewById<View>(R.id.item_theme).setOnClickListener {
            startActivity(Intent(requireContext(), ThemeActivity::class.java))
        }
        view.findViewById<View>(R.id.item_about).setOnClickListener {
            val ver = try {
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }
            AlertDialog.Builder(requireContext()).setTitle("关于")
                .setMessage("打卡 APP v$ver\n纯本地运行，数据仅保存在本机；联网增强默认关闭，打开后仅用于位置地名翻译。")
                .setPositiveButton("知道了", null).show()
        }
    }

    // ---------- v1.3.17 超级管理员模式入口（版本号连点 5 次开关） ----------
    private var versionTapCount = 0
    private var lastVersionTap = 0L

    private fun updateVerText(tvVer: TextView) {
        val ver = try { requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
        tvVer.text = "打卡 APP v" + ver + if (AdminMode.isOn) "  ·  管理员模式" else ""
    }

    private fun onVersionTapped() {
        val now = System.currentTimeMillis()
        if (now - lastVersionTap > 800) versionTapCount = 0
        lastVersionTap = now
        versionTapCount++
        if (versionTapCount < 5) return
        versionTapCount = 0
        if (AdminMode.isOn) {
            AlertDialog.Builder(requireContext())
                .setTitle("关闭超级管理员模式")
                .setMessage("退出管理员模式后，将恢复锁定项的限制。\n\n确定关闭？")
                .setNegativeButton("取消", null)
                .setPositiveButton("关闭") { _, _ ->
                    AdminMode.off()
                    view?.findViewById<TextView>(R.id.tv_version)?.let { updateVerText(it) }
                    Toast.makeText(requireContext(), "管理员模式已关闭", Toast.LENGTH_SHORT).show()
                }.show()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("开启超级管理员模式")
                .setMessage("开启后可修改任意打卡项（含锁定项）与打卡记录。\n退出 App 后自动关闭，不影响原锁定状态。\n\n确定开启？")
                .setNegativeButton("取消", null)
                .setPositiveButton("开启") { _, _ ->
                    AdminMode.on()
                    view?.findViewById<TextView>(R.id.tv_version)?.let { updateVerText(it) }
                    Toast.makeText(requireContext(), "超级管理员模式已开启", Toast.LENGTH_SHORT).show()
                }.show()
        }
    }
}
