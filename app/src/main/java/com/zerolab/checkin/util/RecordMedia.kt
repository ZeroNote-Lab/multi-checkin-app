package com.zerolab.checkin.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 记录 extraJson 中媒体路径的统一提取与改写（v1.4.0）。
 *
 * 覆盖三类存储：
 * 1. 普通/习惯/组子项打卡多图：extraJson.photoPaths（JSON 数组）
 * 2. 随心记 blocks：type=photos 块的 paths[]
 * 3. 随心记 blocks：type=voice 块的 voices[].path
 *
 * 顶层字段 CheckinRecord.photoPath / voicePath 由导出/导入代码各自处理，不在本工具内。
 */
object RecordMedia {

    const val KIND_PHOTO = "photos"
    const val KIND_VOICE = "voices"

    /** 提取 extraJson 中全部媒体路径（kind, path），path 原样返回（可能是绝对或相对路径）。 */
    fun extract(extraJson: String?): List<Pair<String, String>> {
        if (extraJson.isNullOrBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        try {
            val o = JSONObject(extraJson)
            o.optJSONArray("photoPaths")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i)
                    if (!p.isNullOrBlank()) out.add(KIND_PHOTO to p)
                }
            }
            o.optJSONArray("blocks")?.let { blocks ->
                for (i in 0 until blocks.length()) {
                    val b = blocks.optJSONObject(i) ?: continue
                    when (b.optString("type")) {
                        "photos", "photo" -> {
                            b.optJSONArray("paths")?.let { arr ->
                                for (j in 0 until arr.length()) {
                                    val p = arr.optString(j)
                                    if (!p.isNullOrBlank()) out.add(KIND_PHOTO to p)
                                }
                            }
                            // 兼容旧版照片块直接 path 字段
                            b.optString("path").takeIf { !it.isNullOrBlank() }?.let { out.add(KIND_PHOTO to it) }
                        }
                        "voice" -> {
                            b.optJSONArray("voices")?.let { arr ->
                                for (j in 0 until arr.length()) {
                                    val v = arr.optJSONObject(j) ?: continue
                                    val p = v.optString("path")
                                    if (!p.isNullOrBlank()) out.add(KIND_VOICE to p)
                                }
                            }
                            // 兼容旧版语音块直接 path 字段
                            b.optString("path").takeIf { !it.isNullOrBlank() }?.let { out.add(KIND_VOICE to it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * 对 extraJson 中所有媒体路径执行 transform；transform 返回 null 表示该素材缺失、移除该项。
     * extraJson 为空且无内容时返回 null。
     */
    fun rewrite(extraJson: String?, transform: (kind: String, oldPath: String) -> String?): String? {
        if (extraJson.isNullOrBlank()) return null
        return try {
            val o = JSONObject(extraJson)
            o.optJSONArray("photoPaths")?.let { arr ->
                val next = JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i)
                    if (p.isNullOrBlank()) continue
                    val np = transform(KIND_PHOTO, p)
                    if (np != null) next.put(np)
                }
                if (next.length() == 0) o.remove("photoPaths") else o.put("photoPaths", next)
            }
            o.optJSONArray("blocks")?.let { blocks ->
                for (i in 0 until blocks.length()) {
                    val b = blocks.optJSONObject(i) ?: continue
                    when (b.optString("type")) {
                        "photos", "photo" -> {
                            b.optJSONArray("paths")?.let { arr ->
                                val next = JSONArray()
                                for (j in 0 until arr.length()) {
                                    val p = arr.optString(j)
                                    if (p.isNullOrBlank()) continue
                                    val np = transform(KIND_PHOTO, p)
                                    if (np != null) next.put(np)
                                }
                                b.put("paths", next)
                            }
                            // 兼容旧版照片块直接 path 字段
                            b.optString("path").takeIf { !it.isNullOrBlank() }?.let { p ->
                                transform(KIND_PHOTO, p)?.let { b.put("path", it) }
                            }
                        }
                        "voice" -> {
                            b.optJSONArray("voices")?.let { arr ->
                                val next = JSONArray()
                                for (j in 0 until arr.length()) {
                                    val v = arr.optJSONObject(j) ?: continue
                                    val p = v.optString("path")
                                    if (p.isNullOrBlank()) continue
                                    val np = transform(KIND_VOICE, p)
                                    if (np != null) { v.put("path", np); next.put(v) }
                                }
                                b.put("voices", next)
                            }
                            // 兼容旧版语音块直接 path 字段
                            b.optString("path").takeIf { !it.isNullOrBlank() }?.let { p ->
                                transform(KIND_VOICE, p)?.let { b.put("path", it) }
                            }
                        }
                    }
                }
            }
            if (o.length() == 0) null else o.toString()
        } catch (_: Exception) {
            extraJson
        }
    }
}
