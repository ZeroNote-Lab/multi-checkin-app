package com.zerolab.checkin.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.4.2 重构：保存计划的归一化与校验（原 CreateItemActivity.save() 内的纯逻辑） */
class CreatePlannerTest {

    private fun input(
        name: String = "测试项",
        cfg: ItemConfig = ItemConfig(),
        journalMode: Boolean = false,
        moodMode: Boolean = false,
        ndaysMode: Boolean = false,
        ndaysTarget: Int = 21,
        ndaysMethods: Set<String> = setOf(Method.NORMAL.key),
        scheduleMode: String = "DAILY",
        weekDays: Set<Int> = emptySet(),
        bigSmallStart: String = "BIG",
        editPolicy: String = "LOCKED",
        editInterval: Int? = null,
        editingFlex: Boolean = false
    ) = CreateInput(
        name = name, theme = "sakura", icon = "water", cfg = cfg,
        journalMode = journalMode, moodMode = moodMode,
        ndaysMode = ndaysMode, ndaysTarget = ndaysTarget, ndaysMethods = ndaysMethods,
        scheduleMode = scheduleMode, weekDays = weekDays, bigSmallStart = bigSmallStart,
        editPolicyRequested = editPolicy, editIntervalRequested = editInterval,
        editingFlexPolicy = editingFlex
    )

    private fun draft(plan: CreatePlan): CreateDraft =
        (plan as CreatePlan.Ready).draft

    private fun rejection(plan: CreatePlan): String =
        (plan as CreatePlan.Rejected).message

    @Test
    fun normal_item_saves_with_locked_policy() {
        val d = draft(CreatePlanner.plan(input()))
        assertEquals("测试项", d.name)
        assertEquals("NORMAL", d.type)
        assertEquals("LOCKED", d.editPolicy)
        assertNull(d.editInterval)
        val json = JSONObject(d.configJson)
        assertEquals("NORMAL", json.getJSONArray("methods").getString(0))
        assertEquals(1, json.getInt("dailyLimit"))
    }

    @Test
    fun blank_name_is_rejected_first() {
        assertEquals("请填写打卡名称", rejection(CreatePlanner.plan(input(name = "  "))))
    }

    @Test
    fun no_method_is_rejected() {
        val cfg = ItemConfig().apply { methods.clear() }
        assertEquals("请至少打开一种打卡方式", rejection(CreatePlanner.plan(input(cfg = cfg))))
    }

    @Test
    fun conflicting_methods_are_rejected() {
        val cfg = ItemConfig().apply {
            methods.clear(); methods.add(Method.NORMAL.key); methods.add(Method.PHOTO.key)
        }
        assertEquals("所选方式存在互斥冲突", rejection(CreatePlanner.plan(input(cfg = cfg))))
    }

    @Test
    fun journal_mode_forces_four_builtin_methods_and_flex() {
        val cfg = ItemConfig().apply { methods.clear(); methods.add(Method.NORMAL.key) }
        val d = draft(CreatePlanner.plan(input(cfg = cfg, journalMode = true)))
        assertEquals("FLEX", d.editPolicy)
        val json = JSONObject(d.configJson)
        assertTrue(json.getBoolean("journalMode"))
        assertEquals(-1, json.getInt("dailyLimit"))
        val methods = json.getJSONArray("methods")
        val set = (0 until methods.length()).map { methods.getString(it) }.toSet()
        assertEquals(setOf("PHOTO", "TEXT", "VOICE", "LOCATION"), set)
    }

    @Test
    fun mood_mode_keeps_only_mood_and_implies_journal() {
        val cfg = ItemConfig().apply { methods.clear(); methods.add(Method.PHOTO.key) }
        val d = draft(CreatePlanner.plan(input(cfg = cfg, moodMode = true)))
        val json = JSONObject(d.configJson)
        assertEquals(1, json.getJSONArray("methods").length())
        assertEquals("MOOD", json.getJSONArray("methods").getString(0))
        assertTrue(json.getBoolean("journalMode"))
        assertEquals("FLEX", d.editPolicy)
    }

    @Test
    fun ndays_mode_applies_target_and_forces_daily_schedule() {
        val cfg = ItemConfig()
        val d = draft(CreatePlanner.plan(
            input(cfg = cfg, ndaysMode = true, ndaysTarget = 30,
                ndaysMethods = setOf(Method.NORMAL.key), scheduleMode = "BIGSMALL")
        ))
        val json = JSONObject(d.configJson)
        assertTrue(json.getBoolean("ndaysMode"))
        assertEquals(30, json.getInt("ndaysTarget"))
        assertEquals("DAILY", json.getString("scheduleMode"))
        assertEquals(false, json.getBoolean("negative"))
    }

    @Test
    fun weekdays_schedule_without_any_day_is_rejected() {
        assertEquals(
            "「每周固定几天」至少选择一天",
            rejection(CreatePlanner.plan(input(scheduleMode = "WEEKDAYS", weekDays = emptySet())))
        )
        // 选了一天即可通过
        val d = draft(CreatePlanner.plan(input(scheduleMode = "WEEKDAYS", weekDays = setOf(1, 3))))
        assertEquals(2, JSONObject(d.configJson).getJSONArray("weekDays").length())
    }

    @Test
    fun location_without_standard_point_is_rejected() {
        val cfg = ItemConfig().apply { methods.clear(); methods.add(Method.LOCATION.key) }
        assertEquals("位置打卡需先获取一个标准位置", rejection(CreatePlanner.plan(input(cfg = cfg))))
    }

    @Test
    fun photo_without_any_source_is_rejected() {
        val cfg = ItemConfig().apply {
            methods.clear(); methods.add(Method.PHOTO.key)
            photoFromCamera = false; photoFromAlbum = false
        }
        assertEquals("拍照打卡需至少勾选一种图片来源", rejection(CreatePlanner.plan(input(cfg = cfg))))
    }

    @Test
    fun nfc_and_qr_require_binding() {
        val nfc = ItemConfig().apply { methods.clear(); methods.add(Method.NFC.key) }
        assertEquals("NFC打卡需先绑定 NFC 标签", rejection(CreatePlanner.plan(input(cfg = nfc))))
        val qr = ItemConfig().apply { methods.clear(); methods.add(Method.QRCODE.key) }
        assertEquals("扫码打卡需先生成专属二维码", rejection(CreatePlanner.plan(input(cfg = qr))))
    }

    @Test
    fun time_window_requires_start_before_end() {
        val cfg = ItemConfig().apply {
            timeWindowEnabled = true; twStart = "08:30"; twEnd = "05:00"
        }
        assertEquals(
            "时间段开始需早于结束（如 05:00 / 08:30），暂不支持跨天",
            rejection(CreatePlanner.plan(input(cfg = cfg)))
        )
    }

    @Test
    fun combo_methods_force_daily_limit_one() {
        val cfg = ItemConfig().apply {
            methods.clear(); methods.add(Method.PHOTO.key); methods.add(Method.TEXT.key)
            dailyLimit = 3
        }
        val d = draft(CreatePlanner.plan(input(cfg = cfg)))
        assertEquals(1, JSONObject(d.configJson).getInt("dailyLimit"))
        assertEquals("MULTI", d.type)
    }

    @Test
    fun interval_policy_keeps_requested_days() {
        val d = draft(CreatePlanner.plan(input(editPolicy = "INTERVAL_N", editInterval = 5)))
        assertEquals("INTERVAL_N", d.editPolicy)
        assertEquals(5, d.editInterval)
    }

    @Test
    fun interval_policy_defaults_to_seven_days_when_blank() {
        val d = draft(CreatePlanner.plan(input(editPolicy = "INTERVAL_N", editInterval = null)))
        assertEquals(7, d.editInterval)
    }

    @Test
    fun migrated_flex_item_stays_flex_even_if_page_requests_locked() {
        val d = draft(CreatePlanner.plan(input(editPolicy = "LOCKED", editingFlex = true)))
        assertEquals("FLEX", d.editPolicy)
        assertNull(d.editInterval)
    }
}
