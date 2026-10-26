package com.zerolab.checkin.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.2：记录 extraJson 中媒体路径的提取与改写（多图 / 随心记块流导入导出依赖） */
class RecordMediaTest {

    @Test
    fun extract_reads_photo_paths_array() {
        val extra = """{"photoPaths":["/a.jpg","/b.jpg"]}"""
        assertEquals(
            listOf(RecordMedia.KIND_PHOTO to "/a.jpg", RecordMedia.KIND_PHOTO to "/b.jpg"),
            RecordMedia.extract(extra)
        )
    }

    @Test
    fun extract_reads_journal_blocks() {
        val extra = """
            {"blocks":[
              {"type":"photos","paths":["/p1.jpg"]},
              {"type":"voice","voices":[{"path":"/v1.aac","seconds":3}]},
              {"type":"text","text":"hi"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(RecordMedia.KIND_PHOTO to "/p1.jpg", RecordMedia.KIND_VOICE to "/v1.aac"),
            RecordMedia.extract(extra)
        )
    }

    @Test
    fun extract_supports_legacy_single_path_fields() {
        val extra = """{"blocks":[{"type":"photo","path":"/old.jpg"},{"type":"voice","path":"/old.aac"}]}"""
        assertEquals(
            listOf(RecordMedia.KIND_PHOTO to "/old.jpg", RecordMedia.KIND_VOICE to "/old.aac"),
            RecordMedia.extract(extra)
        )
    }

    @Test
    fun extract_null_or_blank_returns_empty() {
        assertTrue(RecordMedia.extract(null).isEmpty())
        assertTrue(RecordMedia.extract("").isEmpty())
        assertTrue(RecordMedia.extract("{}").isEmpty())
    }

    @Test
    fun rewrite_maps_photo_paths_and_preserves_other_keys() {
        val extra = """{"timerMode":"COUNTDOWN","photoPaths":["/a.jpg","/b.jpg"]}"""
        val out = RecordMedia.rewrite(extra) { kind, p ->
            if (kind == RecordMedia.KIND_PHOTO) "photos/1/${p.substringAfterLast('/')}" else p
        }!!
        val json = JSONObject(out)
        assertEquals("photos/1/a.jpg", json.getJSONArray("photoPaths").getString(0))
        assertEquals("COUNTDOWN", json.getString("timerMode"))
    }

    @Test
    fun rewrite_drops_media_when_transform_returns_null() {
        val extra = """{"photoPaths":["/missing.jpg","/keep.jpg"]}"""
        val out = RecordMedia.rewrite(extra) { _, p -> if (p.contains("missing")) null else p }!!
        val arr = JSONObject(out).getJSONArray("photoPaths")
        assertEquals(1, arr.length())
        assertEquals("/keep.jpg", arr.getString(0))
    }

    @Test
    fun rewrite_removes_empty_photo_paths_and_returns_null_when_nothing_left() {
        assertNull(RecordMedia.rewrite("""{"photoPaths":["/a.jpg"]}""") { _, _ -> null })
        val onlyTimer = RecordMedia.rewrite("""{"timerMode":"COUNTUP"}""") { _, p -> p }
        assertTrue(onlyTimer!!.contains("COUNTUP"))
    }

    @Test
    fun rewrite_handles_journal_blocks_and_legacy_fields() {
        val extra = """
            {"blocks":[
              {"type":"photos","paths":["/p1.jpg","/p2.jpg"]},
              {"type":"voice","voices":[{"path":"/v1.aac","seconds":2}]},
              {"type":"photo","path":"/legacy.jpg"},
              {"type":"voice","path":"/legacy.aac"}
            ]}
        """.trimIndent()
        val out = RecordMedia.rewrite(extra) { kind, p ->
            if (p.contains("p2") || p.contains("legacy.aac")) null else "$kind/${p.substringAfterLast('/')}"
        }!!
        val json = JSONObject(out)
        val blocks = json.getJSONArray("blocks")
        val photos = blocks.getJSONObject(0).getJSONArray("paths")
        assertEquals(1, photos.length())
        assertEquals("photos/p1.jpg", photos.getString(0))
        val voices = blocks.getJSONObject(1).getJSONArray("voices")
        assertEquals("voices/v1.aac", voices.getJSONObject(0).getString("path"))
        assertEquals("photos/legacy.jpg", blocks.getJSONObject(2).getString("path"))
        // 缺失的旧版语音字段被移除
        assertFalse(blocks.getJSONObject(3).has("path"))
    }

    @Test
    fun rewrite_keeps_original_json_when_malformed() {
        val broken = "{not json"
        assertEquals(broken, RecordMedia.rewrite(broken) { _, p -> p })
    }
}
