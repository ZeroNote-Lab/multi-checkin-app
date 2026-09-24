package com.zerolab.checkin.util

import java.io.File
import java.util.zip.ZipFile

/** ZIP 解析：解压到指定目录，读取 data.json 文本与素材根目录（含防 zip-slip）。 */
object ZipImporter {

    class Extracted(val dataJson: String, val mediaRoot: File)

    fun extract(zipFile: File, outDir: File): Extracted {
        outDir.mkdirs()
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val clean = e.name.replace('\\', '/')
                // 防 zip-slip：拒绝含 .. 的条目名
                if (clean.contains("..")) continue
                val out = File(outDir, clean)
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        val jsonFile = File(outDir, "data.json")
        if (!jsonFile.exists()) throw IllegalArgumentException("ZIP 中缺少 data.json，请确认这是本应用导出的文件")
        return Extracted(jsonFile.readText(Charsets.UTF_8), outDir)
    }
}
