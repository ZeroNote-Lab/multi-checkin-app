package com.zerolab.checkin.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.util.JsonExporter
import com.zerolab.checkin.util.ZipExporter
import java.io.File
import kotlin.concurrent.thread

class ExportActivity : AppCompatActivity() {

    private val items = ArrayList<CheckinItem>()
    private val boxes = ArrayList<CheckBox>()
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "数据导出"

        val btnExport = findViewById<Button>(R.id.btn_export)
        btnExport.setOnClickListener { export() }
        findViewById<Button>(R.id.btn_select_all).setOnClickListener {
            boxes.forEach { it.isChecked = true }
        }
        findViewById<Button>(R.id.btn_select_none).setOnClickListener {
            boxes.forEach { it.isChecked = false }
        }

        loadItems()
    }

    /** 后台加载打卡项并渲染复选框列表 */
    private fun loadItems() {
        val tvResult = findViewById<TextView>(R.id.tv_result)
        tvResult.text = "加载打卡项中…"
        thread {
            try {
                val repo = (application as CheckinApp).repository
                val loaded = repo.getItems()
                runOnUiThread {
                    items.clear(); items.addAll(loaded)
                    boxes.clear()
                    val ll = findViewById<LinearLayout>(R.id.ll_items)
                    ll.removeAllViews()
                    for (it in items) {
                        val cb = CheckBox(this)
                        cb.text = "${it.icon} ${it.name}"
                        cb.isChecked = true
                        cb.setTextColor(resources.getColor(R.color.text_primary, null))
                        cb.textSize = 15f
                        cb.setPadding(8, 8, 8, 8)
                        ll.addView(cb)
                        boxes.add(cb)
                    }
                    tvResult.text = if (items.isEmpty()) "暂无打卡项" else "共 ${items.size} 个打卡项，默认全选"
                }
            } catch (e: Exception) {
                runOnUiThread { tvResult.text = "加载失败：${e.message}" }
            }
        }
    }

    private fun export() {
        if (exporting) return
        val selectedIds = boxes.filter { it.isChecked }.map { items[boxes.indexOf(it)].id }
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "请至少选择一个打卡项", Toast.LENGTH_SHORT).show()
            return
        }
        exporting = true
        val btn = findViewById<Button>(R.id.btn_export)
        btn.isEnabled = false
        val tvResult = findViewById<TextView>(R.id.tv_result)
        val withMedia = findViewById<RadioButton>(R.id.rb_zip).isChecked
        thread {
            try {
                val repo = (application as CheckinApp).repository
                val dir = File(cacheDir, "exports")
                val (file, extra) = if (withMedia) {
                    val r = ZipExporter.buildZip(repo, selectedIds, dir)
                    Triple(r.file, "素材 ${r.mediaCount} 个，缺失 ${r.missingMedia} 个", "application/zip")
                } else {
                    Triple(JsonExporter.writeToCache(repo, dir, selectedIds), "仅记录", "application/json")
                }
                val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
                runOnUiThread {
                    tvResult.text = "已生成：${file.name}\n大小 ${file.length()} 字节 · $extra"
                    share(uri, file.name, file.extension)
                    btn.isEnabled = true
                    exporting = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvResult.text = "导出失败：${e.message}"
                    btn.isEnabled = true
                    exporting = false
                }
            }
        }
    }

    private fun share(uri: Uri, name: String, ext: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = if (ext == "zip") "application/zip" else "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出打卡数据"))
        } catch (e: Exception) {
            Toast.makeText(this, "未找到可分享的应用：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
