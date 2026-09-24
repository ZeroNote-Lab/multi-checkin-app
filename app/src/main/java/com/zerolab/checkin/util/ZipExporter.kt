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
        var missing = 0
        for (it in items) {
            for (r in repo.allRecords(it.id)) {
                r.photoPath?.let { p ->
                    val f = File(p)
                    if (f.exists() && f.isFile) entries.add("photos/${it.id}/${f.name}" to f) else missing++
                }
                r.voicePath?.let { v ->
                    val f = File(v)
                    if (f.exists() && f.isFile) entries.add("voices/${it.id}/${f.name}" to f) else missing++
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
