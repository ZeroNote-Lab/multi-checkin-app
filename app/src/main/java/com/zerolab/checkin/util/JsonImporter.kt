package com.zerolab.checkin.util

import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.data.entity.OffsetCredit
import com.zerolab.checkin.data.repo.CheckinRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 数据导入：解析 data.json，按 createdAt 匹配（name 兜底），记录按 (日期, 时间) 去重合并，盾牌按 (mode, earnedDate) 去重，幂等可重复执行。 */
object JsonImporter {

    class Result(
        val newItems: Int,
        val mergedItems: Int,
        val newRecords: Int,
        val skippedRecords: Int,
        val newCredits: Int,
        val skippedCredits: Int,
        val restoredMedia: Int,
        val missingMedia: Int,
        val quickSet: Boolean
    ) {
        fun summary(): String = buildString {
            append("导入完成：新建打卡项 $newItems 个，合并 $mergedItems 个；")
            append("新增记录 $newRecords 条，跳过重复 $skippedRecords 条；")
            append("新增盾牌 $newCredits 个，跳过 $skippedCredits 个；")
            if (restoredMedia > 0 || missingMedia > 0) append("恢复素材 $restoredMedia 个，缺失 $missingMedia 个；")
            if (quickSet) append("快捷打卡项已设置。")
        }
    }

    /**
     * @param mediaRoot ZIP 解压后的目录（含 photos/、voices/）；为 null 表示仅 JSON 导入，素材不恢复。
     * @param appFilesDir 应用 filesDir（素材落盘目录）
     */
    fun importFromJson(repo: CheckinRepository, text: String, mediaRoot: File?, appFilesDir: File): Result {
        val root = JSONObject(text)
        val itemsArr = root.optJSONArray("checkinItems") ?: JSONArray()
        val recsArr = root.optJSONArray("checkinRecords") ?: JSONArray()
        val credsArr = root.optJSONArray("offsetCredits") ?: JSONArray()
        val quickId = if (root.isNull("quickCheckinItemId")) null else root.optLong("quickCheckinItemId")

        val locals = repo.getItems()
        // 匹配索引：联合键 (createdAt, name) 优先；name / createdAt 单独兜底仅在本机唯一时生效。
        // 注意：只索引导入前的既有项，本次新建的项不加入索引——同文件内同 createdAt 的项互不匹配，永远分别新建。
        val byCreatedName = HashMap<Pair<Long, String>, CheckinItem>()
        val byName = HashMap<String, MutableList<CheckinItem>>()
        val byCreated = HashMap<Long, MutableList<CheckinItem>>()
        locals.forEach {
            byCreatedName[it.createdAt to it.name] = it
            byName.getOrPut(it.name) { mutableListOf() }.add(it)
            byCreated.getOrPut(it.createdAt) { mutableListOf() }.add(it)
        }
        val idMap = HashMap<Long, Long>()   // 导入文件 id -> 本机 id

        var newItems = 0; var mergedItems = 0
        var newRecords = 0; var skippedRecords = 0
        var newCredits = 0; var skippedCredits = 0
        var restoredMedia = 0; var missingMedia = 0
        var quickTarget: Long? = null

        // ---------- 打卡项：匹配 / 新建 ----------
        for (i in 0 until itemsArr.length()) {
            val jo = itemsArr.getJSONObject(i)
            val impId = jo.getLong("id")
            val name = jo.getString("name")
            val createdAt = jo.getLong("createdAt")
            val updatedAt = if (jo.has("updatedAt")) jo.getLong("updatedAt") else 0L

            val local = byCreatedName[createdAt to name]
                ?: byName[name]?.singleOrNull()
                ?: byCreated[createdAt]?.singleOrNull()
            val newId: Long
            if (local != null) {
                mergedItems++
                if (updatedAt > local.updatedAt) {
                    repo.updateItem(local.copy(
                        name = name,
                        type = jo.optString("type", local.type),
                        configJson = jo.optString("configJson", local.configJson),
                        icon = jo.optString("icon", local.icon),
                        theme = jo.optString("theme", local.theme),
                        groupTag = if (jo.isNull("groupTag")) null else jo.optString("groupTag", "").ifEmpty { null },
                        editPolicy = jo.optString("editPolicy", local.editPolicy),
                        editInterval = if (jo.isNull("editInterval")) null else jo.optLong("editInterval").toInt(),
                        lastEditDate = if (jo.isNull("lastEditDate")) null else jo.optString("lastEditDate", "")
                    ))
                }
                newId = local.id
            } else {
                newItems++
                val newItem = CheckinItem(
                    name = name,
                    type = jo.optString("type", "NORMAL"),
                    configJson = jo.optString("configJson", "{}"),
                    icon = jo.optString("icon", "default"),
                    theme = jo.optString("theme", "sakura"),
                    groupTag = if (jo.isNull("groupTag")) null else jo.optString("groupTag", "").ifEmpty { null },
                    sortOrder = (locals.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                    editPolicy = jo.optString("editPolicy", "LOCKED"),
                    editInterval = if (jo.isNull("editInterval")) null else jo.optLong("editInterval").toInt(),
                    lastEditDate = if (jo.isNull("lastEditDate")) null else jo.optString("lastEditDate", ""),
                    createdAt = createdAt
                )
                newId = repo.insertItem(newItem)
            }
            idMap[impId] = newId
            if (quickId != null && impId == quickId) quickTarget = newId
        }

        // ---------- 记录：按 (日期, 时间) 去重合并，素材恢复 ----------
        val keyCache = HashMap<Long, MutableSet<Pair<String, Long>>>()
        for (i in 0 until recsArr.length()) {
            val jo = recsArr.getJSONObject(i)
            val newItemId = idMap[jo.getLong("itemId")] ?: continue
            val date = jo.getString("checkinDate")
            val time = jo.getLong("checkinTime")
            val keys = keyCache.getOrPut(newItemId) {
                repo.allRecords(newItemId).mapTo(HashSet()) { it.checkinDate to it.checkinTime }
            }
            if (!keys.add(date to time)) { skippedRecords++; continue }

            var photo: String? = null
            if (!jo.isNull("photoPath")) {
                photo = restoreMedia(jo.getString("photoPath"), newItemId, mediaRoot, appFilesDir, "photos")
                if (photo != null) restoredMedia++ else if (mediaRoot != null) missingMedia++
            }
            var voice: String? = null
            if (!jo.isNull("voicePath")) {
                voice = restoreMedia(jo.getString("voicePath"), newItemId, mediaRoot, appFilesDir, "voices")
                if (voice != null) restoredMedia++ else if (mediaRoot != null) missingMedia++
            }

            repo.insertRecord(CheckinRecord(
                itemId = newItemId,
                checkinDate = date,
                checkinTime = time,
                status = jo.getString("status"),
                isAuto = if (jo.optBoolean("isAuto")) 1 else 0,
                photoPath = photo,
                textContent = if (jo.isNull("textContent")) null else jo.getString("textContent"),
                voicePath = voice,
                latitude = if (jo.isNull("latitude")) null else jo.getDouble("latitude"),
                longitude = if (jo.isNull("longitude")) null else jo.getDouble("longitude"),
                extraJson = if (jo.isNull("extraJson")) null else jo.getString("extraJson")
            ))
            newRecords++
        }

        // ---------- 盾牌：已用的不导入；未用的按 (mode, earnedDate) 去重 ----------
        for (i in 0 until credsArr.length()) {
            val jo = credsArr.getJSONObject(i)
            val newItemId = idMap[jo.getLong("itemId")] ?: continue
            if (jo.optBoolean("isUsed")) { skippedCredits++; continue }
            val mode = jo.getString("mode")
            val earned = jo.getString("earnedDate")
            if (repo.creditsOf(newItemId).any { it.mode == mode && it.earnedDate == earned }) {
                skippedCredits++; continue
            }
            repo.grantCredit(OffsetCredit(
                itemId = newItemId,
                mode = mode,
                earnedDate = earned,
                expireDate = if (jo.isNull("expireDate")) null else jo.getString("expireDate")
            ))
            newCredits++
        }

        var quickSet = false
        if (quickTarget != null) {
            repo.setQuick(quickTarget)
            quickSet = true
        }
        return Result(newItems, mergedItems, newRecords, skippedRecords, newCredits, skippedCredits, restoredMedia, missingMedia, quickSet)
    }

    /** 相对路径（photos/{oldItemId}/{name}）→ 本机 files/checkin_{kind}/{newItemId}/{name}；无素材源或文件缺失返回 null；目标已存在直接复用（幂等）。 */
    private fun restoreMedia(rel: String, newItemId: Long, mediaRoot: File?, appFilesDir: File, kind: String): String? {
        if (mediaRoot == null) return null
        val src = File(mediaRoot, rel)
        if (!src.exists() || !src.isFile) return null
        val name = rel.substringAfterLast('/')
        val outDir = File(appFilesDir, "checkin_$kind/$newItemId")
        outDir.mkdirs()
        val dst = File(outDir, name)
        if (!dst.exists()) src.copyTo(dst, overwrite = true)
        return dst.absolutePath
    }
}
