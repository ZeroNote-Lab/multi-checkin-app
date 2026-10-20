package com.zerolab.checkin.util

import com.zerolab.checkin.data.repo.CheckinRepository
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 素材打包导出：ZIP = data.json（相对路径）+ photos/ + voices/。素材缺失跳过并计数，不中断导出。 */
object ZipExporter {

    class Result(val file: File, val mediaCount: Int, val missingMedia: Int)

    fun fileName(): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "checkin_export_${f.format(Date())}.zip"
    }

    fun buildZip(repo: CheckinRepository, ids: Collection<Long>, dir: File): Result {
        if (!dir.exists()) dir.mkdirs()
        val zipFile = File(dir, fileName())
        val jsonText = JsonExporter.build(repo, ids).toString(2)

        // 收集素材（数据库存绝对路径，导出 JSON 时才改写为相对路径）
        val items = repo.getItems().filter { it.id in ids }
        val entries = ArrayList<Pair<String, File>>()   // zip 相对路径 -> 源文件
        val seen = HashSet<String>()
        var missing = 0
        fun collect(abs: String?, kind: String, itemId: Long) {
            if (abs.isNullOrBlank()) return
            val f = File(abs)
            if (!f.exists() || !f.isFile) { missing++; return }
            val rel = "$kind/$itemId/${f.name}"
            if (seen.add(rel)) entries.add(rel to f)
        }
        for (it in items) {
            for (r in repo.allRecords(it.id)) {
                collect(r.photoPath, "photos", it.id)
                collect(r.voicePath, "voices", it.id)
                // v1.4.0：多图 photoPaths 与随心记 blocks 中的图片/语音
                RecordMedia.extract(r.extraJson).forEach { (kind, p) ->
                    // 仅收集绝对路径；已经是相对路径（纯JSON导入产生的记录）无源文件
                    if (p.startsWith("/") || p.contains(":/")) collect(p, kind, it.id)
                }
            }
        }

        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("data.json"))
            zos.write(jsonText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            for ((path, src) in entries) {
                zos.putNextEntry(ZipEntry(path))
                FileInputStream(src).use { input -> input.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return Result(zipFile, entries.size, missing)
    }
}
