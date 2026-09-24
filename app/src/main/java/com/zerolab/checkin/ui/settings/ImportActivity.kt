package com.zerolab.checkin.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zerolab.checkin.CheckinApp
import com.zerolab.checkin.R
import com.zerolab.checkin.util.JsonExporter
import com.zerolab.checkin.util.JsonImporter
import com.zerolab.checkin.util.ZipImporter
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class ImportActivity : AppCompatActivity() {

    private val pickCode = 1001
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "数据导入"
        findViewById<Button>(R.id.btn_import).setOnClickListener { pickFile() }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/json", "application/zip", "application/octet-stream"))
        }
        startActivityForResult(i, pickCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            importFile(uri)
        }
    }

    private fun importFile(uri: Uri) {
        if (importing) return
        importing = true
        val tvResult = findViewById<TextView>(R.id.tv_result)
        val btn = findViewById<Button>(R.id.btn_import)
        btn.isEnabled = false
        tvResult.text = "读取文件中…"
        val cache = File(cacheDir, "import/${System.currentTimeMillis()}")
        thread {
            try {
                cache.mkdirs()
                val isZip = displayName(uri)?.endsWith(".zip", true) ?: false
                val input = if (isZip) File(cache, "input.zip") else File(cache, "input.json")
                contentResolver.openInputStream(uri)?.use { input2 ->
                    input.outputStream().use { out -> input2.copyTo(out) }
                } ?: throw IllegalArgumentException("无法读取所选文件")

                val dataJson: String
                val mediaRoot: File?
                if (isZip) {
                    val ex = ZipImporter.extract(input, File(cache, "unzip"))
                    dataJson = ex.dataJson; mediaRoot = ex.mediaRoot
                } else {
                    dataJson = input.readText(Charsets.UTF_8); mediaRoot = null
                }
                val pv = preview(dataJson)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("确认导入")
                        .setMessage("文件包含 ${pv.items} 个打卡项、${pv.records} 条记录、${pv.credits} 个抵消机会。\n\n将按创建时间匹配合并（名称作后备匹配），未匹配的打卡项会新建；导入前自动备份当前数据。\n\n继续？")
                        .setNegativeButton("取消") { _, _ -> importing = false; btn.isEnabled = true; tvResult.text = "" }
                        .setPositiveButton("导入") { _, _ -> doImport(dataJson, mediaRoot, cache) }
                        .setOnCancelListener { importing = false; btn.isEnabled = true; tvResult.text = "" }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvResult.text = "解析失败：${e.message}"
                    btn.isEnabled = true
                    importing = false
                }
                cache.deleteRecursively()
            }
        }
    }

    /** 后台执行导入（含导入前自动备份），完成后展示结果 */
    private fun doImport(dataJson: String, mediaRoot: File?, cache: File) {
        val tvResult = findViewById<TextView>(R.id.tv_result)
        val btn = findViewById<Button>(R.id.btn_import)
        thread {
            try {
                val repo = (application as CheckinApp).repository
                val backupDir = File(cacheDir, "backup"); backupDir.mkdirs()
                val backup = JsonExporter.writeToCache(repo, backupDir)
                val result = JsonImporter.importFromJson(repo, dataJson, mediaRoot, filesDir)
                runOnUiThread {
                    tvResult.text = result.summary() + "\n导入前已自动备份：cache/backup/${backup.name}"
                    btn.isEnabled = true
                    importing = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvResult.text = "导入失败：${e.message}"
                    btn.isEnabled = true
                    importing = false
                }
            } finally {
                cache.deleteRecursively()
            }
        }
    }

    private class Preview(val items: Int, val records: Int, val credits: Int)

    private fun preview(text: String): Preview {
        val root = JSONObject(text)
        return Preview(
            root.optJSONArray("checkinItems")?.length() ?: 0,
            root.optJSONArray("checkinRecords")?.length() ?: 0,
            root.optJSONArray("offsetCredits")?.length() ?: 0
        )
    }

    private fun displayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}
