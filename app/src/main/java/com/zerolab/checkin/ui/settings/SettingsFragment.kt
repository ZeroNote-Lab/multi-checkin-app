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
        try { val tvVer = view.findViewById<TextView>(R.id.tv_version); tvVer.text = "打卡 APP v" + requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName } catch (_: Exception) {}
        view.findViewById<View>(R.id.item_export).setOnClickListener {
            startActivity(Intent(requireContext(), ExportActivity::class.java))
        }
        val soon = View.OnClickListener { v ->
            val name = when (v.id) { R.id.item_import -> "数据导入"; R.id.item_theme -> "主题管理"; else -> "关于" }
            if (v.id == R.id.item_about) {
                val ver = try {
                    requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }
                AlertDialog.Builder(requireContext()).setTitle("关于")
                    .setMessage("打卡 APP v$ver\n纯本地运行，不申请网络权限，数据仅保存在本机。")
                    .setPositiveButton("知道了", null).show()
            } else Toast.makeText(requireContext(), "$name 即将推出", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.item_import).setOnClickListener(soon)
        view.findViewById<View>(R.id.item_theme).setOnClickListener(soon)
        view.findViewById<View>(R.id.item_about).setOnClickListener(soon)
    }
}
