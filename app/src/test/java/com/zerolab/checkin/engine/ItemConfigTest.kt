package com.zerolab.checkin.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.2：ItemConfig 的序列化 / 兼容解析（打卡项的全部业务字段都存这里） */
class ItemConfigTest {

    @Test
    fun defaults_for_blank_or_malformed_json() {
        listOf(null, "", "{not json").forEach { raw ->
            val c = ItemConfig.parse(raw)
            assertEquals(setOf(Method.NORMAL.key), c.methods)
            assertEquals(1, c.dailyLimit)
            assertEquals("DAILY", c.scheduleMode)
            assertEquals("COUNTDOWN", c.timerMode)
            assertFalse(c.negative)
            assertFalse(c.journalMode)
            assertFalse(c.moodMode)
            assertEquals(-1, c.dayCutoff)
        }
    }

    @Test
    fun legacy_single_type_field_falls_back_to_methods() {
        val c = ItemConfig.parse("""{"type":"TIMER"}""")
        assertEquals(setOf(Method.TIMER.key), c.methods)
    }

    @Test
    fun paused_ranges_round_trip_and_legacy_missing_field() {
        val c = ItemConfig().apply {
            pausedRanges.add(PauseRange("2026-10-20", ""))
            pausedRanges.add(PauseRange("2026-10-10", "2026-10-12"))
        }
        val back = ItemConfig.parse(c.toJson())
        assertEquals(2, back.pausedRanges.size)
        assertEquals("2026-10-20", back.pausedRanges[0].from)
        assertEquals("", back.pausedRanges[0].to)
        assertEquals("2026-10-12", back.pausedRanges[1].to)
        // 旧配置没有该字段 → 空列表
        assertTrue(ItemConfig.parse("""{"methods":["NORMAL"]}""").pausedRanges.isEmpty())
    }

    @Test
    fun round_trip_keeps_methods_and_core_rules() {
        val c = ItemConfig().apply {
            methods.clear(); methods.add(Method.PHOTO.key); methods.add(Method.TEXT.key)
            dailyLimit = 3
            dailyAllRequired = true
            negative = false
            timeWindowEnabled = true; twStart = "06:00"; twEnd = "09:00"
            textMinWords = 12; textNoRepeat = true
            photoFromCamera = false
            timerMode = "COUNTUP"; timerMinutes = 45; timerPausable = true
            dayCutoff = 180
            scheduleMode = "WEEKDAYS"; weekDays.addAll(listOf(1, 3, 5)); bigSmallStart = "SMALL"
            moodMode = true; moodChart = false
            comboRequired = 2
        }
        val back = ItemConfig.parse(c.toJson())
        assertEquals(c.methods, back.methods)
        assertEquals(3, back.dailyLimit)
        assertTrue(back.dailyAllRequired)
        assertTrue(back.timeWindowEnabled)
        assertEquals("06:00", back.twStart)
        assertEquals("09:00", back.twEnd)
        assertEquals(12, back.textMinWords)
        assertTrue(back.textNoRepeat)
        assertFalse(back.photoFromCamera)
        assertEquals("COUNTUP", back.timerMode)
        assertEquals(45, back.timerMinutes)
        assertTrue(back.timerPausable)
        assertEquals(180, back.dayCutoff)
        assertEquals("WEEKDAYS", back.scheduleMode)
        assertEquals(setOf(1, 3, 5), back.weekDays)
        assertEquals("SMALL", back.bigSmallStart)
        assertTrue(back.moodMode)
        assertFalse(back.moodChart)
        assertEquals(2, back.comboRequired)
    }

    @Test
    fun round_trip_keeps_offset_and_group_members() {
        val c = ItemConfig().apply {
            groupMode = true
            groupMembers.addAll(listOf(11L, 12L))
            groupMemberNames.addAll(listOf("喝水", "跑步"))
            ndaysMode = true
            ndaysTarget = 66
            offset.enabled = true
            offset.mode = "C"
            offset.nDays = 5
            offset.k = 2
            offset.autoConsume = true
            offset.anchorDate = "2026-10-01"
        }
        val back = ItemConfig.parse(c.toJson())
        assertTrue(back.groupMode)
        assertEquals(listOf(11L, 12L), back.groupMembers)
        assertEquals(listOf("喝水", "跑步"), back.groupMemberNames)
        assertTrue(back.ndaysMode)
        assertEquals(66, back.ndaysTarget)
        assertTrue(back.offset.enabled)
        assertEquals("C", back.offset.mode)
        assertEquals(5, back.offset.nDays)
        assertEquals(2, back.offset.k)
        assertTrue(back.offset.autoConsume)
        assertEquals("2026-10-01", back.offset.anchorDate)
    }

    @Test
    fun unknown_keys_are_ignored() {
        val c = ItemConfig.parse("""{"methods":["TEXT"],"futureField":{"a":1}}""")
        assertEquals(setOf(Method.TEXT.key), c.methods)
    }

    @Test
    fun main_type_derivation() {
        assertEquals("NORMAL", ItemConfig.mainType(listOf("NORMAL")))
        assertEquals("TIMER", ItemConfig.mainType(listOf("TIMER")))
        assertEquals("MULTI", ItemConfig.mainType(listOf("PHOTO", "TEXT")))
        assertEquals("NORMAL", ItemConfig.mainType(emptyList()))
    }

    @Test
    fun toJson_is_valid_json_and_contains_expected_versions() {
        val json = JSONObject(ItemConfig().toJson())
        assertTrue(json.has("methods"))
        assertTrue(json.has("offset"))
        assertFalse(json.isNull("dailyLimit"))
    }
}
