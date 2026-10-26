package com.zerolab.checkin.util

import com.zerolab.checkin.data.entity.CheckinItem
import com.zerolab.checkin.data.entity.CheckinRecord
import com.zerolab.checkin.data.entity.OffsetCredit
import com.zerolab.checkin.engine.ItemConfig
import com.zerolab.checkin.testutil.FakeCheckinStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v1.4.2：导入的匹配 / 合并 / 去重 / 素材恢复 / 幂等（此前完全没有自动化覆盖） */
class JsonImporterTest {

    private val tmpRoot: File = Files.createTempDirectory("checkin-import-test").toFile()
    private val appFilesDir = File(tmpRoot, "files").apply { mkdirs() }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    private fun item(
        id: Long,
        name: String,
        created: Long,
        cfg: ItemConfig = ItemConfig(),
        theme: String = "sakura",
        icon: String = "water",
        updated: Long = created,
        groupTag: String? = null
    ) = CheckinItem(
        id = id, name = name, type = "NORMAL", configJson = cfg.toJson(),
        icon = icon, theme = theme, createdAt = created, updatedAt = updated, groupTag = groupTag
    )

    private fun exportJson(store: FakeCheckinStore): String =
        JsonExporter.build(store).toString(2)

    @Test
    fun import_into_empty_store_creates_everything() {
        val src = FakeCheckinStore()
        src.itemList.add(item(1, "喝水", 1_700_000_000_000))
        src.quickItemId = 1
        src.records.add(
            CheckinRecord(itemId = 1, checkinDate = "2026-10-26", checkinTime = 1_790_000_000_000, status = "SUCCESS")
        )
        src.credits.add(OffsetCredit(itemId = 1, mode = "A", earnedDate = "2026-10-20"))

        val target = FakeCheckinStore()
        val r = JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)

        assertEquals(1, r.newItems)
        assertEquals(0, r.mergedItems)
        assertEquals(1, r.newRecords)
        assertEquals(1, r.newCredits)
        assertTrue(r.quickSet)
        assertEquals("喝水", target.itemList.single().name)
        assertEquals(1, target.allRecords(target.itemList.single().id).size)
        assertEquals(1, target.availableCredits(target.itemList.single().id))
    }

    @Test
    fun import_twice_is_idempotent() {
        val src = FakeCheckinStore()
        src.itemList.add(item(1, "喝水", 1_700_000_000_000))
        src.records.add(
            CheckinRecord(itemId = 1, checkinDate = "2026-10-26", checkinTime = 1_790_000_000_000, status = "SUCCESS")
        )
        src.credits.add(OffsetCredit(itemId = 1, mode = "A", earnedDate = "2026-10-20"))
        val json = exportJson(src)

        val target = FakeCheckinStore()
        JsonImporter.importFromJson(target, json, null, appFilesDir)
        val second = JsonImporter.importFromJson(target, json, null, appFilesDir)

        assertEquals(1, target.itemList.size)
        assertEquals(1, target.records.size)
        assertEquals(1, target.credits.size)
        assertEquals(0, second.newItems)
        assertEquals(1, second.mergedItems)
        assertEquals(0, second.newRecords)
        assertEquals(1, second.skippedRecords)
        assertEquals(0, second.newCredits)
        assertEquals(1, second.skippedCredits)
    }

    @Test
    fun matched_item_is_updated_when_file_is_newer() {
        val created = 1_700_000_000_000
        val local = item(1, "喝水", created, theme = "sakura", icon = "water", updated = created)
        val target = FakeCheckinStore(listOf(local))

        val newer = item(2, "喝水", created, theme = "ocean", icon = "run", updated = created + 10_000)
        val src = FakeCheckinStore(listOf(newer))
        val r = JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)

        assertEquals(0, r.newItems)
        assertEquals(1, r.mergedItems)
        assertEquals(1, target.itemList.size)
        assertEquals("ocean", target.itemList.single().theme)
        assertEquals("run", target.itemList.single().icon)
    }

    @Test
    fun matched_item_keeps_local_when_local_is_newer() {
        val created = 1_700_000_000_000
        val local = item(1, "喝水", created, theme = "mint", updated = created + 99_000)
        val target = FakeCheckinStore(listOf(local))
        val older = item(2, "喝水", created, theme = "ocean", updated = created)
        val src = FakeCheckinStore(listOf(older))

        JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)
        assertEquals("mint", target.itemList.single().theme)
    }

    @Test
    fun name_fallback_merges_when_unique() {
        val target = FakeCheckinStore(listOf(item(1, "跑步", 1_700_000_000_000, theme = "sakura", updated = 1_700_000_000_000)))
        val src = FakeCheckinStore(listOf(item(9, "跑步", 1_700_000_500_000, theme = "coral", updated = 1_700_000_600_000)))

        val r = JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)
        assertEquals(0, r.newItems)
        assertEquals(1, r.mergedItems)
        assertEquals(1, target.itemList.size)
        assertEquals("coral", target.itemList.single().theme)
    }

    @Test
    fun record_dedupe_by_date_and_time() {
        val time = 1_790_000_000_000
        val localItem = item(1, "喝水", 1_700_000_000_000)
        val target = FakeCheckinStore(listOf(localItem))
        target.records.add(
            CheckinRecord(itemId = 1, checkinDate = "2026-10-26", checkinTime = time, status = "SUCCESS")
        )

        val src = FakeCheckinStore()
        src.itemList.add(item(1, "喝水", 1_700_000_000_000))
        src.records.add(
            CheckinRecord(itemId = 1, checkinDate = "2026-10-26", checkinTime = time, status = "SUCCESS")
        )
        src.records.add(
            CheckinRecord(itemId = 1, checkinDate = "2026-10-27", checkinTime = time + 1, status = "SUCCESS")
        )

        val r = JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)
        assertEquals(1, r.newRecords)
        assertEquals(1, r.skippedRecords)
        assertEquals(2, target.records.size)
    }

    @Test
    fun used_credits_skipped_and_unused_deduped() {
        val src = FakeCheckinStore()
        src.itemList.add(item(1, "喝水", 1_700_000_000_000))
        src.credits.add(OffsetCredit(itemId = 1, mode = "A", earnedDate = "2026-10-20"))
        src.credits.add(OffsetCredit(itemId = 1, mode = "A", earnedDate = "2026-10-21", isUsed = 1, usedDate = "2026-10-22"))

        val target = FakeCheckinStore()
        val r = JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)
        assertEquals(1, r.newCredits)
        assertEquals(1, r.skippedCredits)
        assertEquals(1, target.credits.size)

        val again = JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)
        assertEquals(0, again.newCredits)
        assertEquals(2, again.skippedCredits)
    }

    @Test
    fun quick_item_is_not_overwritten_when_local_already_has_one() {
        val local = item(1, "本地项", 1_600_000_000_000)
        val target = FakeCheckinStore(listOf(local))
        target.quickItemId = 1

        val src = FakeCheckinStore()
        src.itemList.add(item(2, "导入项", 1_700_000_000_000))
        src.quickItemId = 2

        val r = JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)
        assertEquals(false, r.quickSet)
        assertEquals(1L, target.quickItemId)
        assertEquals(2, target.itemList.size)
    }

    @Test
    fun group_references_are_remapped_to_new_ids() {
        val groupCfg = ItemConfig().apply {
            groupMode = true
            groupMembers.add(2)
            groupMemberNames.add("子项")
        }
        val src = FakeCheckinStore()
        src.itemList.add(item(1, "打卡组", 1_700_000_000_000, groupCfg))
        src.itemList.add(item(2, "子项", 1_700_000_100_000, groupTag = "1"))

        // 目标机已有一个无关项（id=1），导入后 id 会被重新分配
        val target = FakeCheckinStore(listOf(item(1, "旧项", 1_500_000_000_000)))
        val r = JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)
        assertEquals(2, r.newItems)

        val group = target.itemList.first { it.name == "打卡组" }
        val child = target.itemList.first { it.name == "子项" }
        assertEquals(group.id.toString(), child.groupTag)
        val cfg = ItemConfig.parse(group.configJson)
        assertEquals(listOf(child.id), cfg.groupMembers)
        assertEquals(listOf("子项"), cfg.groupMemberNames)
    }

    @Test
    fun media_is_restored_to_app_files_dir() {
        val src = FakeCheckinStore()
        src.itemList.add(item(1, "拍照", 1_700_000_000_000))
        src.records.add(
            CheckinRecord(
                itemId = 1, checkinDate = "2026-10-26", checkinTime = 1_790_000_000_000,
                status = "SUCCESS", photoPath = "/old/files/checkin_photos/1/p.webp"
            )
        )

        val mediaRoot = File(tmpRoot, "unzip").apply { mkdirs() }
        val photoDir = File(mediaRoot, "photos/1").apply { mkdirs() }
        File(photoDir, "p.webp").writeBytes(byteArrayOf(1, 2, 3))

        val target = FakeCheckinStore()
        val r = JsonImporter.importFromJson(target, exportJson(src), mediaRoot, appFilesDir)

        assertEquals(1, r.restoredMedia)
        assertEquals(0, r.missingMedia)
        val rec = target.records.single()
        val expectedDir = File(appFilesDir, "checkin_photos/${rec.itemId}")
        assertEquals(File(expectedDir, "p.webp").absolutePath, rec.photoPath)
        assertTrue(File(rec.photoPath).exists())
    }

    @Test
    fun missing_media_is_counted_without_failing() {
        val src = FakeCheckinStore()
        src.itemList.add(item(1, "拍照", 1_700_000_000_000))
        src.records.add(
            CheckinRecord(
                itemId = 1, checkinDate = "2026-10-26", checkinTime = 1_790_000_000_000,
                status = "SUCCESS", photoPath = "/old/files/checkin_photos/1/missing.webp"
            )
        )
        val mediaRoot = File(tmpRoot, "empty-unzip").apply { mkdirs() }
        val target = FakeCheckinStore()
        val r = JsonImporter.importFromJson(target, exportJson(src), mediaRoot, appFilesDir)
        assertEquals(1, r.missingMedia)
        assertEquals(1, r.newRecords)
        assertNull(target.records.single().photoPath)
    }

    @Test
    fun new_items_restore_paused_and_pinned_state() {
        val src = FakeCheckinStore()
        src.itemList.add(
            item(1, "暂停项", 1_700_000_000_000).copy(isActive = 0, isPinned = 1, pinnedAt = 1_700_000_000_500)
        )
        val target = FakeCheckinStore()
        JsonImporter.importFromJson(target, exportJson(src), null, appFilesDir)

        val imported = target.itemList.single()
        assertEquals(0, imported.isActive)
        assertEquals(1, imported.isPinned)
        assertNotNull(imported.pinnedAt)
    }

    @Test
    fun import_does_not_clear_local_last_edit_date_when_file_lacks_it() {
        val created = 1_700_000_000_000
        val target = FakeCheckinStore(listOf(item(1, "喝水", created, updated = created).copy(lastEditDate = "2026-10-01")))
        // 模拟旧导出文件：删掉 lastEditDate 字段
        val json = JSONObject(exportJson(srcFor("喝水", created)))
        json.getJSONArray("checkinItems").getJSONObject(0).remove("lastEditDate")

        JsonImporter.importFromJson(target, json.toString(), null, appFilesDir)
        assertEquals("2026-10-01", target.itemList.single().lastEditDate)
    }

    private fun srcFor(name: String, created: Long): FakeCheckinStore =
        FakeCheckinStore(listOf(item(1, name, created, updated = created + 10_000)))
}
