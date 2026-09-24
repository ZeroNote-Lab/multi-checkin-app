package com.zerolab.checkin.util

import com.zerolab.checkin.data.repo.CheckinRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JsonExporter {

    /** 按选中打卡项构建导出 JSON（ids 为空 = 全部项）。
     *  v1.4：素材路径统一改写为 ZIP 内相对路径（photos/{itemId}/{文件名} / voices/{itemId}/{文件名}），
     *  exportVersion 升 1.1；仅记录模式同样输出相对路径，导入侧对不存在的素材文件跳过即可。 */
    fun build(repo: CheckinRepository, ids: Collection<Long>? = null): JSONObject {
        val root = JSONObject()
        root.put("exportVersion", "1.2")
        root.put("exportTime", System.currentTimeMillis())
        root.put("appName", "打卡APP")
        root.put("appVersion", "6.0.0")
        val items = if (ids == null) repo.getItems()
            else repo.getItems().filter { it.id in ids }
        val quickId = repo.getQuickId()
        root.put("quickCheckinItemId", if (quickId != null && quickId in items.map { it.id }) quickId else JSONObject.NULL)

        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject()
                .put("id", it.id).put("name", it.name).put("type", it.type)
                .put("configJson", JSONObject(it.configJson))
                .put("icon", it.icon).put("theme", it.theme)
                .put("groupTag", it.groupTag ?: JSONObject.NULL)
                .put("sortOrder", it.sortOrder)
                .put("isPinned", it.isPinned == 1)
                .put("pinnedAt", it.pinnedAt ?: JSONObject.NULL)
                .put("isActive", it.isActive == 1)
                .put("editPolicy", it.editPolicy)
                .put("editInterval", it.editInterval ?: JSONObject.NULL)
                .put("createdAt", it.createdAt)
                .put("updatedAt", it.updatedAt))
        }
        root.put("checkinItems", arr)

        val recs = JSONArray()
        for (it in items) for (r in repo.allRecords(it.id)) {
            recs.put(JSONObject()
                .put("id", r.id).put("itemId", r.itemId).put("checkinDate", r.checkinDate)
                .put("checkinTime", r.checkinTime).put("status", r.status).put("isAuto", r.isAuto)
                .put("photoPath", r.photoPath?.let { relMediaPath(it, "photos", r.itemId) } ?: JSONObject.NULL)
                .put("textContent", r.textContent ?: JSONObject.NULL)
                .put("voicePath", r.voicePath?.let { relMediaPath(it, "voices", r.itemId) } ?: JSONObject.NULL)
                .put("latitude", r.latitude ?: JSONObject.NULL)
                .put("longitude", r.longitude ?: JSONObject.NULL)
                .put("extraJson", r.extraJson ?: JSONObject.NULL))
        }
        root.put("checkinRecords", recs)

        val cr = JSONArray()
        for (it in items) for (c in repo.creditsOf(it.id)) {
            cr.put(JSONObject().put("id", c.id).put("itemId", c.itemId).put("mode", c.mode)
                .put("earnedDate", c.earnedDate).put("expireDate", c.expireDate ?: JSONObject.NULL)
                .put("isUsed", c.isUsed == 1).put("usedDate", c.usedDate ?: JSONObject.NULL))
        }
        root.put("offsetCredits", cr)
        return root
    }

    /** 绝对路径 → ZIP 内相对路径；空路径返回 null */
    private fun relMediaPath(abs: String?, kind: String, itemId: Long): String? {
        if (abs.isNullOrBlank()) return null
        val name = abs.substringAfterLast('/')
        if (name.isBlank()) return null
        return "$kind/$itemId/$name"
    }

    fun fileName(): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "checkin_export_${f.format(Date())}.json"
    }

    fun writeToCache(repo: CheckinRepository, dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, fileName())
        f.writeText(build(repo).toString(2), Charsets.UTF_8)
        return f
    }

    fun writeToCache(repo: CheckinRepository, dir: File, ids: Collection<Long>): File {
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, fileName())
        f.writeText(build(repo, ids).toString(2), Charsets.UTF_8)
        return f
    }
}
