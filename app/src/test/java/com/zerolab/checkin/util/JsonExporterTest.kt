package com.zerolab.checkin.util

import com.zerolab.checkin.BuildConfig
import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.data.entity.OffsetCredit
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.testutil.FakeCheckinStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.2：导出 JSON 的结构与素材相对路径改写（导入侧依赖这些约定） */
class JsonExporterTest {

    private fun item(id: Long, name: String, created: Long, cfg: ItemConfig = ItemConfig()) =
        CheckinItem(
            id = id, name = name, type = "NORMAL", configJson = cfg.toJson(),
            icon = "water", theme = "sakura", createdAt = created
        )

    private fun storeWithData(): FakeCheckinStore {
        val store = FakeCheckinStore()
        store.itemList.add(item(1, "喝水", 1_700_000_000_000))
        store.itemList.add(item(2, "跑步", 1_700_000_100_000))
        store.quickItemId = 1

        store.records.add(
            CheckinRecord(
                id = 11, itemId = 1, checkinDate = "2026-10-25", checkinTime = 1_790_000_000_000,
                status = "SUCCESS", photoPath = "/data/user/0/app/files/checkin_photos/1/p1.webp",
                textContent = "早上喝水",
                extraJson = """{"photoPaths":["/data/user/0/app/files/checkin_photos/1/p1.webp"],"method":"PHOTO"}"""
            )
        )
        store.records.add(
            CheckinRecord(
                id = 12, itemId = 2, checkinDate = "2026-10-26", checkinTime = 1_790_100_000_000,
                status = "SUCCESS", voicePath = "/data/user/0/app/files/checkin_voices/2/v1.aac"
            )
        )
        store.credits.add(OffsetCredit(id = 21, itemId = 1, mode = "A", earnedDate = "2026-10-20"))
        store.credits.add(
            OffsetCredit(id = 22, itemId = 1, mode = "A", earnedDate = "2026-10-21", isUsed = 1, usedDate = "2026-10-22")
        )
        return store
    }

    @Test
    fun build_contains_version_items_records_and_quick() {
        val json = JsonExporter.build(storeWithData())
        assertEquals("1.2", json.getString("exportVersion"))
        assertEquals(BuildConfig.VERSION_NAME, json.getString("appVersion"))
        assertEquals(1L, json.getLong("quickCheckinItemId"))
        assertEquals(2, json.getJSONArray("checkinItems").length())
        assertEquals(2, json.getJSONArray("checkinRecords").length())
        assertEquals(2, json.getJSONArray("offsetCredits").length())
        assertTrue(json.getJSONArray("checkinItems").getJSONObject(0).has("lastEditDate"))
    }

    @Test
    fun build_rewrites_media_paths_to_zip_relative() {
        val json = JsonExporter.build(storeWithData())
        val rec = json.getJSONArray("checkinRecords").getJSONObject(0)
        assertEquals("photos/1/p1.webp", rec.getString("photoPath"))
        val extra = JSONObject(rec.getString("extraJson"))
        assertEquals("photos/1/p1.webp", extra.getJSONArray("photoPaths").getString(0))
        assertEquals("PHOTO", extra.getString("method"))

        val voice = json.getJSONArray("checkinRecords").getJSONObject(1)
        assertEquals("voices/2/v1.aac", voice.getString("voicePath"))
    }

    @Test
    fun build_with_selected_ids_only_exports_those_items() {
        val json = JsonExporter.build(storeWithData(), ids = listOf(2))
        val items = json.getJSONArray("checkinItems")
        assertEquals(1, items.length())
        assertEquals(2L, items.getJSONObject(0).getLong("id"))
        assertEquals(1, json.getJSONArray("checkinRecords").length())
        assertEquals(2L, json.getJSONArray("checkinRecords").getJSONObject(0).getLong("itemId"))
        // 快捷项不在选中集时导出为空
        assertTrue(json.isNull("quickCheckinItemId"))
    }

    @Test
    fun build_keeps_null_media_as_null() {
        val store = FakeCheckinStore()
        store.itemList.add(item(1, "无素材", 1_700_000_000_000))
        store.records.add(
            CheckinRecord(id = 1, itemId = 1, checkinDate = "2026-10-26", checkinTime = 1L, status = "SUCCESS")
        )
        val rec = JsonExporter.build(store).getJSONArray("checkinRecords").getJSONObject(0)
        assertTrue(rec.isNull("photoPath"))
        assertTrue(rec.isNull("voicePath"))
        assertTrue(rec.isNull("extraJson"))
    }

    @Test
    fun build_empty_store_is_valid() {
        val json = JsonExporter.build(FakeCheckinStore())
        assertEquals(0, json.getJSONArray("checkinItems").length())
        assertEquals(0, json.getJSONArray("checkinRecords").length())
        assertTrue(json.isNull("quickCheckinItemId"))
        assertFalse(json.has("unknownField"))
    }
}
