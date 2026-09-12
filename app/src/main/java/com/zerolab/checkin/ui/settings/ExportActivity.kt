package com.zerolab.checkin.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.util.JsonExporter
import java.io.File
import kotlin.concurrent.thread

class ExportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "数据导出"
        val tvResult = findViewById<TextView>(R.id.tv_result)

        findViewById<Button>(R.id.btn_export).setOnClickListener { btn ->
            btn.isEnabled = false
            thread {
                try {
                    val repo = (application as CheckinApp).repository
                    val dir = File(cacheDir, "exports")
                    val file = JsonExporter.writeToCache(repo, dir)
                    val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
                    runOnUiThread {
                        tvResult.text = "已生成：${file.name}\n大小 ${file.length()} 字节"
                        share(uri, file.name)
                        btn.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvResult.text = "导出失败：${e.message}"
                        btn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun share(uri: Uri, name: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
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
